package org.telegram.messenger.vpn;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

/**
 * Routes ALL in-process WebViews (the in-app browser {@code BotWebViewContainer.MyWebView}, Instant
 * View, embeds, media players, bot mini-apps) through the embedded sing-box proxy using a
 * process-wide WebView proxy override. Driven by {@link VpnController}: applied when the tunnel is up
 * with a live local port, cleared when it goes down. No-op on WebView builds without PROXY_OVERRIDE
 * support (very old system WebView), in which case browser traffic simply goes direct.
 *
 * We point WebView at the HTTP side of the sing-box "mixed" inbound ({@code http://127.0.0.1:port}).
 * WebView hands the proxy the hostname (GET absolute-URI for http, CONNECT host:443 for https), so
 * the proxy resolves DNS remotely - no DNS leak, same as SOCKS5 remote resolution, but HTTP proxy
 * override is the most broadly supported WebView path.
 */
public class WebViewProxyController {

    // Port currently pushed into WebView (-1 = no override active). Guards against redundant sets.
    private static int appliedPort = -1;

    /** Route every WebView through the local proxy on the given port. port <= 0 clears the override. */
    public static void apply(int port) {
        if (port <= 0) {
            clear();
            return;
        }
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                return;
            }
            if (appliedPort == port) {
                return;
            }
            ProxyConfig config = new ProxyConfig.Builder()
                    .addProxyRule("http://127.0.0.1:" + port)
                    .build();
            ProxyController.getInstance().setProxyOverride(config, Runnable::run, () -> {});
            appliedPort = port;
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("WebViewProxy: routing WebView through 127.0.0.1:" + port);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Remove the process-wide WebView proxy override (browser traffic goes direct again). */
    public static void clear() {
        if (appliedPort == -1) {
            return;
        }
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                ProxyController.getInstance().clearProxyOverride(Runnable::run, () -> {});
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("WebViewProxy: cleared WebView proxy override");
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        appliedPort = -1;
    }
}
