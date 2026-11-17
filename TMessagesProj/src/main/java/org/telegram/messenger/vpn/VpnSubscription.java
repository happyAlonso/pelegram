package org.telegram.messenger.vpn;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * A subscription link: an https URL that serves a list of share links (vless:// and the like),
 * base64 encoded or plain. It is one entry in {@link VpnController#vpnList} (a VpnKeyInfo whose
 * {@link VpnKeyInfo#sub} points here) and runs as one sing-box urltest group over all its servers:
 * the core tests every server every {@link #getIntervalSec()} seconds and sends new connections
 * through the fastest (the home_rtr subscription block does the same with xray). To ping and
 * auto-switch the whole subscription is one server; it fails over to the next entry only when the
 * group as a whole stops answering.
 */
public class VpnSubscription {

    /** Server check intervals for the slider, seconds. No 30 s: each round is one TLS handshake per server. */
    public static final int[] CHECK_INTERVALS_SEC = {60, 180, 300, 600, 1800};
    public static final int DEFAULT_INTERVAL_INDEX = 2; // 5 minutes
    /** Nothing excluded until the user says so. */
    public static final String DEFAULT_EXCLUDE = "";

    /** Stable id that the list entry uses to refer to this subscription in the saved prefs. */
    public final String id;
    /** The link. It works as a password (anyone who has it gets the keys): never log its path. */
    public String url;
    /** Name the user typed, empty for none. */
    public String name;
    /** The server's profile-title header, decoded, or empty. */
    public String title;
    /** Wall clock of the last successful update, 0 if never. */
    public long lastUpdateWallMs;
    /** Whether the last successful update went through the tunnel. */
    public boolean lastUpdateViaTunnel;
    /** The servers from the last successful update, in the subscription's order (name + key). */
    public List<VpnKeyInfo> servers = new ArrayList<>();
    /** Comma-separated parts of server names to leave out of the group. */
    public String exclude = DEFAULT_EXCLUDE;
    public int intervalIndex = DEFAULT_INTERVAL_INDEX;
    /**
     * Name of the server the user picked in the submenu, null for the fastest. With auto-switch on
     * VpnController drops it at the next health check; with auto-switch off it stays, restarts too.
     */
    public String pinnedName;

    // runtime state, not persisted
    /** Wall clock of the last attempt that actually went out, for the retry backoff. */
    public long lastAttemptWallMs;
    /** Failed automatic updates in a row since the last success; picks the retry delay. */
    public int failures;
    /** Short error of the last failed update, null after a success. */
    public String lastError;
    /** The same error in full (exception chain on one line), for the screen that shows it. */
    public String lastErrorDetail;
    public boolean fetching;
    /** elapsedRealtime of the pick, 0 if none in this process: a pick from before a restart is dropped at the first check. */
    public long pinnedAtElapsed;
    /** Restarts spent on servers whose host had no address at start; see VpnController.MAX_UNRESOLVED_RESTARTS. */
    public int unresolvedRestarts;
    /** Bumped on delete, so a fetch still in flight cannot bring the subscription back. */
    public int generation;
    /** What the running core reports about this group, null while it does not run. */
    public GroupStatus groupStatus;

    /** The core's view of the group: which server carries new connections and each server's last delay. */
    public static final class GroupStatus {
        /** Name of the server new connections go through, null if the core named a tag we do not know. */
        public String selectedName;
        /** The fastest server by the last test (what the group uses when nothing is pinned). */
        public String fastestName;
        /** Whether the selector is on a server the user picked rather than on the fastest. */
        public boolean pinned;
        /** Server name -> last urltest delay in ms. A server whose test failed has no entry. */
        public final HashMap<String, Integer> delays = new HashMap<>();
        /** Whether any test finished yet. Before that the core just uses the first server. */
        public boolean tested;
        /** Names of the servers in the running group; an included server missing here had no address at start. */
        public final java.util.HashSet<String> members = new java.util.HashSet<>();
    }

    public VpnSubscription(String id, String url) {
        this.id = id;
        this.url = url;
        this.name = "";
        this.title = "";
    }

    public String getDisplayName() {
        if (!TextUtils.isEmpty(name)) {
            return name;
        }
        if (!TextUtils.isEmpty(title)) {
            return title;
        }
        return "Subscription";
    }

    public int getIntervalSec() {
        return CHECK_INTERVALS_SEC[Math.max(0, Math.min(intervalIndex, CHECK_INTERVALS_SEC.length - 1))];
    }

    private List<String> excludeParts() {
        List<String> parts = new ArrayList<>();
        if (exclude != null) {
            for (String p : exclude.split(",")) {
                String t = p.trim();
                if (!t.isEmpty()) {
                    parts.add(t.toLowerCase(Locale.ROOT));
                }
            }
        }
        return parts;
    }

    public boolean isExcluded(VpnKeyInfo server) {
        String n = server.name == null ? "" : server.name.toLowerCase(Locale.ROOT);
        for (String p : excludeParts()) {
            if (n.contains(p)) {
                return true;
            }
        }
        return false;
    }

    /** The servers the group runs: all but the excluded ones, in subscription order. */
    public List<VpnKeyInfo> includedServers() {
        List<VpnKeyInfo> out = new ArrayList<>();
        for (VpnKeyInfo s : servers) {
            if (!isExcluded(s)) {
                out.add(s);
            }
        }
        return out;
    }

    /** See {@link VpnSubscriptionParser#identity}. */
    public String identity() {
        List<String> keys = new ArrayList<>();
        for (VpnKeyInfo s : servers) {
            keys.add(s.key);
        }
        return VpnSubscriptionParser.identity(keys);
    }

    JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("url", url);
        o.put("name", name == null ? "" : name);
        o.put("title", title == null ? "" : title);
        o.put("last_update", lastUpdateWallMs);
        o.put("exclude", exclude == null ? "" : exclude);
        o.put("interval", intervalIndex);
        o.put("via_tunnel", lastUpdateViaTunnel);
        if (pinnedName != null) {
            o.put("pinned", pinnedName);
        }
        JSONArray arr = new JSONArray();
        for (VpnKeyInfo s : servers) {
            JSONObject so = new JSONObject();
            so.put("name", s.name == null ? "" : s.name);
            so.put("key", s.key);
            arr.put(so);
        }
        o.put("servers", arr);
        return o;
    }

    static VpnSubscription fromJson(JSONObject o) {
        String id = o.optString("id", "");
        String url = o.optString("url", "");
        if (id.isEmpty() || url.isEmpty()) {
            return null;
        }
        VpnSubscription s = new VpnSubscription(id, url);
        s.name = o.optString("name", "");
        s.title = o.optString("title", "");
        s.lastUpdateWallMs = o.optLong("last_update", 0);
        s.exclude = o.has("exclude") ? o.optString("exclude", "") : DEFAULT_EXCLUDE;
        s.intervalIndex = o.optInt("interval", DEFAULT_INTERVAL_INDEX);
        s.lastUpdateViaTunnel = o.optBoolean("via_tunnel", false);
        s.pinnedName = o.has("pinned") ? o.optString("pinned", null) : null;
        JSONArray arr = o.optJSONArray("servers");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject so = arr.optJSONObject(i);
                if (so != null && !so.optString("key", "").isEmpty()) {
                    s.servers.add(new VpnKeyInfo(so.optString("name", ""), so.optString("key")));
                }
            }
        }
        return s;
    }
}
