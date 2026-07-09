package org.telegram.messenger.vpn;

import android.text.TextUtils;

/** One saved VPN key (mirrors SharedConfig.ProxyInfo for the VPN list). */
public class VpnKeyInfo {

    public String name;
    public String key;   // raw URI: vpn:// / vless:// / hysteria2:// / awg .conf

    // runtime status (not persisted)
    public boolean checking;
    public boolean available;
    public long ping;
    public long availableCheckTime;

    public VpnKeyInfo(String name, String key) {
        this.name = name;
        this.key = key;
    }

    /** Human-readable protocol label derived from the key. */
    public String getType() {
        if (key == null) {
            return "VPN";
        }
        String trimmed = key.trim();
        if (trimmed.startsWith("{")) {
            // stored as an edited sing-box outbound JSON
            try {
                String t = new org.json.JSONObject(trimmed).optString("type");
                if ("wireguard".equals(t)) return "AmneziaWG";
                if ("vless".equals(t)) return "VLESS";
                if ("hysteria2".equals(t)) return "Hysteria2";
                if ("shadowsocks".equals(t)) return "Shadowsocks";
            } catch (Exception ignored) {
            }
            return "VPN";
        }
        String low = trimmed.toLowerCase();
        if (low.startsWith("vpn://") || low.startsWith("awg://") || low.startsWith("wg://")
                || low.contains("[interface]")) {
            return "AmneziaWG";
        }
        if (low.startsWith("vless://")) {
            return "VLESS";
        }
        if (low.startsWith("hysteria2://") || low.startsWith("hy2://")) {
            return "Hysteria2";
        }
        return "VPN";
    }

    public String getDisplayName() {
        if (!TextUtils.isEmpty(name)) {
            return name;
        }
        return getType();
    }
}
