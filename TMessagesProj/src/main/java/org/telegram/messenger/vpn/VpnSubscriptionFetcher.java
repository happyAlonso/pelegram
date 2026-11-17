package org.telegram.messenger.vpn;

import android.os.SystemClock;
import android.text.TextUtils;

import org.telegram.messenger.BuildVars;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLException;

/**
 * Downloads a subscription link and turns it into keys. Blocking: call it on a background thread.
 *
 * Through the tunnel the request goes to the core's mixed inbound as an HTTP proxy. HTTPS then
 * travels in a CONNECT tunnel that names the host, and the core resolves it through the VPN, so
 * neither the request nor its DNS lookup leaves the device directly.
 */
public final class VpnSubscriptionFetcher {

    private static final int CONNECT_TIMEOUT_MS = 15 * 1000;
    private static final int READ_TIMEOUT_MS = 20 * 1000;
    private static final int MAX_REDIRECTS = 5;
    /** Counted on the decoded stream, so a gzip body cannot get past it. 500 keys are about 300 KB. */
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    /**
     * Panels pick the format by User-Agent: a clash or sing-box agent gets YAML or JSON, an unknown
     * one the base64 list. Checked against the first real subscription on 2026-11-17: base64.
     */
    private static final String USER_AGENT = "pelegram/" + BuildVars.BUILD_VERSION_STRING;

    public static final class Result {
        /** Short reason, null on success. Shown on the subscription screen. */
        public String error;
        /** The error in full: exception chain with stack frames, or the HTTP status and body start. Link masked. */
        public String errorDetail;
        /** One line for a toast: every exception in the chain with its message. Link masked. */
        public String errorLine;
        public boolean viaTunnel;
        /** The failed attempt through the tunnel that came before this direct one, or null. */
        public Result tunnelAttempt;
        public int httpCode;
        public String title;
        public List<VpnKeyInfo> keys = new ArrayList<>();
        public int rejected;
        /** Lines with schemes the group cannot run, "3 trojan", empty for none. */
        public String skipped = "";
        public boolean base64;
        public long tookMs;
        public int bodyBytes;
    }

    private VpnSubscriptionFetcher() {
    }

    /** proxyPort > 0: through the core on 127.0.0.1:proxyPort. Otherwise direct. */
    public static Result fetch(String url, int proxyPort) {
        long start = SystemClock.elapsedRealtime();
        Result r = new Result();
        r.viaTunnel = proxyPort > 0;
        Proxy proxy = proxyPort > 0
                ? new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", proxyPort))
                : Proxy.NO_PROXY;
        List<String> seenUrls = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            String current = url;
            for (int hop = 0; ; hop++) {
                seenUrls.add(current);
                if (!VpnSubscriptionParser.isHttps(current)) {
                    // the app allows cleartext (AndroidManifest usesCleartextTraffic), so check every hop
                    fail(r, seenUrls, "https only", (hop == 0 ? "the link is not https: " : "redirect to a non-https link: ")
                            + VpnSubscriptionParser.maskUrl(current));
                    return r;
                }
                URL u = new URL(current);
                conn = (HttpURLConnection) u.openConnection(proxy);
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setUseCaches(false);
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("Accept", "*/*");
                int code = conn.getResponseCode();
                r.httpCode = code;
                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    conn.disconnect();
                    conn = null;
                    if (TextUtils.isEmpty(location) || hop >= MAX_REDIRECTS) {
                        fail(r, seenUrls, "bad redirect", "HTTP " + code + (TextUtils.isEmpty(location)
                                ? " without a Location header" : ", more than " + MAX_REDIRECTS + " redirects"));
                        return r;
                    }
                    current = new URL(u, location).toString();
                    continue;
                }
                if (code < 200 || code >= 300) {
                    String body = readErrorBody(conn);
                    fail(r, seenUrls, "HTTP " + code, "HTTP " + code + " " + nullToEmpty(conn.getResponseMessage())
                            + ", content-type " + conn.getContentType()
                            + (body.isEmpty() ? ", empty body" : ", body starts: " + sanitize(body, 300)));
                    return r;
                }
                r.title = VpnSubscriptionParser.decodeTitle(conn.getHeaderField("profile-title"));
                byte[] raw = readCapped(conn.getInputStream());
                if (raw == null) {
                    fail(r, seenUrls, "too large", "the body is over " + MAX_BODY_BYTES + " bytes");
                    return r;
                }
                r.bodyBytes = raw.length;
                String body = new String(raw, StandardCharsets.UTF_8);
                VpnSubscriptionParser.Parsed parsed = VpnSubscriptionParser.parse(body);
                if (parsed.error != null) {
                    // a body with share links in it is never quoted: they hold passwords and hosts
                    fail(r, seenUrls, parsed.error, parsed.error + ": " + raw.length + " bytes, content-type "
                            + conn.getContentType() + (body.contains("://")
                            ? ", " + body.split("\r\n|\r|\n").length + " lines"
                            : ", starts: " + sanitize(body, 80)));
                    return r;
                }
                r.base64 = parsed.base64;
                r.skipped = parsed.skipped;
                StringBuilder rejects = new StringBuilder();
                for (int i = 0; i < parsed.lines.size(); i++) {
                    String line = parsed.lines.get(i);
                    try {
                        SingBoxConfigBuilder.buildOutbound(line);
                    } catch (Exception e) {
                        r.rejected++;
                        int sep = line.indexOf("://");
                        rejects.append(rejects.length() == 0 ? "" : "; ").append("line ").append(i + 1).append(' ')
                                .append(sep > 0 ? line.substring(0, sep) : "?").append(": ").append(sanitize(String.valueOf(e), 120));
                        continue;
                    }
                    String name = SingBoxConfigBuilder.keyName(line);
                    VpnKeyInfo info = new VpnKeyInfo(name, line);
                    if (TextUtils.isEmpty(name)) {
                        info.name = info.getType() + " " + (i + 1);
                    }
                    r.keys.add(info);
                }
                if (r.keys.isEmpty()) {
                    fail(r, seenUrls, "no valid keys", parsed.lines.size() + " lines, none could be read: " + rejects);
                    return r;
                }
                if (r.rejected > 0) {
                    // not a failure, but worth seeing why: logged by the caller with the result
                    r.errorDetail = r.rejected + " of " + parsed.lines.size() + " lines skipped: " + rejects;
                }
                return r;
            }
        } catch (Throwable e) {
            r.error = shortReason(e);
            r.errorLine = mask(chainLine(e), seenUrls);
            r.errorDetail = mask(fullTrace(e), seenUrls);
            return r;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
            r.tookMs = SystemClock.elapsedRealtime() - start;
        }
    }

    /** Every failure goes through here or the catch block, and both mask the link. */
    private static void fail(Result r, List<String> seenUrls, String reason, String detail) {
        r.error = reason;
        r.errorLine = mask(reason.equals(detail) ? detail : reason + ": " + detail, seenUrls);
        r.errorDetail = r.errorLine;
    }

    private static String shortReason(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException) {
                return "timeout";
            }
            if (t instanceof UnknownHostException) {
                return "host not found";
            }
            if (t instanceof SSLException) {
                return "TLS error";
            }
        }
        return e instanceof IOException ? "network error" : e.getClass().getSimpleName();
    }

    /** "SocketTimeoutException: timeout <- ..." for every exception in the cause chain. */
    private static String chainLine(Throwable e) {
        StringBuilder b = new StringBuilder();
        int depth = 0;
        for (Throwable t = e; t != null && depth < 6; t = t.getCause(), depth++) {
            if (b.length() > 0) {
                b.append(" <- ");
            }
            b.append(t.getClass().getSimpleName());
            if (t.getMessage() != null) {
                b.append(": ").append(t.getMessage());
            }
        }
        return b.toString();
    }

    /** Every exception in the chain with its message and up to 20 stack frames. */
    private static String fullTrace(Throwable e) {
        StringBuilder b = new StringBuilder();
        int depth = 0;
        for (Throwable t = e; t != null && depth < 6; t = t.getCause(), depth++) {
            b.append(depth == 0 ? "" : "\nCaused by: ").append(t);
            StackTraceElement[] stack = t.getStackTrace();
            for (int i = 0; i < Math.min(stack.length, 20); i++) {
                b.append("\n    at ").append(stack[i]);
            }
        }
        return b.toString();
    }

    /** Replaces each link that was requested, and its path alone, with the masked form. */
    private static String mask(String text, List<String> urls) {
        for (String u : urls) {
            String masked = VpnSubscriptionParser.maskUrl(u);
            text = text.replace(u, masked);
            int scheme = u.indexOf("://");
            int path = scheme < 0 ? -1 : u.indexOf('/', scheme + 3);
            if (path > 0 && path < u.length() - 1) {
                text = text.replace(u.substring(path), "/<token>");
            }
        }
        return text;
    }

    /**
     * Text from the server or from a key parser, made safe for a log and a toast: one line, every
     * link cut to its scheme, runs of 20+ token characters replaced, then shortened. Masking comes
     * first, so a cut cannot leave a short piece of a token in clear.
     */
    private static String sanitize(String s, int max) {
        String one = s.replace("\r", "\\r").replace("\n", "\\n");
        one = one.replaceAll("\\b([A-Za-z][A-Za-z0-9+.-]{1,15})://\\S+", "$1://<link>");
        one = one.replaceAll("[A-Za-z0-9+/_=-]{20,}", "<token>");
        if (one.length() > max) {
            one = one.substring(0, max) + "...";
        }
        return one;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BODY_BYTES) {
                    return null;
                }
            }
            return out.toByteArray();
        }
    }

    private static String readErrorBody(HttpURLConnection conn) {
        try (InputStream s = conn.getErrorStream()) {
            if (s == null) {
                return "";
            }
            byte[] buf = new byte[1024];
            int total = 0;
            int n;
            while (total < buf.length && (n = s.read(buf, total, buf.length - total)) != -1) {
                total += n;
            }
            return new String(buf, 0, total, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }
}
