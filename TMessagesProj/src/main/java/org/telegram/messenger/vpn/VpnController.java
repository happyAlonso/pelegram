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
import org.telegram.utils.proxy.ProxySettings;

import java.util.ArrayList;

/**
 * Owns the saved VPN keys, the "use VPN" / "auto-switch" flags and timeout, and the selection, and
 * drives {@link SingBoxManager}. VPN analogue of the proxy handling in SharedConfig +
 * ConnectionsManager. Screens observe it via {@link Listener} (no NotificationCenter).
 */
public class VpnController implements SingBoxManager.StateListener {

    private static final String PREFS = "vpnconfig";

    /**
     * Auto-switch delay options (seconds): how often the current connection is re-pinged *while the
     * user has the app open*. Backgrounded, the interval does not apply at all - see
     * {@link #runHealthCheck()}.
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
     * How often the background tick runs, regardless of the user's (foreground) interval. The tick
     * itself is nearly free - normally it just observes that tgnet has received data through the
     * tunnel and returns without pinging, see {@link #runHealthCheck()} - so it can afford to be
     * frequent. It is a plain main-looper post: no wakelock, no alarm, and it does not fire at all
     * while the device is in deep sleep.
     */
    private static final long BACKGROUND_TICK_MS = 60 * 1000L;

    /**
     * How many consecutive background ticks must pass with no inbound tgnet traffic at all before we
     * spend a real ping on finding out why. Two ticks means ~2 minutes of the device being *awake*
     * with a completely silent tunnel; a healthy backgrounded client is never that quiet for that
     * long, because its push connection is answered every few minutes. Ticks do not advance in deep
     * sleep, which is what keeps a long doze from looking like silence.
     */
    private static final int BACKGROUND_SILENT_TICKS_BEFORE_PING = 2;

    /**
     * Minimum spacing between background pings, indexed by how many we have already spent without
     * traffic coming back. The first entries are short so a genuinely dead server is confirmed and
     * switched away from in a couple of minutes; the tail backs off so the pathological case - every
     * server equally unreachable, nothing better to switch to - settles into a 5 minute poll instead
     * of pinging on every tick.
     */
    private static final long[] BACKGROUND_PING_INTERVALS_MS = {60 * 1000L, 60 * 1000L, 120 * 1000L, 300 * 1000L};

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
    /** elapsedRealtime of the last ping actually sent, so background ping spacing survives deep sleep. */
    private long lastHealthCheckElapsed;
    private boolean loaded;
    /**
     * Consecutive failed health-check pings for {@link #currentVpn}, foreground and background alike.
     * Auto-switch only fires once this
     * reaches {@link #PING_FAILURES_BEFORE_SWITCH} - a single failure used to roll the server over, and
     * because every switch restarts the core, the replacement's first ping then failed too (it had not
     * handshaked yet) and switched again, so one hiccup cost several restarts. Any successful ping
     * clears it; it also resets whenever we move to a different server or (re)connect, and whenever a
     * health-check tick sees tgnet traffic. Without that last reset the count was not consecutive at
     * all: a 7 October log switched away at 07:02 on failures from 03:08, 06:51 and 07:02, although
     * the tunnel had carried traffic for the four hours between the first two.
     */
    private int consecutivePingFailures;
    private static final int PING_FAILURES_BEFORE_SWITCH = 3;

    /**
     * How long a server that auto-switch gave up on stays out of the rotation. {@link #switchToNext()}
     * used to take the next index blindly, so a list holding dead keys walked into each of them in
     * turn: in a 7 October log three of seven servers never answered, and one night the app spent 46
     * minutes on two of them while a working server sat further down the list. A server that failed
     * within this window is skipped unless every other server has failed within it too.
     */
    private static final long FAILED_SERVER_SKIP_MS = 30 * 60 * 1000L;

    /**
     * Trial pings for a server auto-switch has just moved to, each delay counted from the previous
     * result. In the background the regular health check needs two silent ticks and then three failed
     * pings spaced by the outage backoff before it gives up, about 22 minutes per dead server in that
     * log. A switch is already evidence that something is wrong, so the new server gets a short trial
     * instead: if all of these fail (about a minute in all) we move on, and the first answer, the
     * connect-time ping included, ends the trial.
     */
    private static final long[] TRIAL_PING_DELAYS_MS = {5 * 1000L, 10 * 1000L, 15 * 1000L};
    /** Next index into {@link #TRIAL_PING_DELAYS_MS} while a trial runs, -1 otherwise. */
    private int trialStep = -1;
    /** Bumped whenever a trial starts or stops, so a late ping result cannot act on a newer trial. */
    private int trialGeneration;
    /** Set by {@link #switchToNext()} when the server it picked should get a trial once the core is up. */
    private boolean trialAfterConnect;
    /**
     * elapsedRealtime of the last proof that the tunnel worked: a successful ping, or tgnet traffic seen
     * by a health-check tick. A switch runs a trial only if this is recent. When nothing has worked for
     * {@link #FAILED_SERVER_SKIP_MS} - every server unreachable, a long outage - switches fall back to
     * the slow backoff instead of cycling the whole list once a minute.
     */
    private long lastTunnelWorkedElapsed;

    /**
     * Wait this long after a connectivity change before re-checking. Android sends CONNECTIVITY_ACTION
     * more than once for one switch (a 10 October log has pairs 15-30 s apart), so the re-check runs
     * once things have settled, and once per burst.
     */
    private static final long NETWORK_SETTLE_MS = 5 * 1000L;
    /**
     * A network change re-checks the current server every time, but it clears the failure marks and
     * opens a full quick lap (trials on every fresh server, see {@link #switchToNext()}) at most this
     * often. The phone in that log changed networks nine times in one hour of outage while moving
     * about. Clearing the marks on every change made the replay of that hour restart the core 29 times
     * and spend 122 pings, against 10 and 41 without the network hook.
     */
    private static final long NETWORK_LAP_INTERVAL_MS = 15 * 60 * 1000L;
    /** How long after a network change its quick lap may run. Seven servers take about six minutes. */
    private static final long NETWORK_LAP_WINDOW_MS = 10 * 60 * 1000L;
    private long lastNetworkLapElapsed;
    private long networkLapUntilElapsed;
    /** The running trial re-checks the current server after a network change, not after a switch. */
    private boolean trialAfterNetworkChange;

    private static final int PING_CONNECT = 0; // right after the core connects, never switches
    private static final int PING_HEALTH = 1;  // health-check tick, counts towards a switch
    private static final int PING_TRIAL = 2;   // trial of a server auto-switch just moved to
    private static final String[] PING_PURPOSE_NAMES = {"connect", "health", "trial"};

    /**
     * Set by tgnet, on tgnet's own threads, whenever any bytes arrive from Telegram; cleared by each
     * background tick that reads it. While the VPN is on those bytes necessarily came through the
     * tunnel, so this is a liveness signal for the current server that costs us nothing to collect
     * and is, if anything, more truthful than the ping - a server carrying real traffic is working
     * even if a fresh proxy handshake happens to time out on it.
     *
     * Static so tgnet can set it without forcing the singleton into existence on a network thread.
     */
    private static volatile boolean tgnetBytesSinceLastTick;

    /** Consecutive background ticks that saw no inbound traffic at all. */
    private int silentBackgroundTicks;
    /**
     * Background pings spent since traffic last flowed. Indexes {@link #BACKGROUND_PING_INTERVALS_MS},
     * so it is also the backoff level. It survives a {@link #switchToNext()} that lands on a server
     * which already failed recently, or one made when nothing has worked for a while: if a long outage
     * makes every server look dead, the backoff has to keep growing across switches, or we would cycle
     * the whole list every couple of minutes and restart the core each time. A switch to a fresh server
     * while the tunnel worked recently resets it, because the backoff measured the old server, and the
     * new one gets a trial instead (see {@link #TRIAL_PING_DELAYS_MS}).
     */
    private int backgroundPingsWithoutTraffic;

    /**
     * Media downloads that gave up (tgnet's RETRY_LIMIT) since the last file that arrived.
     *
     * The health-check ping only exercises a proxy connection, and messages travel on the generic
     * connection, so a server whose download connections are answered by silence looks perfectly
     * healthy to both. That is the state users report as "media doesn't load until I reconnect the
     * VPN": the ping passes, messages arrive, photos never do, and auto-switch never fires because
     * nothing it measures is broken. Downloads giving up is the missing evidence, so it counts
     * towards a switch the way failed pings do.
     */
    private static final int MEDIA_STALLS_BEFORE_SWITCH = 3;
    /** Stalls further apart than this are separate incidents, not one server that stopped working. */
    private static final long MEDIA_STALL_WINDOW_MS = 5 * 60 * 1000L;
    private static volatile int mediaStallsSinceProgress;
    private static volatile long lastMediaStallElapsed;

    private final ArrayList<Listener> listeners = new ArrayList<>();
    private final Runnable autoSwitchRunnable = this::switchToNext;
    private final Runnable healthCheckRunnable = this::runHealthCheck;
    private final Runnable trialRunnable = this::runTrialPing;
    private final Runnable networkRecheckRunnable = this::recheckAfterNetworkChange;

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
            silentBackgroundTicks = 0;
            backgroundPingsWithoutTraffic = 0;
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
        silentBackgroundTicks = 0;
        backgroundPingsWithoutTraffic = 0; // deliberate user action, so drop the outage backoff too
        trialAfterConnect = false;
        save();
        notifyList();
        if (enabled) {
            reconnect();
        }
    }

    public void setEnabled(boolean value) {
        enabled = value;
        save();
        silentBackgroundTicks = 0;
        backgroundPingsWithoutTraffic = 0;
        trialAfterConnect = false;
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
        stopTrial();
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
                silentBackgroundTicks = 0;
                tgnetBytesSinceLastTick = false; // whatever arrived went through the previous tunnel
                measurePing(PING_CONNECT);
                if (trialAfterConnect) {
                    trialAfterConnect = false;
                    startTrial();
                }
                // Keep re-checking reachability on the chosen interval so a server that dies mid-session
                // (not just at connect) triggers auto-switch.
                scheduleHealthCheck();
                // Route all in-process WebViews (the in-app browser included) through the proxy too.
                WebViewProxyController.apply(SingBoxManager.getInstance().getLocalPort());
            } else {
                AndroidUtilities.cancelRunOnUIThread(healthCheckRunnable);
                stopTrial();
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

    /**
     * Ping the current connection through the local proxy to show its latency/availability. A failed
     * {@link #PING_HEALTH} ping counts towards rolling over to the next connection, and a failed
     * {@link #PING_TRIAL} ping advances the trial of a server auto-switch has just moved to.
     *
     * We deliberately do NOT auto-switch on the connect-time ping: the core reports "connected" the
     * instant it starts, before the server has finished its handshake, so an immediate ping usually
     * fails - and since every switch tears the proxy down and starts a new one, switching on it would
     * tight-loop through every connection without any of them getting a chance to come up (exactly the
     * runaway the user hit). Only the health-check tick and a trial switch, and neither fires straight
     * after connecting: in the foreground the tick waits a full interval, in the background it first
     * needs {@link #BACKGROUND_SILENT_TICKS_BEFORE_PING} ticks of total silence, and a trial starts
     * {@link #TRIAL_PING_DELAYS_MS} after the connect, all long enough for a live server to come up.
     */
    private void measurePing(int purpose) {
        final VpnKeyInfo info = currentVpn;
        final int generation = trialGeneration;
        int port = SingBoxManager.getInstance().getLocalPort();
        if (info == null || port <= 0) {
            return;
        }
        info.checking = true;
        lastHealthCheckElapsed = SystemClock.elapsedRealtime();
        int account = UserConfig.selectedAccount;
        ConnectionsManager.getInstance(account).checkProxy(ProxySettings.builder()
                .setType(ProxySettings.Type.SOCKS5)
                .setAddress("127.0.0.1")
                .setPort(port)
                .build(), time -> AndroidUtilities.runOnUIThread(() -> {
            info.checking = false;
            if (BuildVars.LOGS_ENABLED) {
                // Why the health check decided what it did. Without this the only symptom is the core
                // silently restarting every N seconds, which is impossible to attribute from a log.
                FileLog.d("VpnController: ping " + info.getType() + " '" + info.name + "' -> "
                        + (time == -1 ? "FAILED" : time + "ms")
                        + " (" + PING_PURPOSE_NAMES[purpose] + ", autoSwitch=" + autoSwitch
                        + ", state=" + SingBoxManager.getInstance().getState()
                        + ", servers=" + vpnList.size() + ", fg=" + isInForeground() + ")");
            }
            if (time == -1) {
                info.available = false;
                info.ping = 0;
                // Only the periodic health check and a trial switch, and only if we're still connected to
                // the same server they just tested (guards against switching during a transition). The
                // connect-time ping is expected to fail before the handshake completes, so it must not count.
                boolean canSwitch = enabled && autoSwitch && info == currentVpn && vpnList.size() > 1
                        && SingBoxManager.getInstance().getState() == SingBoxManager.STATE_CONNECTED;
                boolean trialPing = purpose == PING_TRIAL && generation == trialGeneration && trialStep >= 0;
                if (trialPing) {
                    if (canSwitch) {
                        onTrialPingFailed();
                    } else {
                        stopTrial();
                    }
                } else if (purpose == PING_HEALTH && canSwitch) {
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
                info.lastFailedElapsed = 0;
                lastTunnelWorkedElapsed = SystemClock.elapsedRealtime();
                info.lastWorkedElapsed = lastTunnelWorkedElapsed;
                consecutivePingFailures = 0;
                if (info == currentVpn && trialStep >= 0) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("VpnController: '" + info.getDisplayName() + "' answered - trial over, staying");
                    }
                    stopTrial();
                }
            }
            notifyList();
        }));
    }

    /**
     * Schedule the next reachability tick. In the foreground the auto-switch timeout slider doubles as
     * the ping interval: every N seconds we re-measure the current connection and, on failure, roll
     * over to the next one. In the background the tick runs on {@link #BACKGROUND_TICK_MS} instead,
     * because there most ticks cost nothing - whether one actually pings is decided in
     * {@link #runHealthCheck()}. No-op unless the VPN is on, auto-switch is enabled, and there's
     * somewhere to switch to.
     */
    private void scheduleHealthCheck() {
        AndroidUtilities.cancelRunOnUIThread(healthCheckRunnable);
        if (!enabled || !autoSwitch || vpnList.size() < 2) {
            return;
        }
        long delay = BACKGROUND_TICK_MS;
        if (isInForeground()) {
            int seconds = AUTOSWITCH_TIMEOUTS[Math.max(0, Math.min(autoSwitchTimeoutIndex, AUTOSWITCH_TIMEOUTS.length - 1))];
            delay = seconds * 1000L;
        }
        AndroidUtilities.runOnUIThread(healthCheckRunnable, delay);
    }

    /**
     * The user is looking at the app. Only then is the fast, user-chosen interval worth its cost.
     * Screen off, or the UI in the background, both count as not-in-front.
     */
    private static boolean isInForeground() {
        return ApplicationLoader.isScreenOn && !ApplicationLoader.mainInterfacePaused;
    }

    /** Called by tgnet for every inbound byte, on tgnet's threads. Keep it to the single store. */
    public static void onTgnetBytesReceived() {
        tgnetBytesSinceLastTick = true;
    }

    /**
     * Android reported a connectivity change. Called from ApplicationLoader's CONNECTIVITY_ACTION
     * receiver on the main thread; the re-check itself waits {@link #NETWORK_SETTLE_MS}.
     */
    public static void onNetworkChanged() {
        VpnController controller = instance;
        if (controller == null) {
            return;
        }
        AndroidUtilities.cancelRunOnUIThread(controller.networkRecheckRunnable);
        AndroidUtilities.runOnUIThread(controller.networkRecheckRunnable, NETWORK_SETTLE_MS);
    }

    /**
     * Everything auto-switch knows about its servers was measured on the old network: failure marks,
     * the failure count and the background backoff. A 10 October log shows what keeping them costs.
     * The phone changed networks at 20:01 and every server became unreachable, the app settled into
     * the slow backoff, and three later network changes passed without a single new check. The tunnel
     * only came back when the user opened the app at 21:07 and picked a server that answered at once.
     * So a change resets the failure count and the backoff and puts the current server on trial. If
     * the trial fails, the usual switch logic takes over. At most once per
     * {@link #NETWORK_LAP_INTERVAL_MS} a change also clears the failure marks and allows a quick lap.
     */
    private void recheckAfterNetworkChange() {
        if (!enabled || !autoSwitch || currentVpn == null || vpnList.size() < 2) {
            return;
        }
        if (!ApplicationLoader.isNetworkOnline()) {
            // Nothing to test on. The change that brings the network back calls us again.
            return;
        }
        long now = SystemClock.elapsedRealtime();
        consecutivePingFailures = 0;
        silentBackgroundTicks = 0;
        backgroundPingsWithoutTraffic = 0;
        boolean lap = lastNetworkLapElapsed == 0 || now - lastNetworkLapElapsed >= NETWORK_LAP_INTERVAL_MS;
        if (lap) {
            lastNetworkLapElapsed = now;
            networkLapUntilElapsed = now + NETWORK_LAP_WINDOW_MS;
            // Only servers that worked earlier get their mark cleared. A key that never answered in
            // this session keeps its 30-minute skip, or every lap pays for it again: three of the seven
            // keys in that log never answered once.
            for (VpnKeyInfo info : vpnList) {
                if (info.lastWorkedElapsed != 0) {
                    info.lastFailedElapsed = 0;
                }
            }
        }
        boolean connected = SingBoxManager.getInstance().getState() == SingBoxManager.STATE_CONNECTED;
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("VpnController: network changed - " + (connected ? "trial of '" + currentVpn.getDisplayName() + "'" : "core not connected")
                    + (lap ? ", failure marks cleared, quick lap allowed" : ", no quick lap (one ran recently)"));
        }
        if (connected) {
            startTrial();
            trialAfterNetworkChange = true;
        }
    }

    /**
     * A media download gave up after tgnet exhausted its retries against one datacenter. Called from
     * the file loader on whichever thread the failure arrived on, so it only touches the counters
     * here and hands the decision to the main thread.
     */
    public static void onMediaDownloadStalled() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastMediaStallElapsed > MEDIA_STALL_WINDOW_MS) {
            mediaStallsSinceProgress = 0;
        }
        lastMediaStallElapsed = now;
        final int count = ++mediaStallsSinceProgress;
        AndroidUtilities.runOnUIThread(() -> getInstance().onMediaStalled(count));
    }

    /** A file finished downloading, so the tunnel is carrying media after all. */
    public static void onMediaDownloadFinished() {
        mediaStallsSinceProgress = 0;
    }

    private void onMediaStalled(int count) {
        if (!enabled || currentVpn == null
                || SingBoxManager.getInstance().getState() != SingBoxManager.STATE_CONNECTED) {
            return;
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("VpnController: media download gave up (" + count + "/" + MEDIA_STALLS_BEFORE_SWITCH
                    + ") while the tunnel is connected");
        }
        if (count < MEDIA_STALLS_BEFORE_SWITCH || !autoSwitch || vpnList.size() < 2) {
            return;
        }
        mediaStallsSinceProgress = 0;
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("VpnController: downloads keep giving up while the ping passes - auto-switching to next server");
        }
        switchToNext();
    }

    /**
     * Decide whether this tick is worth a ping, and ping if so.
     *
     * The distinction matters more than it looks, because a ping is not a UDP echo: it is
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
     * So in the background we do not ping on a timer at all. Instead each tick asks whether tgnet has
     * received anything since the last one ({@link #tgnetBytesSinceLastTick}). If it has, the tunnel
     * is carrying traffic and the server is alive - proven for free, no packet spent, and proven more
     * convincingly than a ping could. Only a tunnel that has gone completely silent while the device
     * was awake gets pinged, and then quickly, until it either answers or is switched away from.
     *
     * That is what makes this both cheaper and faster than the 5 minute background floor it replaces:
     * a healthy backgrounded client now spends *zero* pings, while a server that dies is confirmed
     * dead and rolled over in a couple of minutes instead of the 15-40 that the floor plus Doze cost.
     *
     * Deep sleep needs no special handling: ticks are main-looper posts, so they stop when the device
     * suspends and resume with it, and a silent stretch spent asleep never counts as silence.
     */
    private void runHealthCheck() {
        boolean trafficFlowed = tgnetBytesSinceLastTick;
        tgnetBytesSinceLastTick = false;
        if (enabled && currentVpn != null
                && SingBoxManager.getInstance().getState() == SingBoxManager.STATE_CONNECTED) {
            if (trafficFlowed) {
                // Checked before the foreground branch, not inside the background one: traffic proves
                // the tunnel either way, and a user who opens the app mid-outage must not leave the
                // backoff behind them when they close it again.
                silentBackgroundTicks = 0;
                backgroundPingsWithoutTraffic = 0;
                // Failures before this traffic were blips, not a dead server, see consecutivePingFailures.
                consecutivePingFailures = 0;
                lastTunnelWorkedElapsed = SystemClock.elapsedRealtime();
                currentVpn.lastWorkedElapsed = lastTunnelWorkedElapsed;
            }
            if (isInForeground()) {
                // The user is looking at the list, so the ping is paying for the latency they see as
                // well as for auto-switch. Keep their chosen cadence.
                measurePing(PING_HEALTH);
            } else if (!trafficFlowed
                    && ++silentBackgroundTicks >= BACKGROUND_SILENT_TICKS_BEFORE_PING
                    && SystemClock.elapsedRealtime() - lastHealthCheckElapsed >= backgroundPingIntervalMs()) {
                if (ApplicationLoader.isNetworkOnline()) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("VpnController: no tgnet traffic through the tunnel for "
                                + silentBackgroundTicks + " ticks - checking the server (backoff level "
                                + backgroundPingsWithoutTraffic + ")");
                    }
                    backgroundPingsWithoutTraffic++;
                    measurePing(PING_HEALTH);
                } else if (BuildVars.LOGS_ENABLED
                        && silentBackgroundTicks == BACKGROUND_SILENT_TICKS_BEFORE_PING) {
                    // Airplane mode, no coverage. The silence is not the server's fault and there is
                    // nothing better to switch to, so don't spend a handshake on it. Logged once per
                    // silent stretch rather than every tick.
                    FileLog.d("VpnController: tunnel silent but the device is offline - not checking");
                }
            }
        }
        // Always re-arm, even when the checks above bailed out: scheduleHealthCheck() decides for
        // itself whether a next tick is wanted, and returning early here used to kill the chain
        // outright until some unrelated event happened to restart it.
        scheduleHealthCheck();
    }

    private long backgroundPingIntervalMs() {
        int level = Math.min(backgroundPingsWithoutTraffic, BACKGROUND_PING_INTERVALS_MS.length - 1);
        return BACKGROUND_PING_INTERVALS_MS[level];
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
        long now = SystemClock.elapsedRealtime();
        // Every caller switches because the current server failed: pings, downloads giving up, a core
        // error or a failed trial. Remember it, so the rotation skips this server for a while.
        currentVpn.lastFailedElapsed = now;
        VpnKeyInfo next = pickNextServer(now);
        if (next == null || next == currentVpn) {
            return;
        }
        boolean fresh = !failedRecently(next, now);
        boolean workedRecently = lastTunnelWorkedElapsed != 0 && now - lastTunnelWorkedElapsed < FAILED_SERVER_SKIP_MS;
        boolean trial = fresh && (workedRecently || now < networkLapUntilElapsed);
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("VpnController: switching '" + currentVpn.getDisplayName() + "' -> '" + next.getDisplayName() + "'"
                    + (fresh ? "" : " (every other server failed recently)")
                    + (next.lastWorkedElapsed != 0 ? "" : ", it has not worked in this session")
                    + (trial ? ", trial pings follow" : ", no trial"));
        }
        currentVpn = next;
        consecutivePingFailures = 0; // the new server gets a clean slate
        silentBackgroundTicks = 0;   // ...and a fresh silence window to prove itself in
        if (trial) {
            backgroundPingsWithoutTraffic = 0; // the backoff measured the old server
        }
        trialAfterConnect = trial;
        save();
        notifyList();
        reconnect();
    }

    private boolean hasFreshServer(long now) {
        for (VpnKeyInfo info : vpnList) {
            if (info != currentVpn && !failedRecently(info, now)) {
                return true;
            }
        }
        return false;
    }

    private static boolean failedRecently(VpnKeyInfo info, long now) {
        return info.lastFailedElapsed != 0 && now - info.lastFailedElapsed < FAILED_SERVER_SKIP_MS;
    }

    /**
     * The server to try next, walking the list from the current one. Servers that have not failed
     * within {@link #FAILED_SERVER_SKIP_MS} come first: those that already worked in this session, then
     * those that never did, each group in list order. If every other server failed recently, the one
     * whose failure is oldest among those that worked, and only then the ones that never worked.
     *
     * Until 1.4.1 the fallback was "oldest failure" alone. On 10 October that kept choosing the two
     * keys in the list that never answered at all, because they were always the first to fail in a
     * lap, while the servers that had carried traffic all day waited behind them. "Worked most
     * recently" is no better a key: the replay showed it picks the server that has just died.
     */
    private VpnKeyInfo pickNextServer(long now) {
        int idx = vpnList.indexOf(currentVpn);
        VpnKeyInfo best = null;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 1; i < vpnList.size(); i++) {
            VpnKeyInfo candidate = vpnList.get((idx + i) % vpnList.size());
            boolean worked = candidate.lastWorkedElapsed != 0;
            int rank = failedRecently(candidate, now) ? (worked ? 2 : 3) : (worked ? 0 : 1);
            boolean better;
            if (rank != bestRank) {
                better = rank < bestRank;
            } else if (rank >= 2) {
                better = candidate.lastFailedElapsed < best.lastFailedElapsed;
            } else {
                better = false; // fresh servers keep list order
            }
            if (better) {
                best = candidate;
                bestRank = rank;
            }
        }
        return best;
    }

    private void startTrial() {
        stopTrial();
        trialStep = 0;
        AndroidUtilities.runOnUIThread(trialRunnable, TRIAL_PING_DELAYS_MS[0]);
    }

    private void stopTrial() {
        AndroidUtilities.cancelRunOnUIThread(trialRunnable);
        trialStep = -1;
        trialGeneration++;
        trialAfterNetworkChange = false;
    }

    private void runTrialPing() {
        if (trialStep < 0) {
            return;
        }
        if (!enabled || !autoSwitch || currentVpn == null || vpnList.size() < 2
                || SingBoxManager.getInstance().getState() != SingBoxManager.STATE_CONNECTED) {
            stopTrial();
            return;
        }
        if (!ApplicationLoader.isNetworkOnline()) {
            // No coverage says nothing about this server. Leave it to the regular health check.
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("VpnController: device offline - trial of '" + currentVpn.getDisplayName() + "' dropped");
            }
            stopTrial();
            return;
        }
        measurePing(PING_TRIAL);
    }

    private void onTrialPingFailed() {
        trialStep++;
        if (trialStep < TRIAL_PING_DELAYS_MS.length) {
            AndroidUtilities.runOnUIThread(trialRunnable, TRIAL_PING_DELAYS_MS[trialStep]);
            return;
        }
        int steps = trialStep;
        boolean afterNetworkChange = trialAfterNetworkChange;
        stopTrial();
        if (afterNetworkChange && currentVpn.lastWorkedElapsed != 0 && !hasFreshServer(SystemClock.elapsedRealtime())) {
            // Every other server failed within the last half hour, and this one has worked before, so
            // the new network is most likely still cut off from all of them. Switching now would only
            // move to a server that failed minutes ago. The health check, its count and backoff fresh
            // from the reset, decides about this server as usual. A server that never worked gets no
            // such patience: it switches, and the fallback order puts servers that worked first.
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("VpnController: '" + currentVpn.getDisplayName() + "' failed the network-change trial,"
                        + " every other server failed recently - staying, the health check carries on");
            }
            return;
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("VpnController: '" + currentVpn.getDisplayName() + "' failed all " + steps
                    + " trial pings - moving on");
        }
        switchToNext();
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
