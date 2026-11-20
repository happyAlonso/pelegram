package org.telegram.messenger.vpn;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;

/**
 * Routes the in-app browser through the embedded sing-box proxy: ALL in-process WebViews (the in-app
 * browser {@code BotWebViewContainer.MyWebView}, Instant View, embeds, media players, bot mini-apps)
 * through a process-wide WebView proxy override, and the browser's own Java requests (address bar
 * suggestions, popups, tonsite, favicons, file fetches, embedded players, downloads) through
 * {@link #openConnection(URL)}. Driven by {@link VpnController#updateBrowserRouting()}.
 *
 * Three targets: the core's port while the tunnel is up; {@link #BLOCK_PORT} while the VPN is on but
 * the tunnel is not up (connecting, a core restart, an error), so pages and requests fail instead of
 * going out with the real address; direct only when the VPN is off.
 *
 * We point WebView at the HTTP side of the sing-box "mixed" inbound ({@code http://127.0.0.1:port}).
 * WebView hands the proxy the hostname (GET absolute-URI for http, CONNECT host:443 for https), so
 * the proxy resolves DNS remotely - no DNS leak. The rule list has no "direct://" entry, so a dead
 * proxy fails the page and never falls back to a direct connection. On a system WebView without
 * PROXY_OVERRIDE (very old) the override can't be set and pages go direct; that is logged once.
 */
public class WebViewProxyController {

    /** No override: the VPN is off. */
    public static final int DIRECT = -1;
    /** A loopback port nothing listens on: the VPN is on but the tunnel is not up, so fail closed. */
    public static final int BLOCK_PORT = 9;

    // What the browser should use now: DIRECT, BLOCK_PORT or the core's port. Read from any thread.
    private static volatile int target = DIRECT;
    // What WebView was last given (UI thread only). Guards against redundant sets.
    private static int webViewPort = DIRECT;
    private static boolean unsupportedLogged;

    /** Route everything through the local proxy on the given port. port <= 0 blocks. */
    public static void apply(int port) {
        set(port > 0 ? port : BLOCK_PORT);
    }

    /** The VPN is on but the tunnel is not up: pages and browser requests fail instead of going direct. */
    public static void block() {
        set(BLOCK_PORT);
    }

    /** The VPN is off: remove the override, browser traffic goes direct again. */
    public static void clear() {
        set(DIRECT);
    }

    private static void set(int port) {
        target = port;
        if (webViewPort == port) {
            return; // before any WebView call, so a VPN-off start never loads the WebView provider
        }
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                if (!unsupportedLogged) {
                    unsupportedLogged = true;
                    FileLog.e("WebViewProxy: the system WebView has no proxy override, in-app browser pages go direct");
                }
                return;
            }
            if (port == DIRECT) {
                ProxyController.getInstance().clearProxyOverride(Runnable::run, () -> {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("WebViewProxy: override cleared, WebView goes direct");
                    }
                });
            } else {
                ProxyConfig config = new ProxyConfig.Builder()
                        .addProxyRule("http://127.0.0.1:" + port)
                        .build();
                ProxyController.getInstance().setProxyOverride(config, Runnable::run, () -> {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("WebViewProxy: override set, WebView uses 127.0.0.1:" + port);
                    }
                });
            }
            webViewPort = port;
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("WebViewProxy: " + (port == DIRECT ? "direct (VPN off)"
                        : port == BLOCK_PORT ? "blocked until the tunnel is up" : "routing WebView through 127.0.0.1:" + port));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * The proxy for a Java request the browser makes itself. An explicit proxy, never a
     * ProxySelector: Android's HttpURLConnection (OkHttp 2 RouteSelector) appends a direct attempt
     * after the ProxySelector's proxies, and uses only the given proxy when one is passed.
     */
    public static Proxy javaProxy() {
        int port = target;
        if (port == DIRECT) {
            return Proxy.NO_PROXY;
        }
        return new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port));
    }

    /**
     * {@code url.openConnection()} through the tunnel while the VPN is on. With the VPN off it is
     * exactly upstream's call, so a Wi-Fi or PAC proxy from the system still applies.
     */
    public static URLConnection openConnection(URL url) throws IOException {
        Proxy proxy = javaProxy();
        return proxy == Proxy.NO_PROXY ? url.openConnection() : url.openConnection(proxy);
    }

    /** Whether the VPN is on but the tunnel is not up yet, so browser traffic is blocked. */
    public static boolean isBlocked() {
        return target == BLOCK_PORT;
    }

    /** Whether the VPN is on, so browser traffic must stay in this process (no DownloadManager, no Custom Tabs). */
    public static boolean isRouted() {
        return target != DIRECT;
    }
}
