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
 * {@link #openConnection(URL)}. Driven by {@link VpnController#updateRouting()}.
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

    /**
     * At process start with the VPN on, before {@link VpnController} has loaded: block the app's own
     * Java requests without touching the WebView provider. The next {@link #block()} or
     * {@link #apply(int)} also sets the WebView override.
     */
    static void blockJavaEarly() {
        if (target == DIRECT) {
            target = BLOCK_PORT;
        }
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

    /**
     * Injected at document start into every frame of a WebView created while the VPN is on: removes
     * the WebRTC constructors, whose UDP (STUN, ICE candidates) the proxy override does not cover.
     */
    public static final String NO_WEBRTC_SCRIPT = "(function(){['RTCPeerConnection','webkitRTCPeerConnection','mozRTCPeerConnection']"
            + ".forEach(function(k){try{Object.defineProperty(window,k,{value:undefined,writable:false,configurable:false});}catch(e){}});})();";

    /**
     * Called right after a WebView is created: while the VPN is on, its pages get no WebRTC (see
     * {@link #NO_WEBRTC_SCRIPT}). A WebView created while the VPN is off keeps WebRTC until it is
     * created again.
     */
    public static void harden(android.webkit.WebView webView) {
        if (webView == null || !isRouted()) {
            return;
        }
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, NO_WEBRTC_SCRIPT, java.util.Collections.singleton("*"));
            } else if (!unsupportedLogged) {
                FileLog.e("WebViewProxy: the system WebView cannot run a document-start script, WebRTC stays on");
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static boolean selectorInstalled;

    /**
     * A second layer for Java HTTP in libraries we don't call ourselves (the Stripe SDK, Firebase,
     * anything that does {@code url.openConnection()}): while the VPN is on they get the core's port
     * as their proxy. Not fail closed: Android's HttpURLConnection appends one direct attempt after
     * a ProxySelector's proxies (AOSP okhttp RouteSelector.resetNextProxy), so a failed proxy
     * connection still goes direct. That is why every request site we own passes an explicit proxy
     * through {@link #openConnection(URL)}. With the VPN off the previous selector answers, so a
     * Wi-Fi or PAC proxy still applies. Installed once per process at startup.
     */
    public static synchronized void installProxySelector() {
        if (selectorInstalled) {
            return;
        }
        selectorInstalled = true;
        try {
            final java.net.ProxySelector system = java.net.ProxySelector.getDefault();
            java.net.ProxySelector.setDefault(new java.net.ProxySelector() {
                @Override
                public java.util.List<Proxy> select(java.net.URI uri) {
                    int port = target;
                    String host = uri == null ? null : uri.getHost();
                    boolean loopback = host != null && (host.equals("localhost") || host.startsWith("127.") || host.equals("::1") || host.equals("[::1]"));
                    if (port == DIRECT || loopback || isLocalNetwork(host)) {
                        return system != null ? system.select(uri) : java.util.Collections.singletonList(Proxy.NO_PROXY);
                    }
                    return java.util.Collections.singletonList(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port)));
                }

                @Override
                public void connectFailed(java.net.URI uri, java.net.SocketAddress sa, IOException ioe) {
                    if (system != null && target == DIRECT) {
                        system.connectFailed(uri, sa, ioe);
                    }
                }
            });
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** A literal address on the local network (a cast device, a LAN server): the tunnel can't reach it. */
    static boolean isLocalNetwork(String host) {
        if (host == null) {
            return false;
        }
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("169.254.")) {
            return true;
        }
        if (h.startsWith("172.")) {
            int dot = h.indexOf('.', 4);
            try {
                int second = Integer.parseInt(dot > 0 ? h.substring(4, dot) : "");
                return second >= 16 && second <= 31;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        String lower = h.toLowerCase();
        return lower.startsWith("fe80:") || (lower.indexOf(':') > 0 && (lower.startsWith("fc") || lower.startsWith("fd")));
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
