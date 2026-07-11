package org.telegram.messenger.vpn;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.zip.Inflater;

/**
 * Turns a pasted VPN share link into a sing-box config JSON with:
 *   - a local `socks` inbound on 127.0.0.1:localPort (what tgnet's proxy is pointed at),
 *   - one outbound parsed from the key,
 *   - a route sending everything to that outbound.
 *
 * Supported schemes: hysteria2:// , vless:// , and AmneziaWG (awg-quick .conf text).
 *
 * NOTE (interop risks, to validate on-device against the real finn/amnezia servers):
 *  - VLESS: the xhttp transport (incl. mode=packet-up and host) is mapped from the URL. Verified
 *    against the finn REALITY+xhttp server. The `mode` must match the server or the handshake 404s.
 *  - AmneziaWG: field mapping follows the lx fork schema (jc/jmin/jmax, s1-s4, h1-h4, i1-i5).
 */
public class SingBoxConfigBuilder {

    public static final String TAG_PROXY = "proxy-out";
    public static final String TAG_SOCKS_IN = "socks-in";

    /** Build the full sing-box config for a key, with the SOCKS inbound on the given port. */
    public static String build(String rawKey, int localPort) throws Exception {
        JSONObject outbound = buildOutbound(rawKey);
        // The core's default resolver on Android points at a non-existent local :53 and fails, so a
        // hostname server (e.g. Outline keys) never connects. Resolve it here with the device
        // resolver (which works - it's what the browser/Outline use) and hand the core an IP.
        resolveServerAddresses(outbound);

        // "mixed" serves BOTH SOCKS5 and HTTP CONNECT on the one port: tgnet uses the SOCKS side, while
        // routed calls use the HTTP side (webrtc can only proxy over HTTP CONNECT, not SOCKS).
        JSONObject socksIn = new JSONObject();
        socksIn.put("type", "mixed");
        socksIn.put("tag", TAG_SOCKS_IN);
        socksIn.put("listen", "127.0.0.1");
        socksIn.put("listen_port", localPort);

        JSONObject direct = new JSONObject();
        direct.put("type", "direct");
        direct.put("tag", "direct");

        JSONObject route = new JSONObject();
        // Everything from the socks inbound leaves via the proxy outbound/endpoint (by tag).
        route.put("final", TAG_PROXY);

        JSONObject log = new JSONObject();
        log.put("level", "info");
        log.put("timestamp", true);

        JSONObject root = new JSONObject();
        root.put("log", log);
        root.put("inbounds", new JSONArray().put(socksIn));
        // wireguard (incl. AmneziaWG) is an `endpoint`, not an `outbound`, in current sing-box.
        if ("wireguard".equals(outbound.optString("type"))) {
            root.put("endpoints", new JSONArray().put(outbound));
            root.put("outbounds", new JSONArray().put(direct));
        } else {
            root.put("outbounds", new JSONArray().put(outbound).put(direct));
        }
        root.put("route", route);
        return root.toString();
    }

    /**
     * Replace hostname server addresses with resolved IPs, so the core never has to run DNS itself
     * (its Android default resolver targets a dead local :53). Called only on the connect path
     * (build), off the main thread - never from buildOutbound, which may run on the UI thread.
     */
    private static void resolveServerAddresses(JSONObject outbound) throws JSONException {
        String server = outbound.optString("server", null);
        if (server != null) {
            outbound.put("server", resolveHost(server));
        }
        JSONArray peers = outbound.optJSONArray("peers"); // wireguard / AmneziaWG endpoint
        if (peers != null) {
            for (int i = 0; i < peers.length(); i++) {
                JSONObject peer = peers.optJSONObject(i);
                if (peer != null) {
                    String addr = peer.optString("address", null);
                    if (addr != null) {
                        peer.put("address", resolveHost(addr));
                    }
                }
            }
        }
    }

    /** Resolve a hostname to an IP via the device resolver, preferring IPv4. IPs pass through. */
    private static String resolveHost(String host) {
        if (TextUtils.isEmpty(host) || isNumericAddress(host)) {
            return host;
        }
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            InetAddress first = null;
            for (InetAddress a : addrs) {
                if (first == null) {
                    first = a;
                }
                if (a instanceof Inet4Address) {
                    return a.getHostAddress();
                }
            }
            if (first != null) {
                return first.getHostAddress();
            }
        } catch (Exception ignored) {
            // fall through: leave the hostname and let the core try (it will likely fail, but we
            // don't want to hard-fail the connect on a transient resolver hiccup)
        }
        return host;
    }

    private static boolean isNumericAddress(String host) {
        if (host.indexOf(':') >= 0) {
            return true; // IPv6 literal
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) {
                return false;
            }
            for (int i = 0; i < p.length(); i++) {
                if (!Character.isDigit(p.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(p) > 255) {
                return false;
            }
        }
        return true;
    }

    /** Parse just the outbound object for a key (also used for pre-connect URLTest). */
    public static JSONObject buildOutbound(String rawKey) throws Exception {
        String key = rawKey.trim();
        // An edited connection is stored as a ready sing-box outbound JSON; an AmneziaVPN QR
        // decodes to its config JSON (with "containers").
        if (key.startsWith("{")) {
            JSONObject o = new JSONObject(key);
            if (o.has("containers")) {
                return parseAmneziaContainers(o);
            }
            o.put("tag", TAG_PROXY);
            return o;
        }
        String lower = key.toLowerCase();
        if (lower.startsWith("vpn://")) {
            return parseVpnUri(key);
        } else if (lower.startsWith("hysteria2://") || lower.startsWith("hy2://")) {
            return parseHysteria2(key);
        } else if (lower.startsWith("vless://")) {
            return parseVless(key);
        } else if (lower.startsWith("ss://")) {
            return parseShadowsocks(key);
        } else if (lower.contains("[interface]") || lower.startsWith("wg://") || lower.startsWith("awg://")) {
            return parseAmneziaWg(key);
        }
        throw new IllegalArgumentException("Unrecognized VPN key format");
    }

    // hysteria2://<auth>@<host>:<port>?obfs=salamander&obfs-password=..&sni=..&insecure=1#name
    private static JSONObject parseHysteria2(String key) throws Exception {
        Uri uri = Uri.parse(key);
        JSONObject o = new JSONObject();
        o.put("type", "hysteria2");
        o.put("tag", TAG_PROXY);
        o.put("server", uri.getHost());
        o.put("server_port", uri.getPort() > 0 ? uri.getPort() : 443);
        if (!TextUtils.isEmpty(uri.getUserInfo())) {
            o.put("password", decode(uri.getUserInfo()));
        }
        String obfs = uri.getQueryParameter("obfs");
        String obfsPw = uri.getQueryParameter("obfs-password");
        if (!TextUtils.isEmpty(obfs)) {
            JSONObject obfsObj = new JSONObject();
            obfsObj.put("type", obfs);
            if (!TextUtils.isEmpty(obfsPw)) {
                obfsObj.put("password", obfsPw);
            }
            o.put("obfs", obfsObj);
        }
        JSONObject tls = new JSONObject();
        tls.put("enabled", true);
        String sni = uri.getQueryParameter("sni");
        if (!TextUtils.isEmpty(sni)) {
            tls.put("server_name", sni);
        }
        if ("1".equals(uri.getQueryParameter("insecure"))) {
            tls.put("insecure", true);
        }
        o.put("tls", tls);
        return o;
    }

    // Shadowsocks / Outline. SIP002: ss://base64(method:password)@host:port[?plugin=..]#name
    // Legacy: ss://base64(method:password@host:port)#name
    private static JSONObject parseShadowsocks(String key) throws Exception {
        String s = key.substring("ss://".length());
        int hash = s.indexOf('#');
        if (hash >= 0) {
            s = s.substring(0, hash);
        }
        String method, password, host, pluginParam = null, prefixParam = null;
        int port;
        int at = s.indexOf('@');
        if (at >= 0) {
            String userinfo = new String(b64decode(s.substring(0, at)), StandardCharsets.UTF_8);
            int c = userinfo.indexOf(':');
            method = userinfo.substring(0, c);
            password = userinfo.substring(c + 1);
            String rest = s.substring(at + 1);
            int q = rest.indexOf('?');
            if (q >= 0) {
                String query = rest.substring(q + 1);
                pluginParam = queryValue(query, "plugin");
                prefixParam = queryValue(query, "prefix");
                rest = rest.substring(0, q);
            }
            int slash = rest.indexOf('/'); // Outline keys have a trailing /path before the query
            if (slash >= 0) {
                rest = rest.substring(0, slash);
            }
            int cc = rest.lastIndexOf(':');
            host = rest.substring(0, cc);
            port = Integer.parseInt(rest.substring(cc + 1));
        } else {
            // whole thing is base64(method:password@host:port)
            String decoded = new String(b64decode(s), StandardCharsets.UTF_8);
            int c = decoded.indexOf(':');
            int a = decoded.indexOf('@');
            method = decoded.substring(0, c);
            password = decoded.substring(c + 1, a);
            String hostport = decoded.substring(a + 1);
            int cc = hostport.lastIndexOf(':');
            host = hostport.substring(0, cc);
            port = Integer.parseInt(hostport.substring(cc + 1));
        }

        JSONObject o = new JSONObject();
        o.put("type", "shadowsocks");
        o.put("tag", TAG_PROXY);
        o.put("server", host);
        o.put("server_port", port);
        o.put("method", method);
        o.put("password", password);
        if (!TextUtils.isEmpty(pluginParam)) {
            String p = decode(pluginParam);
            int semi = p.indexOf(';');
            if (semi >= 0) {
                o.put("plugin", p.substring(0, semi));
                o.put("plugin_opts", p.substring(semi + 1));
            } else {
                o.put("plugin", p);
            }
        }
        if (!TextUtils.isEmpty(prefixParam)) {
            // Outline "prefix" (TLS-shaped salt disguise). Keys double-URL-encode it (%2516 -> %16
            // -> byte 0x16), so URL-decode once then percent-decode to raw bytes, base64 for the core.
            byte[] pfx = percentToBytes(URLDecoder.decode(prefixParam, StandardCharsets.UTF_8.name()));
            if (pfx.length > 0) {
                o.put("prefix", Base64.encodeToString(pfx, Base64.NO_WRAP));
            }
        }
        return o;
    }

    private static byte[] percentToBytes(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); ) {
            char ch = s.charAt(i);
            if (ch == '%' && i + 2 < s.length()) {
                try {
                    out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 3;
                    continue;
                } catch (NumberFormatException ignored) {
                }
            }
            out.write(ch & 0xff);
            i++;
        }
        return out.toByteArray();
    }

    private static byte[] b64decode(String in) {
        String t = in.replace('-', '+').replace('_', '/');
        switch (t.length() % 4) {
            case 2: t += "=="; break;
            case 3: t += "="; break;
            default: break;
        }
        return Base64.decode(t, Base64.NO_WRAP);
    }

    private static String queryValue(String query, String key) {
        for (String kv : query.split("&")) {
            int e = kv.indexOf('=');
            if (e > 0 && kv.substring(0, e).equals(key)) {
                return kv.substring(e + 1);
            }
        }
        return null;
    }

    // vless://<uuid>@<host>:<port>?type=xhttp&path=..&mode=packet-up&security=reality&pbk=..&fp=chrome&sni=..&sid=..#name
    private static JSONObject parseVless(String key) throws Exception {
        Uri uri = Uri.parse(key);
        JSONObject o = new JSONObject();
        o.put("type", "vless");
        o.put("tag", TAG_PROXY);
        o.put("server", uri.getHost());
        o.put("server_port", uri.getPort() > 0 ? uri.getPort() : 443);
        o.put("uuid", uri.getUserInfo());

        String flow = uri.getQueryParameter("flow");
        if (!TextUtils.isEmpty(flow)) {
            o.put("flow", flow);
        }

        String security = uri.getQueryParameter("security");
        if ("reality".equals(security) || "tls".equals(security)) {
            JSONObject tls = new JSONObject();
            tls.put("enabled", true);
            String sni = uri.getQueryParameter("sni");
            if (!TextUtils.isEmpty(sni)) {
                tls.put("server_name", sni);
            }
            String fp = uri.getQueryParameter("fp");
            JSONObject utls = new JSONObject();
            utls.put("enabled", true);
            utls.put("fingerprint", TextUtils.isEmpty(fp) ? "chrome" : fp);
            tls.put("utls", utls);
            if ("reality".equals(security)) {
                JSONObject reality = new JSONObject();
                reality.put("enabled", true);
                reality.put("public_key", uri.getQueryParameter("pbk"));
                String sid = uri.getQueryParameter("sid");
                if (!TextUtils.isEmpty(sid)) {
                    reality.put("short_id", sid);
                }
                tls.put("reality", reality);
            }
            o.put("tls", tls);
        }

        // Transport (xhttp / ws / grpc). For xhttp the `mode` (packet-up / stream-one / ...) MUST
        // match the server; the wrong mode makes the handshake 404. `host` is optional.
        String type = uri.getQueryParameter("type");
        if (!TextUtils.isEmpty(type) && !"tcp".equals(type)) {
            JSONObject transport = new JSONObject();
            transport.put("type", type);
            String path = uri.getQueryParameter("path");
            if (!TextUtils.isEmpty(path)) {
                transport.put("path", decode(path));
            }
            String mode = uri.getQueryParameter("mode");
            if (!TextUtils.isEmpty(mode)) {
                transport.put("mode", mode);
            }
            String host = uri.getQueryParameter("host");
            if (!TextUtils.isEmpty(host)) {
                transport.put("host", host);
            }
            o.put("transport", transport);
        }
        return o;
    }

    // AmneziaVPN vpn:// backup blob: base64url -> Qt qCompress (4-byte big-endian size + zlib)
    // -> JSON { containers:[{ awg:{ last_config:"<json>" } }] }, where last_config.config is a
    // full awg-quick .conf we can reuse. Handles the amnezia-awg / amnezia-awg2 containers.
    private static JSONObject parseVpnUri(String key) throws Exception {
        String b64 = key.substring("vpn://".length()).trim();
        byte[] compressed = Base64.decode(b64, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        byte[] json = qUncompress(compressed);
        return parseAmneziaContainers(new JSONObject(new String(json, StandardCharsets.UTF_8)));
    }

    // AmneziaVPN config JSON: {"containers":[{"awg":{"last_config":"{...,config:<awg-quick>}"}}], ..}
    private static JSONObject parseAmneziaContainers(JSONObject root) throws Exception {
        JSONArray containers = root.getJSONArray("containers");
        String def = root.optString("defaultContainer", null);
        JSONObject awg = null;
        for (int i = 0; i < containers.length(); i++) {
            JSONObject c = containers.getJSONObject(i);
            if (!c.has("awg")) continue;
            if (def != null && def.equals(c.optString("container"))) {
                awg = c.getJSONObject("awg");
                break;
            }
            if (awg == null) {
                awg = c.getJSONObject("awg");
            }
        }
        if (awg == null) {
            throw new IllegalArgumentException("vpn:// key has no AmneziaWG container");
        }

        JSONObject lastConfig = new JSONObject(awg.getString("last_config"));
        JSONObject outbound = parseAmneziaWg(lastConfig.getString("config"));
        // MTU isn't in the .conf text; it's a separate field in last_config.
        if (lastConfig.has("mtu")) {
            try {
                outbound.put("mtu", Integer.parseInt(lastConfig.getString("mtu")));
            } catch (NumberFormatException ignored) {
            }
        }
        return outbound;
    }

    // Qt qUncompress: 4-byte big-endian uncompressed size, then a zlib stream.
    private static byte[] qUncompress(byte[] data) throws Exception {
        if (data.length < 5) {
            throw new IllegalArgumentException("vpn:// payload too short");
        }
        Inflater inflater = new Inflater();
        inflater.setInput(data, 4, data.length - 4);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length * 3));
        byte[] buf = new byte[8192];
        while (!inflater.finished()) {
            int n = inflater.inflate(buf);
            if (n == 0) {
                if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) {
                    break;
                }
            }
            out.write(buf, 0, n);
        }
        inflater.end();
        return out.toByteArray();
    }

    // --- AmneziaVPN QR codes ---
    // Payload = base64url of: magic(0x07 0xC0) + count(1) + index(1) + dataLen(uint32 BE) + data.
    // A large config is split across `count` codes; concatenate `data` by `index`. The reassembled
    // blob is either a Qt qCompress'd Amnezia JSON ([uint32 len][zlib 0x78..]) or raw awg-quick text.

    private static byte[] amneziaQrRaw(String qrText) {
        if (qrText == null || qrText.length() < 12) {
            return null;
        }
        try {
            byte[] raw = b64decode(qrText.trim());
            if (raw.length >= 8 && (raw[0] & 0xff) == 0x07 && (raw[1] & 0xff) == 0xc0) {
                return raw;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** If qrText is an AmneziaVPN chunk, returns {count, index}; otherwise null. */
    public static int[] amneziaQrParts(String qrText) {
        byte[] raw = amneziaQrRaw(qrText);
        if (raw == null) {
            return null;
        }
        return new int[]{raw[2] & 0xff, raw[3] & 0xff};
    }

    /** The data payload of an AmneziaVPN chunk (without the 8-byte header), or null. */
    public static byte[] amneziaQrChunkData(String qrText) {
        byte[] raw = amneziaQrRaw(qrText);
        if (raw == null) {
            return null;
        }
        int len = ((raw[4] & 0xff) << 24) | ((raw[5] & 0xff) << 16) | ((raw[6] & 0xff) << 8) | (raw[7] & 0xff);
        int end = len >= 0 && 8 + len <= raw.length ? 8 + len : raw.length;
        byte[] data = new byte[end - 8];
        System.arraycopy(raw, 8, data, 0, data.length);
        return data;
    }

    /** Turn reassembled chunk data into a key string that {@link #buildOutbound} understands. */
    public static String amneziaQrToKey(byte[] blob) throws Exception {
        // Qt qCompress'd -> decompress to the AmneziaVPN config JSON (buildOutbound routes it by its
        // "containers" field). No base64 round-trip - that mangled the data across alphabets.
        // Otherwise it is a raw awg-quick [Interface] config, used as-is.
        if (blob.length > 8 && (blob[0] & 0xff) == 0x00 && (blob[4] & 0xff) == 0x78) {
            return new String(qUncompress(blob), StandardCharsets.UTF_8);
        }
        return new String(blob, StandardCharsets.UTF_8);
    }

    // AmneziaWG: awg-quick .conf text ([Interface]/[Peer] with Jc/Jmin/Jmax/S1../H1.. keys).
    // Emits a sing-box `wireguard` endpoint-style outbound with the lx-fork awg fields.
    private static JSONObject parseAmneziaWg(String conf) throws Exception {
        JSONObject o = new JSONObject();
        o.put("type", "wireguard");
        o.put("tag", TAG_PROXY);
        o.put("system", false);

        JSONArray localAddress = new JSONArray();
        JSONObject peer = new JSONObject();
        String endpoint = null;

        String section = "";
        for (String line : conf.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (t.startsWith("[") && t.endsWith("]")) {
                section = t.toLowerCase();
                continue;
            }
            int eq = t.indexOf('=');
            if (eq < 0) continue;
            String k = t.substring(0, eq).trim().toLowerCase();
            String v = t.substring(eq + 1).trim();
            if (v.isEmpty()) continue; // e.g. empty I2..I5 = unset

            if (section.contains("interface")) {
                switch (k) {
                    case "privatekey": o.put("private_key", v); break;
                    case "address":
                        for (String a : v.split(",")) localAddress.put(a.trim());
                        break;
                    case "mtu": o.put("mtu", Integer.parseInt(v)); break;
                    case "jc": o.put("jc", Integer.parseInt(v)); break;
                    case "jmin": o.put("jmin", Integer.parseInt(v)); break;
                    case "jmax": o.put("jmax", Integer.parseInt(v)); break;
                    case "s1": o.put("s1", Integer.parseInt(v)); break;
                    case "s2": o.put("s2", Integer.parseInt(v)); break;
                    case "s3": o.put("s3", Integer.parseInt(v)); break;
                    case "s4": o.put("s4", Integer.parseInt(v)); break;
                    case "h1": o.put("h1", v); break;
                    case "h2": o.put("h2", v); break;
                    case "h3": o.put("h3", v); break;
                    case "h4": o.put("h4", v); break;
                    case "i1": o.put("i1", v); break;
                    case "i2": o.put("i2", v); break;
                    case "i3": o.put("i3", v); break;
                    case "i4": o.put("i4", v); break;
                    case "i5": o.put("i5", v); break;
                    default: break;
                }
            } else if (section.contains("peer")) {
                switch (k) {
                    case "publickey": peer.put("public_key", v); break;
                    case "presharedkey": peer.put("pre_shared_key", v); break;
                    case "endpoint": endpoint = v; break;
                    case "persistentkeepalive": peer.put("persistent_keepalive_interval", Integer.parseInt(v)); break;
                    case "allowedips": {
                        JSONArray allowed = new JSONArray();
                        for (String a : v.split(",")) allowed.put(a.trim());
                        peer.put("allowed_ips", allowed);
                        break;
                    }
                    default: break;
                }
            }
        }

        // sing-box wireguard endpoint: interface addresses under "address"; the server endpoint
        // lives on the peer (address/port), NOT at the outbound root.
        if (endpoint != null) {
            int c = endpoint.lastIndexOf(':');
            peer.put("address", endpoint.substring(0, c));
            peer.put("port", Integer.parseInt(endpoint.substring(c + 1)));
        }
        if (!peer.has("allowed_ips")) {
            peer.put("allowed_ips", new JSONArray().put("0.0.0.0/0").put("::/0"));
        }
        // AmneziaWG uses MTU 1280 by default. The awg-quick text (e.g. from an Amnezia "for other
        // apps" QR) usually omits it; without it sing-box uses a larger MTU and traffic stalls
        // ("Connecting" forever). vpn:// keys carry the real MTU in last_config and override this.
        if (!o.has("mtu")) {
            o.put("mtu", 1280);
        }
        o.put("address", localAddress);
        o.put("peers", new JSONArray().put(peer));
        return o;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }
}
