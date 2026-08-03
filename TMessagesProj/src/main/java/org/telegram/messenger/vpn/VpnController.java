package org.telegram.messenger.vpn;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.tgnet.ConnectionsManager;

import java.util.ArrayList;

/**
 * Owns the saved VPN keys, the "use VPN" / "auto-switch" flags and timeout, and the selection, and
 * drives {@link SingBoxManager}. VPN analogue of the proxy handling in SharedConfig +
 * ConnectionsManager. Screens observe it via {@link Listener} (no NotificationCenter).
 */
public class VpnController implements SingBoxManager.StateListener {

    private static final String PREFS = "vpnconfig";

    /**
     * Auto-switch delay options (seconds), i.e. how often the current connection is re-pinged.
     *
     * These used to start at 5s. Every check tears the proxy connection down and re-dials it, which
     * makes sing-box open a fresh outbound to the VPN server (a full TLS/QUIC handshake), so a short
     * interval was expensive in two ways: the cellular radio never got to leave its high-power state
     * (RRC inactivity timers run 10-20s, so anything under that pins the radio up permanently), and
     * every re-dial reset tgnet's background sleep timer - see {@link #runHealthCheck()}. The floor is
     * now 30s and the default 2 minutes.
     */
    public static final int[] AUTOSWITCH_TIMEOUTS = {30, 60, 120, 300};
    private static final int DEFAULT_AUTOSWITCH_TIMEOUT_INDEX = 2; // 2 minutes

    /**
     * Minimum spacing between health checks while the UI is not in front. The user-chosen interval
     * only applies while they are actually looking at the app; in the background a dead server just
     * needs to be noticed eventually (so notifications keep arriving), not within seconds.
     */
    private static final long BACKGROUND_MIN_INTERVAL_MS = 5 * 60 * 1000L;

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
    private boolean routeCalls;
    private int autoSwitchTimeoutIndex = DEFAULT_AUTOSWITCH_TIMEOUT_INDEX;
    /** elapsedRealtime of the last ping actually sent, so the background floor survives deep sleep. */
    private long lastHealthCheckElapsed;
    private boolean loaded;
    /**
     * Consecutive failed health-check pings for {@link #currentVpn}. Auto-switch only fires once this
     * reaches {@link #PING_FAILURES_BEFORE_SWITCH} - a single failure used to roll the server over, and
     * because every switch restarts the core, the replacement's first ping then failed too (it had not
     * handshaked yet) and switched again, so one hiccup cost several restarts. Any successful ping
     * clears it; it also resets whenever we move to a different server or (re)connect.
     */
    private int consecutivePingFailures;
    private static final int PING_FAILURES_BEFORE_SWITCH = 3;

    private final ArrayList<Listener> listeners = new ArrayList<>();
    private final Runnable autoSwitchRunnable = this::switchToNext;
    private final Runnable healthCheckRunnable = this::runHealthCheck;

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
        routeCalls = p.getBoolean("route_calls", false);
        // New pref key: the old indices addressed a 5s..60s ladder that no longer exists, and every
        // value on it was hard on the battery, so an old setting is dropped rather than remapped onto
        // whatever index happens to line up.
        autoSwitchTimeoutIndex = p.getInt("autoswitch_timeout_v2", DEFAULT_AUTOSWITCH_TIMEOUT_INDEX);
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
        updateBackgroundKeepAlive();
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
                .putBoolean("route_calls", routeCalls)
                .putInt("autoswitch_timeout_v2", autoSwitchTimeoutIndex)
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
        if (value) {
            scheduleHealthCheck();
        } else {
            AndroidUtilities.cancelRunOnUIThread(healthCheckRunnable);
        }
    }

    public boolean isRouteCalls() {
        return routeCalls;
    }

    public void setRouteCalls(boolean value) {
        routeCalls = value;
        save();
    }

    /**
     * Whether an active call's media should be sent through the embedded proxy right now. Requires the
     * VPN to be on, the user to have opted in, and the core to be actually connected with a live SOCKS
     * port - otherwise the call must go direct.
     */
    public boolean shouldRouteCalls() {
        return isEnabled() && routeCalls
                && SingBoxManager.getInstance().getState() == SingBoxManager.STATE_CONNECTED
                && SingBoxManager.getInstance().getLocalPort() > 0;
    }

    /** Local SOCKS5 port the sing-box core is listening on (0 if not connected). */
    public int getProxyPort() {
        return SingBoxManager.getInstance().getLocalPort();
    }

    /**
     * Whether in-app WebView traffic (the internal browser and every other in-process WebView) should
     * currently go through the embedded proxy. True whenever the VPN is on and the core is actually
     * connected with a live local port - the WebView proxy override is driven off this, and
     * {@link org.telegram.messenger.browser.Browser} uses it to force the in-app browser over Custom
     * Tabs so the traffic stays inside the tunnel.
     */
    public boolean shouldRouteBrowser() {
        return isEnabled()
                && SingBoxManager.getInstance().getState() == SingBoxManager.STATE_CONNECTED
                && SingBoxManager.getInstance().getLocalPort() > 0;
    }

    public int getAutoSwitchTimeoutIndex() {
        return autoSwitchTimeoutIndex;
    }

    public void setAutoSwitchTimeoutIndex(int index) {
        autoSwitchTimeoutIndex = index;
        save();
        scheduleHealthCheck();
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
        updateBackgroundKeepAlive();
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
            updateBackgroundKeepAlive();
        }
    }

    public void selectVpn(VpnKeyInfo info) {
        currentVpn = info;
        consecutivePingFailures = 0; // user picked a different server - clean slate
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
        updateBackgroundKeepAlive();
        notifyList();
    }

    private void reconnect() {
        AndroidUtilities.cancelRunOnUIThread(autoSwitchRunnable);
        if (currentVpn != null && !TextUtils.isEmpty(currentVpn.key)) {
            SingBoxManager.getInstance().connect(currentVpn.key);
        }
    }

    /**
     * Keep the app alive in the background while the VPN is on so the in-process proxy survives and
     * tgnet holds its push connection open - without this the fork gets no notifications when the UI
     * is closed (it can't use FCM). Mirrors the state of {@link #isEnabled()}.
     */
    private void updateBackgroundKeepAlive() {
        boolean on = isEnabled();
        setPushConnection(on);
        if (on) {
            VpnForegroundService.start();
        } else {
            VpnForegroundService.stop();
        }
    }

    /**
     * Turn tgnet's persistent push connection on/off. The global pref is what {@code init()} reads on
     * a cold start, so persist it there; the per-account call takes effect immediately.
     */
    private void setPushConnection(boolean on) {
        try {
            MessagesController.getGlobalNotificationsSettings().edit().putBoolean("pushConnection", on).apply();
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    ConnectionsManager.getInstance(a).setPushConnectionEnabled(on);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // ---- SingBoxManager state -> ping / auto-switch / UI ----

    @Override
    public void onSingBoxState(int state, String message) {
        AndroidUtilities.runOnUIThread(() -> {
            if (state == SingBoxManager.STATE_CONNECTED) {
                AndroidUtilities.cancelRunOnUIThread(autoSwitchRunnable);
                consecutivePingFailures = 0; // fresh core session - don't carry failures across it
                measurePing();
                // Keep re-checking reachability on the chosen interval so a server that dies mid-session
                // (not just at connect) triggers auto-switch.
                scheduleHealthCheck();
                // Route all in-process WebViews (the in-app browser included) through the proxy too.
                WebViewProxyController.apply(SingBoxManager.getInstance().getLocalPort());
            } else {
                AndroidUtilities.cancelRunOnUIThread(healthCheckRunnable);
                WebViewProxyController.clear();
                if (state == SingBoxManager.STATE_ERROR && enabled && autoSwitch && vpnList.size() > 1) {
                    AndroidUtilities.cancelRunOnUIThread(autoSwitchRunnable);
                    AndroidUtilities.runOnUIThread(autoSwitchRunnable, 1000L);
                }
            }
            for (Listener l : new ArrayList<>(listeners)) {
                l.onVpnStateChanged(state, message);
            }
        });
    }

    private void measurePing() {
        measurePing(false);
    }

    /**
     * Ping the current connection through the local proxy to show its latency/availability. When
     * {@code allowAutoSwitch} is set (periodic health check only) a failure rolls over to the next
     * connection.
     *
     * We deliberately do NOT auto-switch on the connect-time ping: the core reports "connected" the
     * instant it starts, before the server has finished its handshake, so an immediate ping usually
     * fails - and since every switch tears the proxy down and starts a new one, switching on it would
     * tight-loop through every connection without any of them getting a chance to come up (exactly the
     * runaway the user hit). Only the periodic health check switches, and it fires a full interval
     * after connecting, giving each server time to become reachable first.
     */
    private void measurePing(boolean allowAutoSwitch) {
        final VpnKeyInfo info = currentVpn;
        int port = SingBoxManager.getInstance().getLocalPort();
        if (info == null || port <= 0) {
            return;
        }
        info.checking = true;
        lastHealthCheckElapsed = SystemClock.elapsedRealtime();
        int account = UserConfig.selectedAccount;
        ConnectionsManager.getInstance(account).checkProxy("127.0.0.1", port, "", "", "", time -> AndroidUtilities.runOnUIThread(() -> {
            info.checking = false;
            if (BuildVars.LOGS_ENABLED) {
                // Why the health check decided what it did. Without this the only symptom is the core
                // silently restarting every N seconds, which is impossible to attribute from a log.
                FileLog.d("VpnController: ping " + info.getType() + " '" + info.name + "' -> "
                        + (time == -1 ? "FAILED" : time + "ms")
                        + " (healthCheck=" + allowAutoSwitch + ", autoSwitch=" + autoSwitch
                        + ", state=" + SingBoxManager.getInstance().getState()
                        + ", servers=" + vpnList.size() + ", fg=" + isInForeground() + ")");
            }
            if (time == -1) {
                info.available = false;
                info.ping = 0;
                // Only the periodic health check switches, and only if we're still connected to the same
                // server it just tested (guards against switching during a transition). The connect-time
                // ping is expected to fail before the handshake completes, so it must not count.
                if (allowAutoSwitch && enabled && autoSwitch && info == currentVpn && vpnList.size() > 1
                        && SingBoxManager.getInstance().getState() == SingBoxManager.STATE_CONNECTED) {
                    consecutivePingFailures++;
                    if (consecutivePingFailures >= PING_FAILURES_BEFORE_SWITCH) {
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("VpnController: " + consecutivePingFailures
                                    + " consecutive health checks failed - auto-switching to next server");
                        }
                        switchToNext();
                    } else if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("VpnController: health check failed (" + consecutivePingFailures + "/"
                                + PING_FAILURES_BEFORE_SWITCH + ") - staying on this server for now");
                    }
                }
            } else {
                info.available = true;
                info.ping = time;
                consecutivePingFailures = 0;
            }
            notifyList();
        }));
    }

    /**
     * Schedule the next periodic reachability check. The auto-switch timeout slider doubles as the
     * ping-check interval: every N seconds we re-measure the current connection and, on failure, roll
     * over to the next one. No-op unless the VPN is on, auto-switch is enabled, and there's somewhere
     * to switch to. Whether a given tick actually pings is decided in {@link #runHealthCheck()} - in
     * the background most ticks are skipped.
     */
    private void scheduleHealthCheck() {
        AndroidUtilities.cancelRunOnUIThread(healthCheckRunnable);
        if (!enabled || !autoSwitch || vpnList.size() < 2) {
            return;
        }
        int seconds = AUTOSWITCH_TIMEOUTS[Math.max(0, Math.min(autoSwitchTimeoutIndex, AUTOSWITCH_TIMEOUTS.length - 1))];
        AndroidUtilities.runOnUIThread(healthCheckRunnable, seconds * 1000L);
    }

    /**
     * The user is looking at the app. Only then is the fast, user-chosen interval worth its cost.
     * Screen off, or the UI in the background, both count as not-in-front.
     */
    private static boolean isInForeground() {
        return ApplicationLoader.isScreenOn && !ApplicationLoader.mainInterfacePaused;
    }

    /**
     * The timer keeps ticking at the user's interval, but in the background we let most ticks pass
     * without pinging. That matters more than it looks: a ping is not a UDP echo, it is
     * {@code ConnectionsManager.checkProxy}, which suspends the proxy connection and re-dials it, so
     * sing-box opens a brand new outbound to the VPN server every time. Two consequences:
     *
     *   1. Radio. Cellular RRC inactivity timers run 10-20s. Dialling more often than that never lets
     *      the radio demote out of its high-power state, which is most of the reported drain.
     *   2. tgnet never sleeps. When the app is backgrounded tgnet suspends every datacenter connection
     *      and parks its event loop for ~3 minutes between push pings. But a proxy connection coming
     *      up resets that sleep timer (ConnectionsManager.cpp, onConnectionConnected -> lastPauseTime),
     *      and the next pass through select() then takes the "resume network and timers" branch: the
     *      poll timeout drops back to 1s, the generic ping restarts on its 19s cycle, and the request
     *      queue is walked every second. The sleep timer is 10s (CONNECTION_BACKGROUND_KEEP_TIME), so
     *      the old 10s default meant it could essentially never stay asleep.
     *
     * Leaving the timer running (rather than cancelling it while backgrounded) means no foreground
     * hook is needed anywhere: the first tick after the user comes back sees the app in front and goes
     * straight back to the fast cadence. The tick itself is a main-looper post - no wakelock, no alarm,
     * and it does not fire at all while the device is in deep sleep.
     */
    private void runHealthCheck() {
        if (!enabled || currentVpn == null
                || SingBoxManager.getInstance().getState() != SingBoxManager.STATE_CONNECTED) {
            return;
        }
        // elapsedRealtime, not uptimeMillis: time spent in deep sleep must count towards the floor,
        // otherwise a phone that was asleep for an hour pings the moment it wakes.
        boolean due = isInForeground()
                || SystemClock.elapsedRealtime() - lastHealthCheckElapsed >= BACKGROUND_MIN_INTERVAL_MS;
        if (due) {
            // measurePing(true) switches to the next connection if this one has stopped responding.
            measurePing(true);
        }
        scheduleHealthCheck();
    }

    private void switchToNext() {
        if (vpnList.size() < 2 || currentVpn == null || !enabled) {
            return;
        }
        if (isCallActive()) {
            // Never roll over to another server mid-call. Auto-switch reconnects the core, which tears
            // down the proxy the call's media is flowing through, so the call hangs in "connecting"
            // forever. The periodic health check keeps running, so if this server is genuinely dead the
            // switch just happens once the call has ended.
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("VpnController: auto-switch suppressed - call in progress");
            }
            return;
        }
        int idx = vpnList.indexOf(currentVpn);
        int next = (idx + 1) % vpnList.size();
        if (next == idx) {
            return;
        }
        currentVpn = vpnList.get(next);
        consecutivePingFailures = 0; // the new server gets a clean slate
        save();
        notifyList();
        reconnect();
    }

    /**
     * True while a voice/video call is being set up or is ongoing. Auto-switch is suppressed during
     * this window because a server rollover reconnects the core and breaks the call's media path -
     * see {@link #switchToNext()}. Non-null for the whole call lifetime (connecting -> established ->
     * ending), back to null once the call ends, so the deferred switch can resume.
     */
    private boolean isCallActive() {
        return VoIPService.getSharedInstance() != null;
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
