package org.telegram.messenger.vpn;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

/**
 * Where tgnet (every MTProto connection of every account: messages, media, push, CDN, uploads)
 * sends its traffic. While the VPN is on it is always a local SOCKS5 port: the core's while it is
 * connected, {@link WebViewProxyController#BLOCK_PORT} (nothing listens) while it starts, restarts
 * after a server switch or fails, so Telegram is unreachable instead of reached direct. Only when
 * the VPN is turned off is the proxy cleared. Before this, every core stop cleared it and tgnet
 * went direct with the real address until the next core came up.
 *
 * Callers: {@link SingBoxManager} (the core's port, worker thread, right after the core starts) and
 * {@link VpnController#updateRouting()} (UI thread, every state change). The last call wins, and
 * a repeated target is skipped, because every change reconnects all datacenters.
 */
public final class TgnetRouting {

    /** Nothing set by the VPN: tgnet uses the user's own proxy setting, or none. */
    public static final int DIRECT = -1;

    private static int applied = DIRECT;

    private TgnetRouting() {
    }

    /** tgnet through the core on this port; port <= 0 blocks. */
    public static synchronized void apply(int port) {
        set(port > 0 ? port : WebViewProxyController.BLOCK_PORT);
    }

    /** The VPN is on but the core is not connected: tgnet reaches nothing. */
    public static synchronized void block() {
        set(WebViewProxyController.BLOCK_PORT);
    }

    /** The VPN is off. No-op unless the VPN set something, so a user's own proxy stays as it is. */
    public static synchronized void clear() {
        set(DIRECT);
    }

    /** The port tgnet uses now: {@link #DIRECT}, the block port or the core's port. */
    public static synchronized int current() {
        return applied;
    }

    private static void set(int port) {
        if (applied == port) {
            return;
        }
        applied = port;
        try {
            if (port == DIRECT) {
                // the VPN is off: back to the user's own proxy setting, the one init() would apply
                ConnectionsManager.setVpnTunnelActive(false);
                ProxySettings own = null;
                try {
                    SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
                    ProxySettings saved = ProxySettings.fromSharedPreferences(prefs);
                    if (prefs.getBoolean("proxy_enabled", false) && saved != null && saved.isValid()) {
                        own = saved;
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                ConnectionsManager.applyProxySettings(own != null, own);
            } else {
                // flag the tunnel first: setProxySettings reconnects every datacenter, and those first
                // dials must already rotate addresses and keep the IPv4-only strategy (ConnectionsManager)
                ConnectionsManager.setVpnTunnelActive(port != WebViewProxyController.BLOCK_PORT);
                ConnectionsManager.applyProxySettings(true, ProxySettings.builder()
                        .setType(ProxySettings.Type.SOCKS5)
                        .setAddress("127.0.0.1")
                        .setPort(port)
                        .build());
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("TgnetRouting: " + (port == DIRECT ? "direct (VPN off)"
                        : port == WebViewProxyController.BLOCK_PORT ? "blocked until the tunnel is up" : "through 127.0.0.1:" + port));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * For {@code ConnectionsManager.init} of an account: the port its tgnet must start on, or
     * {@link #DIRECT} when the VPN is off. At process start that runs before {@link VpnController}
     * has loaded, so the saved VPN state decides and tgnet starts blocked instead of direct; an
     * account set up later gets whatever the others use now.
     */
    public static synchronized int startPort() {
        if (applied == DIRECT && vpnOnInPrefs()) {
            applied = WebViewProxyController.BLOCK_PORT;
            // the app's own Java requests too, until VpnController loads and routes everything
            WebViewProxyController.blockJavaEarly();
        }
        return applied;
    }

    /**
     * Whether the VPN owns tgnet's proxy now, so the app's own proxy settings must not touch it and
     * its proxy checks must not connect to the user's proxies direct. True from the first moment of a
     * process whose saved VPN state is on.
     */
    public static boolean holdsProxy() {
        return startPort() != DIRECT;
    }

    /** Whether the saved VPN state is on: the switch on and at least one key. */
    static boolean vpnOnInPrefs() {
        try {
            SharedPreferences p = ApplicationLoader.applicationContext.getSharedPreferences("vpnconfig", Context.MODE_PRIVATE);
            if (!p.getBoolean("enabled", false)) {
                return false;
            }
            String list = p.getString("list", null);
            return list != null && list.trim().length() > 2; // a non-empty JSON array
        } catch (Throwable e) {
            return false;
        }
    }
}
