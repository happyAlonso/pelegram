package org.telegram.messenger.vpn;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;

import java.util.ArrayList;

/**
 * Owns the saved VPN keys, the "use VPN" / "auto-switch" flags and timeout, and the selection, and
 * drives {@link SingBoxManager}. VPN analogue of the proxy handling in SharedConfig +
 * ConnectionsManager. Screens observe it via {@link Listener} (no NotificationCenter).
 */
public class VpnController implements SingBoxManager.StateListener {

    private static final String PREFS = "vpnconfig";

    /** Auto-switch delay options (seconds), mirrors the proxy rotation timeouts. */
    public static final int[] AUTOSWITCH_TIMEOUTS = {5, 10, 15, 30, 60};

    public interface Listener {
        void onVpnListChanged();
        void onVpnStateChanged(int state, String message);
    }

    private static VpnController instance;

    public static VpnController getInstance() {
        if (instance == null) {
            synchronized (VpnController.class) {
                if (instance == null) {
                    instance = new VpnController();
                }
            }
        }
        return instance;
    }

    public final ArrayList<VpnKeyInfo> vpnList = new ArrayList<>();
    public VpnKeyInfo currentVpn;
    private boolean enabled;
    private boolean autoSwitch;
    private int autoSwitchTimeoutIndex = 1; // default 10s
    private boolean loaded;

    private final ArrayList<Listener> listeners = new ArrayList<>();
    private final Runnable autoSwitchRunnable = this::switchToNext;

    private VpnController() {
        SingBoxManager.getInstance().addListener(this);
    }

    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        vpnList.clear();
        SharedPreferences p = prefs();
        enabled = p.getBoolean("enabled", false);
        autoSwitch = p.getBoolean("autoswitch", false);
        autoSwitchTimeoutIndex = p.getInt("autoswitch_timeout", 1);
        try {
            JSONArray arr = new JSONArray(p.getString("list", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                vpnList.add(new VpnKeyInfo(o.optString("name"), o.optString("key")));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        int cur = p.getInt("current", -1);
        if (cur >= 0 && cur < vpnList.size()) {
            currentVpn = vpnList.get(cur);
        } else if (!vpnList.isEmpty()) {
            currentVpn = vpnList.get(0);
        }
        // The core doesn't survive a process restart, so bring the tunnel back up if it was on.
        if (enabled && currentVpn != null && !TextUtils.isEmpty(currentVpn.key)
                && SingBoxManager.getInstance().getState() == SingBoxManager.STATE_IDLE) {
            reconnect();
        }
    }

    public void save() {
        JSONArray arr = new JSONArray();
        for (VpnKeyInfo info : vpnList) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", info.name == null ? "" : info.name);
                o.put("key", info.key);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        prefs().edit()
                .putString("list", arr.toString())
                .putBoolean("enabled", enabled)
                .putBoolean("autoswitch", autoSwitch)
                .putInt("autoswitch_timeout", autoSwitchTimeoutIndex)
                .putInt("current", currentVpn == null ? -1 : vpnList.indexOf(currentVpn))
                .apply();
    }

    public boolean isEnabled() {
        return enabled && currentVpn != null;
    }

    public boolean isAutoSwitch() {
        return autoSwitch;
    }

    public void setAutoSwitch(boolean value) {
        autoSwitch = value;
        save();
    }

    public int getAutoSwitchTimeoutIndex() {
        return autoSwitchTimeoutIndex;
    }

    public void setAutoSwitchTimeoutIndex(int index) {
        autoSwitchTimeoutIndex = index;
        save();
    }

    public void addVpn(VpnKeyInfo info) {
        vpnList.add(0, info);
        if (currentVpn == null) {
            currentVpn = info;
        }
        save();
        notifyList();
        if (isEnabled()) {
            reconnect();
        }
    }

    /** After editing an existing key's fields. */
    public void updateVpn(VpnKeyInfo info) {
        save();
        notifyList();
        if (info == currentVpn && enabled) {
            reconnect();
        }
    }

    public void deleteVpn(VpnKeyInfo info) {
        boolean wasCurrent = info == currentVpn;
        vpnList.remove(info);
        if (wasCurrent) {
            currentVpn = vpnList.isEmpty() ? null : vpnList.get(0);
        }
        save();
        notifyList();
        if (wasCurrent) {
            if (isEnabled()) {
                reconnect();
            } else {
                SingBoxManager.getInstance().disconnect();
            }
        }
    }

    public void selectVpn(VpnKeyInfo info) {
        currentVpn = info;
        save();
        notifyList();
        if (enabled) {
            reconnect();
        }
    }

    public void setEnabled(boolean value) {
        enabled = value;
        save();
        if (enabled && currentVpn != null) {
            reconnect();
        } else {
            SingBoxManager.getInstance().disconnect();
        }
        notifyList();
    }

    private void reconnect() {
        AndroidUtilities.cancelRunOnUIThread(autoSwitchRunnable);
        if (currentVpn != null && !TextUtils.isEmpty(currentVpn.key)) {
            SingBoxManager.getInstance().connect(currentVpn.key);
        }
    }

    // ---- SingBoxManager state -> ping / auto-switch / UI ----

    @Override
    public void onSingBoxState(int state, String message) {
        AndroidUtilities.runOnUIThread(() -> {
            if (state == SingBoxManager.STATE_CONNECTED) {
                AndroidUtilities.cancelRunOnUIThread(autoSwitchRunnable);
                measurePing();
            } else if (state == SingBoxManager.STATE_ERROR && enabled && autoSwitch && vpnList.size() > 1) {
                AndroidUtilities.cancelRunOnUIThread(autoSwitchRunnable);
                int seconds = AUTOSWITCH_TIMEOUTS[Math.max(0, Math.min(autoSwitchTimeoutIndex, AUTOSWITCH_TIMEOUTS.length - 1))];
                AndroidUtilities.runOnUIThread(autoSwitchRunnable, seconds * 1000L);
            }
            for (Listener l : new ArrayList<>(listeners)) {
                l.onVpnStateChanged(state, message);
            }
        });
    }

    private void measurePing() {
        final VpnKeyInfo info = currentVpn;
        int port = SingBoxManager.getInstance().getLocalPort();
        if (info == null || port <= 0) {
            return;
        }
        info.checking = true;
        int account = UserConfig.selectedAccount;
        ConnectionsManager.getInstance(account).checkProxy("127.0.0.1", port, "", "", "", time -> AndroidUtilities.runOnUIThread(() -> {
            info.checking = false;
            if (time == -1) {
                info.available = false;
                info.ping = 0;
            } else {
                info.available = true;
                info.ping = time;
            }
            notifyList();
        }));
    }

    private void switchToNext() {
        if (vpnList.size() < 2 || currentVpn == null || !enabled) {
            return;
        }
        int idx = vpnList.indexOf(currentVpn);
        int next = (idx + 1) % vpnList.size();
        if (next == idx) {
            return;
        }
        currentVpn = vpnList.get(next);
        save();
        notifyList();
        reconnect();
    }

    public int getState() {
        return SingBoxManager.getInstance().getState();
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyList() {
        for (Listener l : new ArrayList<>(listeners)) {
            l.onVpnListChanged();
        }
    }
}
