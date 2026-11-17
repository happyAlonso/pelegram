package org.telegram.messenger.vpn;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Reads the body of a subscription link: share links one per line, as plain text or base64 encoded
 * (the v2rayN format that 3x-ui, Marzban, Remnawave and similar panels serve by default). Pure Java
 * with no Android classes, so it can be tested on its own.
 *
 * Lines are taken whole. {@link SingBoxConfigBuilder#extractKeys} is made for pasted chat text and
 * would cut a line at the first space and drop invisible characters, which breaks a server name
 * with raw spaces or an emoji sequence joined with U+200D.
 */
public final class VpnSubscriptionParser {

    /**
     * Schemes a subscription line may start with: the share links SingBoxConfigBuilder reads as a
     * plain outbound. A subscription runs as one urltest group, and vpn:// (a WireGuard endpoint)
     * cannot sit in it, so it is skipped like trojan:// or vmess://.
     */
    static final String[] LINE_SCHEMES = {"vless://", "hysteria2://", "hy2://", "ss://"};
    /** More keys than this is not a subscription for one person; the cap keeps a broken server from filling memory. */
    public static final int MAX_KEYS = 500;
    private static final int MAX_TITLE_LENGTH = 64;

    public static final String ERROR_EMPTY = "empty body";
    public static final String ERROR_FORMAT = "not a subscription";

    public static final class Parsed {
        /** Key lines in body order, without duplicates. Empty when {@link #error} is set. */
        public final List<String> lines;
        public final boolean base64;
        public final String error;
        /** Lines with a scheme this app does not run, as "3 trojan, 1 vmess", empty for none. */
        public final String skipped;

        Parsed(List<String> lines, boolean base64, String error, String skipped) {
            this.lines = lines;
            this.base64 = base64;
            this.error = error;
            this.skipped = skipped;
        }
    }

    private VpnSubscriptionParser() {
    }

    public static Parsed parse(String body) {
        if (body == null || body.trim().isEmpty()) {
            return new Parsed(new ArrayList<>(), false, ERROR_EMPTY, "");
        }
        boolean base64 = false;
        String text = body;
        if (!hasUriLine(body)) {
            String decoded = decodeBase64(body);
            if (decoded == null || !hasUriLine(decoded)) {
                return new Parsed(new ArrayList<>(), false, ERROR_FORMAT, "");
            }
            text = decoded;
            base64 = true;
        }
        List<String> lines = keyLines(text);
        String skipped = skippedSchemes(text);
        if (lines.isEmpty()) {
            return new Parsed(lines, base64, skipped.isEmpty() ? ERROR_FORMAT : "no supported keys (" + skipped + ")", skipped);
        }
        return new Parsed(lines, base64, null, skipped);
    }

    private static final java.util.regex.Pattern URI_LINE = java.util.regex.Pattern.compile("^\\uFEFF*\\s*([A-Za-z][A-Za-z0-9+.-]{1,15})://\\S");

    /** Whether some line starts like a share link (any scheme), the test for a plain-text body. */
    private static boolean hasUriLine(String text) {
        for (String raw : text.split("\r\n|\r|\n")) {
            if (URI_LINE.matcher(raw).find()) {
                return true;
            }
        }
        return false;
    }

    /** Count of share links per scheme that {@link #LINE_SCHEMES} leaves out, "3 trojan, 1 vmess". */
    static String skippedSchemes(String text) {
        java.util.TreeMap<String, Integer> counts = new java.util.TreeMap<>();
        for (String raw : text.split("\r\n|\r|\n")) {
            java.util.regex.Matcher m = URI_LINE.matcher(raw);
            if (m.find() && !startsWithScheme(raw.trim().replace("﻿", ""))) {
                String scheme = m.group(1).toLowerCase(Locale.ROOT);
                Integer n = counts.get(scheme);
                counts.put(scheme, n == null ? 1 : n + 1);
            }
        }
        StringBuilder b = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
            b.append(b.length() == 0 ? "" : ", ").append(e.getValue()).append(' ').append(e.getKey());
        }
        return b.toString();
    }

    /** Every line that starts with a known scheme, whole, trimmed, without duplicates, at most {@link #MAX_KEYS}. */
    static List<String> keyLines(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : text.split("\r\n|\r|\n")) {
            String line = raw.trim();
            // trim() keeps a byte order mark, which some generators put before the first line
            while (!line.isEmpty() && line.charAt(0) == '﻿') {
                line = line.substring(1).trim();
            }
            if (startsWithScheme(line)) {
                out.add(line);
                if (out.size() >= MAX_KEYS) {
                    break;
                }
            }
        }
        return new ArrayList<>(out);
    }

    static boolean startsWithScheme(String line) {
        String head = line.substring(0, Math.min(line.length(), 16)).toLowerCase(Locale.ROOT);
        for (String scheme : LINE_SCHEMES) {
            if (head.startsWith(scheme)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Standard or URL-safe base64, padded or not, with line breaks or other whitespace anywhere.
     * Null when the text is not base64 or does not decode to valid UTF-8. android.util.Base64 is
     * not usable here (pure Java) and java.util.Base64 needs API 26.
     */
    static String decodeBase64(String text) {
        StringBuilder b = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '﻿') {
                continue;
            }
            b.append(c);
        }
        int len = b.length();
        while (len > 0 && b.charAt(len - 1) == '=') {
            len--;
        }
        if (len == 0 || len % 4 == 1) {
            return null;
        }
        byte[] out = new byte[len * 3 / 4];
        int acc = 0;
        int bits = 0;
        int o = 0;
        for (int i = 0; i < len; i++) {
            int v = base64Value(b.charAt(i));
            if (v < 0) {
                return null;
            }
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[o++] = (byte) (acc >> bits);
                acc &= (1 << bits) - 1;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(out, 0, o)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static int base64Value(char c) {
        if (c >= 'A' && c <= 'Z') {
            return c - 'A';
        }
        if (c >= 'a' && c <= 'z') {
            return c - 'a' + 26;
        }
        if (c >= '0' && c <= '9') {
            return c - '0' + 52;
        }
        if (c == '+' || c == '-') {
            return 62;
        }
        if (c == '/' || c == '_') {
            return 63;
        }
        return -1;
    }

    /**
     * The profile-title header: plain text, or "base64:" and base64 text. Control characters are
     * removed and the length is capped. Null when the header is missing or empty after that.
     */
    public static String decodeTitle(String header) {
        if (header == null) {
            return null;
        }
        String t = header.trim();
        if (t.regionMatches(true, 0, "base64:", 0, 7)) {
            t = decodeBase64(t.substring(7));
            if (t == null) {
                return null;
            }
        }
        StringBuilder b = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (!Character.isISOControl(c)) {
                b.append(c);
            }
        }
        String clean = b.toString().trim();
        if (clean.length() > MAX_TITLE_LENGTH) {
            clean = clean.substring(0, MAX_TITLE_LENGTH).trim();
        }
        return clean.isEmpty() ? null : clean;
    }

    /**
     * The first http:// or https:// link in pasted text, without trailing punctuation, or null.
     * The caller rejects plain http.
     */
    public static String findUrl(String text) {
        if (text == null) {
            return null;
        }
        for (String token : text.split("\\s+")) {
            String low = token.toLowerCase(Locale.ROOT);
            int start = low.indexOf("https://");
            if (start < 0) {
                start = low.indexOf("http://");
            }
            if (start < 0) {
                continue;
            }
            int end = token.length();
            while (end > start && "\"'<>()[]{},;.".indexOf(token.charAt(end - 1)) >= 0) {
                end--;
            }
            String url = token.substring(start, end);
            if (url.length() > "https://".length()) {
                return url;
            }
        }
        return null;
    }

    /** Query parameters a subscription may change on every download without changing the server. */
    private static final String[] VOLATILE_PARAMS = {"sni", "sid"};

    /**
     * What a list of keys means for the tunnel, for deciding whether an update changed anything.
     * BlancVPN returns new sni and sid values for every server on every download, and the old ones
     * keep working (home_rtr notes, 2026-11-16), so those two are left out. The #name stays: the
     * servers share 6 entry hosts and the SNI picks the exit country, so without sni the name is
     * what tells the 50 servers apart (two real downloads on 2026-11-17 differed in sni on 50 lines,
     * sid on 48, nothing else). Order does not matter. Equal identity means the same servers.
     */
    public static String identity(List<String> keys) {
        ArrayList<String> parts = new ArrayList<>();
        for (String key : keys) {
            parts.add(stripVolatile(key));
        }
        java.util.Collections.sort(parts);
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            b.append(p).append('\n');
        }
        return b.toString();
    }

    static String stripVolatile(String key) {
        String k = key;
        String fragment = "";
        int hash = k.indexOf('#');
        if (hash >= 0) {
            fragment = k.substring(hash);
            k = k.substring(0, hash);
        }
        int q = k.indexOf('?');
        if (q < 0) {
            return k + fragment;
        }
        ArrayList<String> kept = new ArrayList<>();
        for (String kv : k.substring(q + 1).split("&")) {
            if (kv.isEmpty()) {
                continue;
            }
            int eq = kv.indexOf('=');
            String name = eq < 0 ? kv : kv.substring(0, eq);
            boolean drop = false;
            for (String v : VOLATILE_PARAMS) {
                if (v.equals(name)) {
                    drop = true;
                    break;
                }
            }
            if (!drop) {
                kept.add(kv);
            }
        }
        java.util.Collections.sort(kept); // a panel may list the same parameters in another order
        StringBuilder b = new StringBuilder();
        for (String kv : kept) {
            b.append(b.length() == 0 ? "" : "&").append(kv);
        }
        return k.substring(0, q) + (b.length() == 0 ? "" : "?" + b) + fragment;
    }

    public static boolean isHttps(String url) {
        return url != null && url.regionMatches(true, 0, "https://", 0, 8);
    }

    /**
     * The link with everything after the host replaced, for logs and error text: the path and
     * query carry the access token, the host helps tell a blocked domain from a dead server.
     */
    public static String maskUrl(String url) {
        if (url == null) {
            return "null";
        }
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return "<link>";
        }
        int pathStart = url.length();
        for (int i = scheme + 3; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                pathStart = i;
                break;
            }
        }
        return pathStart < url.length() ? url.substring(0, pathStart) + "/<token>" : url;
    }
}
