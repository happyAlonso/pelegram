package org.telegram.messenger.vpn;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestTimeDelegate;
import org.telegram.utils.proxy.ProxySettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

        /**
         * A running subscription's server test results changed. They arrive up to four times a
         * second during a test round, so a screen should refresh only the rows that show them: a
         * full notifyDataSetChanged recreates rows and cancels a slider drag in progress.
         */
        default void onVpnGroupStatusChanged() {
            onVpnListChanged();
        }
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
    /**
     * Bumped on every core state event. A ping only counts if no event came between its start and its
     * answer: tgnet keeps a proxy check that it already sent alive across a proxy close (the close
     * handler compares connection tokens after Connection::onDisconnected has zeroed them) and redials
     * the same local port, so the answer can come from the next key's core and was booked on this key.
     */
    private int pingToken;
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
     * How long a ping may stay unanswered before it counts as failed. tgnet reports -1 only for a
     * check whose socket closed before the request went out; one that was sent and never answered
     * (the SOCKS side accepts, the tunnel behind it is dead) stays open with no callback, so without
     * a deadline a health check could never fail for such a server.
     */
    private static final long PING_TIMEOUT_MS = 30 * 1000L;
    /** Result passed for a ping that hit {@link #PING_TIMEOUT_MS}; any negative result is a failure. */
    private static final long PING_TIMED_OUT = -2;

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

    /** Subscription links. Their servers live in {@link #vpnList}, see {@link VpnSubscription}. */
    public final ArrayList<VpnSubscription> subscriptions = new ArrayList<>();
    /** How often a subscription is downloaded again, as the user asked. The header's own interval is ignored. */
    private static final long SUBSCRIPTION_INTERVAL_MS = 60 * 60 * 1000L;
    /** Retry delays after failed automatic updates, indexed by failures - 1; the last one repeats. */
    private static final long[] SUBSCRIPTION_RETRY_MS = {5 * 60 * 1000L, 15 * 60 * 1000L, 60 * 60 * 1000L};
    /** Floor for the check timer, so a due subscription that cannot go out (offline) is not polled in a loop. */
    private static final long SUBSCRIPTION_MIN_CHECK_MS = 60 * 1000L;
    /** First check after start: lets the core connect, so the download can go through the tunnel. */
    private static final long SUBSCRIPTION_FIRST_CHECK_MS = 60 * 1000L;
    private final ExecutorService subscriptionWorker = Executors.newSingleThreadExecutor(r -> new Thread(r, "vpn-subscription"));
    private final Runnable subscriptionCheckRunnable = this::checkSubscriptionsDue;
    /**
     * Set by the first failed health ping of a subscription: the group was asked to test every server
     * at once, and that failure was not counted. Cleared by a good ping and by every core start.
     */
    private boolean groupRetestRequested;
    /** A restart of the running subscription waits for the call in progress to end. */
    private boolean pendingRestart;
    private static final long PENDING_RESTART_CHECK_MS = 30 * 1000L;
    private final Runnable pendingRestartRunnable = this::runPendingRestart;
    /** Slider and exclude edits restart the group once the user stops changing them. */
    private static final long SETTINGS_RESTART_DELAY_MS = 1500L;
    private VpnSubscription settingsChangedSub;
    private final Runnable settingsRestartRunnable = this::applySettingsChange;
    /**
     * How long after a subscription's core starts its first test round is taken as finished. The
     * core forgets a server whose test failed, so a round where nothing answered sends no result
     * at all; without this the screens would say "checking" for good.
     */
    private static final long GROUP_FIRST_ROUND_MS = 20 * 1000L;
    private final Runnable groupFirstRoundRunnable = this::checkGroupFirstRound;
    /** A health tick this soon after a pick does not drop it (the pick re-arms the tick anyway). */
    private static final long PIN_MIN_HOLD_MS = 5 * 1000L;
    /** The selector switch runs on its own thread; ping after it, so the ping goes through the pick. */
    private static final long PICK_PING_DELAY_MS = 1000L;
    /**
     * Restarts spent on servers whose host had no address at start, until a start resolves them all.
     * Each restart drops every Telegram connection, and a host that is gone for good would otherwise
     * cost one every hour and on every network change.
     */
    private static final int MAX_UNRESOLVED_RESTARTS = 3;

    /**
     * The subscription entry the user chose last, by a tap on it (or as the first entry added).
     * Auto-switch only ever moved away from an entry that failed and never back, so on 17 November
     * the phone left the subscription in a 3-minute VLESS outage at 19:32 and then stayed on a
     * Shadowsocks key with 1-2 s pings for the rest of the evening, while the subscription had
     * given about 110 ms (user: "add going back to the subscription"). While auto-switch keeps
     * another entry running, the core carries this subscription's servers as standby (see
     * SingBoxConfigBuilder.Standby), and {@link #probeHome()} checks them from time to time
     * without moving any traffic. A tap on an entry that is not a subscription clears it.
     */
    private VpnKeyInfo home;
    /** Set while the app tries the way back: the entry it left for home, null otherwise. */
    private VpnKeyInfo returnFrom;
    private long returnStartElapsed;
    /** Probes that found no way back since the app left home, the backoff level of {@link #PROBE_DELAYS_MS}. */
    private int returnLevel;
    /**
     * Returns that home failed since the app left it. Each one costs an outage of up to
     * {@link #RETURN_DEADLINE_MS}, so after one the probes keep at least {@link #RETURN_FAILED_DELAY_MS}
     * apart, doubled per further failure up to an hour, and a network change no longer brings the
     * next probe forward once two have failed.
     */
    private int returnFailures;
    private static final long RETURN_FAILED_DELAY_MS = 15 * 60 * 1000L;
    private static final long RETURN_FAILED_MAX_DELAY_MS = 60 * 60 * 1000L;
    /** When the last return was confirmed; home leaving again sooner than {@link #HOME_HOLD_MS} after it counts as a failed return. */
    private long homeCommitElapsed;
    private static final long HOME_HOLD_MS = 15 * 60 * 1000L;
    /**
     * Delays between probes, by {@link #returnLevel}; the last one repeats. A probe tests every
     * server once, so at the cap it costs a third of what the running subscription spends on its
     * own tests at the default 5 minutes, and home is at most 15 minutes late after it recovers.
     */
    private static final long[] PROBE_DELAYS_MS = {5 * 60 * 1000L, 10 * 60 * 1000L, 15 * 60 * 1000L};
    /** Next look when a probe cannot run now (no network, a trial or a call in progress). */
    private static final long PROBE_RETRY_MS = 60 * 1000L;
    /** A probe this soon after a network change, once the running entry's own trial is over. */
    private static final long PROBE_AFTER_NETWORK_MS = 30 * 1000L;
    /** Probes never start closer than this, however often the network changes. */
    private static final long PROBE_MIN_SPACING_MS = 2 * 60 * 1000L;
    /**
     * When the test results are read after the request: the core tests 10 servers at a time with up
     * to 15 s each, so a dead list takes longer than a live one. Stops at the first look with answers.
     */
    private static final long[] PROBE_TEST_LOOKS_MS = {10 * 1000L, 30 * 1000L, 55 * 1000L, 80 * 1000L};
    /** How long the Telegram ping through the standby may stay unanswered. */
    private static final long PROBE_PING_TIMEOUT_MS = 20 * 1000L;
    /**
     * Home must answer a ping this soon after the return, or the app goes back. A trial that ends
     * without a verdict (a timeout in the background is not counted, see measurePing) would
     * otherwise leave it on a silent tunnel.
     */
    private static final long RETURN_DEADLINE_MS = 90 * 1000L;
    /** 0 no probe, 1 the standby servers are being tested, 2 Telegram is pinged through one of them. */
    private int probeStage;
    private int probeLook;
    /** Bumped whenever a probe starts or is cancelled, so a late result of an older one is dropped. */
    private int probeToken;
    private long probeStartElapsed;
    /** Unix seconds when the probe asked for the test; older results do not count. */
    private long probeRequestSec;
    private SingBoxManager.Session probeSession;
    /** The latest standby test results and the core session they came from. */
    private VpnSubscription.GroupStatus standbyStatus;
    private SingBoxManager.Session standbySession;
    private final Runnable probeRunnable = this::probeHome;
    private final Runnable probeLookRunnable = this::lookAtStandbyTest;
    private final Runnable returnDeadlineRunnable = this::onReturnDeadline;

    public interface SubscriptionCallback {
        /** On the UI thread. error is null on success. */
        void onDone(VpnSubscription sub, String error, int servers);
    }

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
        subscriptions.clear();
        try {
            JSONArray subs = new JSONArray(p.getString("subs", "[]"));
            for (int i = 0; i < subs.length(); i++) {
                VpnSubscription s = VpnSubscription.fromJson(subs.getJSONObject(i));
                if (s != null) {
                    subscriptions.add(s);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        try {
            JSONArray arr = new JSONArray(p.getString("list", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                VpnKeyInfo info = new VpnKeyInfo(o.optString("name"), o.optString("key"));
                // An entry whose subscription is missing stays as the plain key it mirrors.
                // Subscriptions are read first: the reconnect below needs them.
                info.sub = findSubscriptionById(o.optString("sub", ""));
                if (info.sub == null || entryOf(info.sub) == null) {
                    vpnList.add(info);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        for (VpnSubscription s : subscriptions) {
            if (entryOf(s) == null) {
                vpnList.add(newEntry(s));
            }
        }
        int cur = p.getInt("current", -1);
        if (cur >= 0 && cur < vpnList.size()) {
            currentVpn = vpnList.get(cur);
        } else if (!vpnList.isEmpty()) {
            currentVpn = vpnList.get(0);
        }
        // Before the reconnect: the core of another entry carries home's servers as standby.
        if (p.contains("home_sub")) {
            VpnSubscription homeSub = findSubscriptionById(p.getString("home_sub", ""));
            home = homeSub == null ? null : entryOf(homeSub);
        } else if (currentVpn != null && currentVpn.sub != null) {
            home = currentVpn; // first start of a build that knows home
        }
        // The core doesn't survive a process restart, so bring the tunnel back up if it was on.
        if (enabled && currentVpn != null && (currentVpn.sub != null || !TextUtils.isEmpty(currentVpn.key))
                && SingBoxManager.getInstance().getState() == SingBoxManager.STATE_IDLE) {
            reconnect();
        }
        updateRouting(); // before the first WebView: blocked until the core is up
        updateBackgroundKeepAlive();
        scheduleSubscriptionCheck(SUBSCRIPTION_FIRST_CHECK_MS);
        scheduleProbe(PROBE_DELAYS_MS[0]);
    }

    public void save() {
        JSONArray arr = new JSONArray();
        for (VpnKeyInfo info : vpnList) {
            JSONObject o = new JSONObject();
            try {
                if (info.sub != null) {
                    // what an older build would run: the first server the group uses
                    info.key = mirrorKey(info.sub, info.key);
                    info.name = info.sub.getDisplayName();
                }
                o.put("name", info.name == null ? "" : info.name);
                o.put("key", info.key);
                if (info.sub != null) {
                    o.put("sub", info.sub.id);
                }
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        JSONArray subs = new JSONArray();
        for (VpnSubscription s : subscriptions) {
            try {
                subs.put(s.toJson());
            } catch (Exception ignored) {
            }
        }
        prefs().edit()
                .putString("list", arr.toString())
                .putString("subs", subs.toString())
                .putBoolean("enabled", enabled)
                .putBoolean("autoswitch", autoSwitch)
                .putBoolean("route_calls", routeCalls)
                .putInt("autoswitch_timeout_v2", autoSwitchTimeoutIndex)
                .putInt("current", currentVpn == null ? -1 : vpnList.indexOf(currentVpn))
                .putString("home_sub", home != null && home.sub != null ? home.sub.id : "")
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
            returnLevel = 0;
            returnFailures = 0;
            homeCommitElapsed = 0;
            scheduleProbe(PROBE_DELAYS_MS[0]);
        } else {
            AndroidUtilities.cancelRunOnUIThread(healthCheckRunnable);
            // Off means no automatic move at all: a return in progress stays where it is now.
            endReturn();
            cancelProbe();
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
     * tgcalls versions whose engine (InstanceV2Impl) relays call media through the SOCKS5 proxy
     * (Socks5UdpSocket). The others ignore the proxy and would send media direct: 2.7.7 and 5.0.0
     * (InstanceImpl, TCP only), 10/11/14 (InstanceV2ReferenceImpl / CompatImpl), 18/19 (Pump).
     */
    public static final List<String> TUNNEL_CALL_VERSIONS = java.util.Arrays.asList("13.0.0", "12.0.0", "9.0.0", "8.0.0", "7.0.0");

    /**
     * Whether call media (1:1 and group) goes through the embedded proxy: whenever the VPN is on. While
     * the core is not connected the call gets the dead port (see {@link #callProxyPort()}), so it
     * fails instead of going direct. The old "route calls" switch is gone: the VPN carries everything.
     */
    public boolean shouldRouteCalls() {
        return isEnabled();
    }

    /** Host of the proxy a call must use, null when the VPN is off (direct). */
    public String callProxyHost() {
        return isEnabled() ? "127.0.0.1" : null;
    }

    /**
     * Port of the proxy a call must use: the core's while it is connected, a dead local port while
     * the VPN is on but not connected, 0 when the VPN is off.
     */
    public int callProxyPort() {
        if (!isEnabled()) {
            return 0;
        }
        SingBoxManager m = SingBoxManager.getInstance();
        int port = m.getLocalPort();
        return m.getState() == SingBoxManager.STATE_CONNECTED && port > 0 ? port : WebViewProxyController.BLOCK_PORT;
    }

    /**
     * The tgcalls versions to offer for a 1:1 call: all of them with the VPN off, only those of the
     * proxied engine while it is on, so the peers never agree on an engine that sends media direct.
     */
    public String[] callVersions(String[] all) {
        if (!isEnabled()) {
            return all;
        }
        ArrayList<String> out = new ArrayList<>();
        for (String v : all) {
            if (TUNNEL_CALL_VERSIONS.contains(v)) {
                out.add(v);
            }
        }
        return out.toArray(new String[0]);
    }

    /** Local SOCKS5 port the sing-box core is listening on (0 if not connected). */
    public int getProxyPort() {
        return SingBoxManager.getInstance().getLocalPort();
    }

    /**
     * Whether web links must stay in the in-app browser: whenever the VPN is on, connected or not.
     * {@link org.telegram.messenger.browser.Browser} uses it to skip Custom Tabs and the external
     * browser, which run in another process and can't be tunnelled. While the tunnel is not up the
     * in-app browser is blocked (see {@link #updateRouting()}), so a link fails instead of
     * opening with the real address.
     */
    public boolean shouldRouteBrowser() {
        return isEnabled();
    }

    /**
     * Points everything the app sends (tgnet, every in-process WebView, the app's own Java requests;
     * calls read {@link #callProxyPort()} when they start) at the core while it is connected, at a
     * dead local port while the VPN is on but the core is not connected (start, restart, error), and
     * direct only when the VPN is off. Fail closed: nothing goes out with the real address while the
     * VPN is on.
     */
    void updateRouting() {
        if (!isEnabled()) {
            WebViewProxyController.clear();
            TgnetRouting.clear();
            return;
        }
        SingBoxManager m = SingBoxManager.getInstance();
        int port = m.getLocalPort();
        if (m.getState() == SingBoxManager.STATE_CONNECTED && port > 0) {
            WebViewProxyController.apply(port);
            TgnetRouting.apply(port);
        } else {
            WebViewProxyController.block();
            TgnetRouting.block();
        }
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
        addVpns(Collections.singletonList(info));
    }

    /**
     * Adds keys at the top of the list in the given order, with one save and one reconnect, so a
     * paste that holds several keys does not restart the core once per key.
     */
    public void addVpns(List<VpnKeyInfo> infos) {
        if (infos.isEmpty()) {
            return;
        }
        vpnList.addAll(0, infos);
        if (currentVpn == null) {
            currentVpn = infos.get(0);
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
        if (info == home || info == returnFrom) {
            endReturn();
            cancelProbe();
            if (info == home) {
                home = null;
            }
        }
        if (info.sub != null) {
            info.sub.generation++; // a download still in flight must not bring it back
            subscriptions.remove(info.sub);
            scheduleNextSubscriptionCheck();
        }
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
                stopCore();
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
        // A fresh try: the core reports the other device again within a minute if it is still on the key
        info.keyInUseElsewhereElapsed = 0;
        // The user's choice: a subscription becomes home, anything else means no way back to one.
        endReturn();
        cancelProbe();
        home = info.sub != null ? info : null;
        returnLevel = 0;
        returnFailures = 0;
        homeCommitElapsed = 0;
        save();
        if (enabled) {
            // before notifyList, so the refresh already shows the cleared ping of the new session
            reconnect();
            // a row tap no longer goes through setEnabled(true) when the VPN is on; keep its retry of
            // the foreground service and the push connection
            updateBackgroundKeepAlive();
        }
        notifyList();
    }

    public void setEnabled(boolean value) {
        enabled = value;
        save();
        silentBackgroundTicks = 0;
        backgroundPingsWithoutTraffic = 0;
        trialAfterConnect = false;
        endReturn();
        cancelProbe();
        returnLevel = 0;
        returnFailures = 0;
        homeCommitElapsed = 0;
        if (enabled && currentVpn != null) {
            reconnect();
            scheduleProbe(PROBE_DELAYS_MS[0]);
        } else {
            stopCore();
        }
        updateBackgroundKeepAlive();
        notifyList();
    }

    /** Stops the core; pings in flight now measure a core that is going away (see {@link #pingToken}). */
    private void stopCore() {
        pingToken++;
        SingBoxManager.getInstance().disconnect();
        updateRouting(); // a core that was already down sends no state event
    }

    private void reconnect() {
        AndroidUtilities.cancelRunOnUIThread(autoSwitchRunnable);
        stopTrial();
        // At once, not when the core's state event reaches this thread: from here on currentVpn may
        // already be the new key while the old core still answers on the port.
        pingToken++;
        if (currentVpn != null) {
            // The row shows ping for the current key as soon as it reads "connected"; without this a
            // reselected key showed the latency of its previous session until the new ping came back.
            currentVpn.ping = 0;
            currentVpn.available = false;
        }
        pendingRestart = false;
        AndroidUtilities.cancelRunOnUIThread(pendingRestartRunnable);
        groupRetestRequested = false;
        // A probe in flight measured the core that is going away.
        if (probeStage != 0) {
            probeStage = 0;
            probeToken++;
            AndroidUtilities.cancelRunOnUIThread(probeLookRunnable);
            scheduleProbe(PROBE_RETRY_MS);
        }
        // While another entry runs, it carries home's servers as standby (see probeHome).
        VpnSubscription standby = null;
        List<VpnKeyInfo> standbyServers = null;
        if (home != null && home != currentVpn && home.sub != null && vpnList.contains(home)) {
            standbyServers = home.sub.includedServers();
            standby = standbyServers.isEmpty() ? null : home.sub;
        }
        if (currentVpn != null && currentVpn.sub != null) {
            VpnSubscription sub = currentVpn.sub;
            List<VpnKeyInfo> servers = new ArrayList<>(sub.includedServers());
            if (sub.firstName != null) {
                // a return: the server Telegram just answered through leads (see startReturn)
                for (int i = 1; i < servers.size(); i++) {
                    if (sub.firstName.equals(servers.get(i).name)) {
                        servers.add(0, servers.remove(i));
                        break;
                    }
                }
                sub.firstName = null;
            }
            if (servers.isEmpty()) {
                // the build fails with a clear message, the row turns red
                String what = sub.servers.isEmpty() ? "has no servers yet" : "has every server excluded";
                FileLog.e("VpnController: subscription '" + sub.getDisplayName() + "' " + what + ", nothing to connect to");
                if (isInForeground()) { // not on every background auto-switch lap
                    toast(sub.getDisplayName() + ": " + what);
                }
            }
            String pinned = null;
            for (VpnKeyInfo s : servers) {
                if (s.name != null && s.name.equals(sub.pinnedName)) {
                    pinned = s.name;
                    break;
                }
            }
            SingBoxManager.getInstance().connectSubscription(sub, servers, sub.getIntervalSec(), pinned, standby, standbyServers);
        } else if (currentVpn != null && !TextUtils.isEmpty(currentVpn.key)) {
            SingBoxManager.getInstance().connect(currentVpn.key, standby, standbyServers);
        }
        // at once: a first key added while the VPN is on must not leave the browser direct until the
        // core's CONNECTING event reaches this thread
        updateRouting();
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
            pingToken++; // any ping still in flight measured the previous core session
            if (state == SingBoxManager.STATE_CONNECTED) {
                AndroidUtilities.cancelRunOnUIThread(autoSwitchRunnable);
                consecutivePingFailures = 0; // fresh core session - don't carry failures across it
                groupRetestRequested = false;
                AndroidUtilities.cancelRunOnUIThread(groupFirstRoundRunnable);
                if (currentVpn != null && currentVpn.sub != null) {
                    AndroidUtilities.runOnUIThread(groupFirstRoundRunnable, GROUP_FIRST_ROUND_MS);
                    SingBoxManager.Session session = SingBoxManager.getInstance().getActiveSession();
                    if (session != null && session.sub == currentVpn.sub && session.unresolved[0] == 0) {
                        currentVpn.sub.unresolvedRestarts = 0; // every host resolved this time
                    }
                }
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
                updateRouting();
            } else {
                AndroidUtilities.cancelRunOnUIThread(healthCheckRunnable);
                stopTrial();
                updateRouting(); // blocked while the VPN is on, direct only when it is off
                AndroidUtilities.cancelRunOnUIThread(groupFirstRoundRunnable);
                for (VpnSubscription s : subscriptions) {
                    s.groupStatus = null; // the next core starts its tests from scratch
                }
                if (state == SingBoxManager.STATE_ERROR && enabled && returnFrom != null) {
                    // home's core did not start: straight back to the entry that worked
                    AndroidUtilities.cancelRunOnUIThread(returnDeadlineRunnable);
                    AndroidUtilities.runOnUIThread(returnDeadlineRunnable, 1000L);
                } else if (state == SingBoxManager.STATE_ERROR && enabled && autoSwitch && vpnList.size() > 1) {
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
        final int token = pingToken;
        int port = SingBoxManager.getInstance().getLocalPort();
        if (info == null || port <= 0) {
            return;
        }
        info.checking = true;
        lastHealthCheckElapsed = SystemClock.elapsedRealtime();
        int account = UserConfig.selectedAccount;
        // Runs on the UI thread, once: for tgnet's answer or for the deadline, whichever comes first.
        final boolean[] answered = new boolean[1];
        final Runnable[] deadline = new Runnable[1];
        final RequestTimeDelegate onAnswer = time -> {
            if (answered[0]) {
                return;
            }
            answered[0] = true;
            AndroidUtilities.cancelRunOnUIThread(deadline[0]);
            if (token != pingToken || info != currentVpn) {
                // Stale (see pingToken): the core restarted or the user picked another key while this
                // ran. A success here was another key's latency and used to mark a dead key as worked,
                // which put it first in pickNextServer. A failure may be our own restart.
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("VpnController: ping " + info.getType() + " '" + info.name + "' -> "
                            + pingResultText(time) + " dropped, the tunnel changed while it ran");
                }
                return;
            }
            info.checking = false;
            if (time == PING_TIMED_OUT && (!ApplicationLoader.isNetworkOnline() || !isInForeground())) {
                // tgnet holds proxy checks back while the device is offline (and possibly while its
                // network is paused in the background), so the silence says nothing about the server.
                // Counting it would let auto-switch walk away from a working server with no network.
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("VpnController: ping " + info.getType() + " '" + info.name + "' -> TIMED OUT, not counted ("
                            + (ApplicationLoader.isNetworkOnline() ? "background" : "offline") + ")");
                }
                // A trial waits for this answer and schedules nothing else: end it, the health check
                // takes over (as runTrialPing does when it finds the device offline).
                if (purpose == PING_TRIAL && generation == trialGeneration && trialStep >= 0) {
                    stopTrial();
                }
                info.ping = 0; // the latency on the row is unknown now, not the previous value
                notifyList();
                return;
            }
            if (BuildVars.LOGS_ENABLED) {
                // Why the health check decided what it did. Without this the only symptom is the core
                // silently restarting every N seconds, which is impossible to attribute from a log.
                FileLog.d("VpnController: ping " + info.getType() + " '" + info.name + "' -> "
                        + pingResultText(time)
                        + " (" + PING_PURPOSE_NAMES[purpose] + ", autoSwitch=" + autoSwitch
                        + ", state=" + SingBoxManager.getInstance().getState()
                        + ", servers=" + vpnList.size() + ", fg=" + isInForeground() + ")");
            }
            if (time < 0 && purpose == PING_HEALTH && info.sub != null
                    && SingBoxManager.getInstance().getState() == SingBoxManager.STATE_CONNECTED) {
                // A subscription runs as a group that retests its servers only every few minutes,
                // so its selected server may have died since. Every failed health ping asks for a
                // test of every server now (with nothing to switch to, that is the only remedy).
                // The first one is not counted: counting it could switch away from the whole
                // subscription while the group still has working servers.
                SingBoxManager.getInstance().requestUrlTest();
                if (!groupRetestRequested) {
                    groupRetestRequested = true;
                    info.available = false;
                    info.ping = 0;
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("VpnController: subscription '" + info.getDisplayName()
                                + "' did not answer - the group tests every server now, this failure is not counted");
                    }
                    notifyList();
                    return;
                }
            }
            if (time < 0) {
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
                groupRetestRequested = false;
                if (info == currentVpn && trialStep >= 0) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("VpnController: '" + info.getDisplayName() + "' answered - trial over, staying");
                    }
                    stopTrial();
                }
                if (info == home && returnFrom != null) {
                    commitReturn("it answered " + (SystemClock.elapsedRealtime() - returnStartElapsed) / 1000 + " s after the return");
                }
                // The tunnel now carries traffic, so a due subscription can download through it. Not
                // on CONNECTED: the core reports that before the server handshake is done.
                if (!subscriptions.isEmpty()) {
                    checkSubscriptionsDue();
                }
            }
            notifyList();
        };
        deadline[0] = () -> onAnswer.run(PING_TIMED_OUT);
        AndroidUtilities.runOnUIThread(deadline[0], PING_TIMEOUT_MS);
        ConnectionsManager.getInstance(account).checkProxy(ProxySettings.builder()
                .setType(ProxySettings.Type.SOCKS5)
                .setAddress("127.0.0.1")
                .setPort(port)
                .build(), time -> AndroidUtilities.runOnUIThread(() -> onAnswer.run(time)));
    }

    private static String pingResultText(long time) {
        return time == PING_TIMED_OUT ? "TIMED OUT" : time < 0 ? "FAILED" : time + "ms";
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
        // With auto-switch on, a subscription alone in the list still gets the check: its failed
        // pings ask the group to retest its servers (see measurePing), even with nothing to switch
        // to. With auto-switch off there are no pings; the network-change hook and the first-round
        // check (checkGroupFirstRound) still ask for a retest.
        boolean subscription = currentVpn != null && currentVpn.sub != null;
        if (!enabled || !autoSwitch || (vpnList.size() < 2 && !subscription)) {
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
        if (isEnabled() && currentVpn.sub != null && ApplicationLoader.isNetworkOnline()
                && SingBoxManager.getInstance().getState() == SingBoxManager.STATE_CONNECTED) {
            // The group ranked its servers on the old network. Independent of auto-switch.
            SingBoxManager.Session session = SingBoxManager.getInstance().getActiveSession();
            if (session != null && session.unresolved[0] > 0 && currentVpn.sub.unresolvedRestarts < MAX_UNRESOLVED_RESTARTS) {
                currentVpn.sub.unresolvedRestarts++;
                String action = restartOrDefer("network changed, " + session.unresolved[0] + " servers had no address at start");
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("VpnController: subscription '" + currentVpn.getDisplayName() + "': " + action);
                }
                return;
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("VpnController: network changed - subscription '" + currentVpn.getDisplayName() + "' tests every server again");
            }
            SingBoxManager.getInstance().requestUrlTest();
        }
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
        // Away from home, the new network may reach it again. The probe waits for the trial above.
        // Only with a lap (at most every 15 min), so a phone on the move does not test every 2 min.
        if (isAwayFromHome() && returnFailures < 2 && lap) {
            returnLevel = 0;
            cancelProbe();
            scheduleProbe(PROBE_AFTER_NETWORK_MS);
        }
    }

    /**
     * The core saw data for another client's session on the current key. A WireGuard or AmneziaWG
     * server keeps one session per key, so two devices on one key keep taking it from each other:
     * on 10 November an AWG 3.1 key carried traffic for about 20 s after each handshake, then
     * nothing until the next one. No setting on this side fixes that, the key needs to be used on
     * one device only. Called from the core log thread.
     */
    public static void onKeyInUseElsewhere() {
        AndroidUtilities.runOnUIThread(() -> {
            VpnController controller = instance;
            if (controller != null) {
                controller.handleKeyInUseElsewhere();
            }
        });
    }

    private void handleKeyInUseElsewhere() {
        if (!enabled || currentVpn == null) {
            return;
        }
        currentVpn.keyInUseElsewhereElapsed = SystemClock.elapsedRealtime();
        boolean canSwitch = autoSwitch && vpnList.size() > 1;
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("VpnController: another device uses the key of '" + currentVpn.getDisplayName()
                    + "' at the same time, the server sends this tunnel's traffic to it"
                    + (canSwitch ? " - auto-switching to next server" : ""));
        }
        notifyList();
        if (canSwitch) {
            switchToNext();
        }
    }

    /** Whether the core reported another client on this key within the last {@link #FAILED_SERVER_SKIP_MS}. */
    public static boolean isKeyInUseElsewhere(VpnKeyInfo info) {
        return info.keyInUseElsewhereElapsed != 0
                && SystemClock.elapsedRealtime() - info.keyInUseElsewhereElapsed < FAILED_SERVER_SKIP_MS;
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
        // With auto-switch on, a server picked in the subscription's submenu holds until this check,
        // then the group goes back to the fastest (user, 2026-11-17: "if reconnect is on, next pings
        // > fastest server"). The pick re-arms this tick, so it holds one full interval. A pick from
        // before an app restart has no time and goes at the first check. Not in a call.
        if (enabled && autoSwitch && currentVpn != null && currentVpn.sub != null && currentVpn.sub.pinnedName != null
                && !isCallActive() && (currentVpn.sub.pinnedAtElapsed == 0
                || SystemClock.elapsedRealtime() - currentVpn.sub.pinnedAtElapsed >= PIN_MIN_HOLD_MS)) {
            pickSubscriptionServer(currentVpn.sub, null, "auto-switch is on");
            // No health ping in this tick: the switch closes open connections, a ping now could
            // fail on it. The pick's own ping a second later shows the new latency.
            scheduleHealthCheck();
            return;
        }
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
        if (returnFrom != null) {
            // home failed again right after the return: back to the entry that worked, not the walk
            returnFailed("auto-switch gave up on it");
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
        boolean leavingHome = currentVpn == home;
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
        reconnect(); // before notifyList, so the refresh already shows the cleared ping
        notifyList();
        if (leavingHome) {
            // Home failed again soon after a return: that return counts as failed too, or a home
            // that answers one ping and then dies would pull the app back every few minutes.
            boolean shortStay = homeCommitElapsed != 0 && now - homeCommitElapsed < HOME_HOLD_MS;
            if (shortStay) {
                returnFailures++;
            } else {
                returnFailures = 0;
            }
            homeCommitElapsed = 0;
            returnLevel = 0;
            scheduleProbe(nextProbeDelay());
        } else if (next == home) {
            cancelProbe(); // the walk itself came back home
        }
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
        if (returnFrom != null) {
            returnFailed(steps + " trial pings failed");
            return;
        }
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

    // ---- the way back to home (see home) ----

    /** Auto-switch runs another entry than home, and no return is under way. */
    private boolean isAwayFromHome() {
        return enabled && autoSwitch && home != null && returnFrom == null && currentVpn != null
                && currentVpn != home && vpnList.contains(home);
    }

    private long nextProbeDelay() {
        long delay = PROBE_DELAYS_MS[Math.min(returnLevel, PROBE_DELAYS_MS.length - 1)];
        if (returnFailures > 0) {
            delay = Math.max(delay, Math.min(RETURN_FAILED_MAX_DELAY_MS, RETURN_FAILED_DELAY_MS << Math.min(returnFailures - 1, 4)));
        }
        return delay;
    }

    /** (Re)arms the next probe, if the app is away from home. A plain main-looper post, like the health tick. */
    private void scheduleProbe(long delayMs) {
        AndroidUtilities.cancelRunOnUIThread(probeRunnable);
        if (isAwayFromHome()) {
            AndroidUtilities.runOnUIThread(probeRunnable, delayMs);
        }
    }

    private void cancelProbe() {
        AndroidUtilities.cancelRunOnUIThread(probeRunnable);
        AndroidUtilities.cancelRunOnUIThread(probeLookRunnable);
        if (probeStage != 0) {
            probeStage = 0;
            probeToken++;
        }
    }

    /** The return is over, one way or the other: it no longer needs its deadline. */
    private void endReturn() {
        returnFrom = null;
        AndroidUtilities.cancelRunOnUIThread(returnDeadlineRunnable);
    }

    /** Why the last probe could not run, logged once per reason rather than every minute. */
    private String lastProbeWait;

    /**
     * Checks whether home works again, without moving any traffic: the core of the running entry
     * tests home's standby servers (stage 1), then Telegram is pinged through the fastest of them on
     * the standby port (stage 2). Only that answer starts the return. A test of the servers alone
     * is not enough: on 17 November at 19:31 one server answered the core's test in 250 ms while
     * every Telegram connection through it ended after 1.4 s.
     */
    private void probeHome() {
        if (!isAwayFromHome() || probeStage != 0) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (probeStartElapsed != 0 && now - probeStartElapsed < PROBE_MIN_SPACING_MS) {
            scheduleProbe(PROBE_MIN_SPACING_MS - (now - probeStartElapsed));
            return;
        }
        SingBoxManager m = SingBoxManager.getInstance();
        SingBoxManager.Session session = m.getActiveSession();
        boolean proven = currentVpn.available || (currentVpn.lastWorkedElapsed != 0
                && now - currentVpn.lastWorkedElapsed < 10 * 60 * 1000L);
        String wait = null;
        if (!ApplicationLoader.isNetworkOnline()) {
            wait = "the device is offline";
        } else if (m.getState() != SingBoxManager.STATE_CONNECTED || session == null) {
            wait = "the tunnel is not connected";
        } else if (trialStep >= 0) {
            wait = "'" + currentVpn.getDisplayName() + "' is on trial";
        } else if (!proven) {
            wait = "'" + currentVpn.getDisplayName() + "' has not answered yet";
        } else if (isCallActive()) {
            wait = "a call is in progress";
        }
        if (wait != null) {
            if (BuildVars.LOGS_ENABLED && !wait.equals(lastProbeWait)) {
                FileLog.d("VpnController: way back to '" + home.getDisplayName() + "' not checked now, " + wait);
            }
            lastProbeWait = wait;
            scheduleProbe(PROBE_RETRY_MS);
            return;
        }
        lastProbeWait = null;
        if (session.home != home.sub || session.homeTagNames.isEmpty() || session.probePort <= 0) {
            // The core started before home was set, or home had no server to carry then.
            returnLevel++;
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("VpnController: way back to '" + home.getDisplayName() + "' not checked, the running core"
                        + " carries none of its servers; next look in " + nextProbeDelay() / 60000 + " min");
            }
            scheduleProbe(nextProbeDelay());
            return;
        }
        if (!sameServers(session.homeServers, home.sub.includedServers())) {
            // An update or the exclude list changed home's servers since this core started: a test of
            // the old ones says little, and a server it passed may not be in the group that the
            // return starts. One restart of the running entry carries the current list.
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("VpnController: the servers of '" + home.getDisplayName() + "' changed since '"
                        + currentVpn.getDisplayName() + "' started, restarting it to carry the new list");
            }
            reconnect();
            scheduleProbe(PROBE_RETRY_MS);
            return;
        }
        probeStage = 1;
        probeToken++;
        probeLook = 0;
        probeSession = session;
        probeStartElapsed = now;
        probeRequestSec = System.currentTimeMillis() / 1000 - 1;
        if (!m.requestStandbyTest()) {
            probeStage = 0;
            scheduleProbe(PROBE_RETRY_MS);
            return;
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("VpnController: away from '" + home.getDisplayName() + "' on '" + currentVpn.getDisplayName()
                    + "' - testing its " + session.homeTagNames.size() + " servers through the standby (check "
                    + (returnLevel + 1) + ")");
        }
        AndroidUtilities.runOnUIThread(probeLookRunnable, PROBE_TEST_LOOKS_MS[0]);
    }

    /** Same servers, ignoring order and the sni/sid that BlancVPN rotates on every download. */
    private static boolean sameServers(List<VpnKeyInfo> a, List<VpnKeyInfo> b) {
        if (a == null || b == null || a.size() != b.size()) {
            return false;
        }
        List<String> ka = new ArrayList<>();
        List<String> kb = new ArrayList<>();
        for (VpnKeyInfo s : a) {
            ka.add(s.key);
        }
        for (VpnKeyInfo s : b) {
            kb.add(s.key);
        }
        return VpnSubscriptionParser.identity(ka).equals(VpnSubscriptionParser.identity(kb));
    }

    /** Stage 1 result: the fastest standby server that answered this test, then the Telegram ping through it. */
    private void lookAtStandbyTest() {
        if (probeStage != 1) {
            return;
        }
        SingBoxManager m = SingBoxManager.getInstance();
        if (probeSession != m.getActiveSession() || m.getState() != SingBoxManager.STATE_CONNECTED) {
            probeStage = 0;
            scheduleProbe(PROBE_RETRY_MS);
            return;
        }
        VpnSubscription.GroupStatus status = standbySession == probeSession ? standbyStatus : null;
        String best = null;
        int bestDelay = Integer.MAX_VALUE;
        int answered = 0;
        if (status != null) {
            for (java.util.Map.Entry<String, Integer> e : status.delays.entrySet()) {
                Long time = status.times.get(e.getKey());
                if (time == null || time < probeRequestSec) {
                    continue; // from an earlier test
                }
                answered++;
                if (e.getValue() < bestDelay) {
                    best = e.getKey();
                    bestDelay = e.getValue();
                }
            }
        }
        int total = probeSession.homeTagNames.size();
        if (best == null) {
            if (++probeLook < PROBE_TEST_LOOKS_MS.length) {
                AndroidUtilities.runOnUIThread(probeLookRunnable, PROBE_TEST_LOOKS_MS[probeLook] - PROBE_TEST_LOOKS_MS[probeLook - 1]);
                return;
            }
            probeFailed("none of its " + total + " servers answered the test");
            return;
        }
        String tag = null;
        for (java.util.Map.Entry<String, String> e : probeSession.homeTagNames.entrySet()) {
            if (best.equals(e.getValue())) {
                tag = e.getKey();
                break;
            }
        }
        if (tag == null) {
            probeFailed("its fastest server has no tag in the core");
            return;
        }
        probeStage = 2;
        final int token = probeToken;
        final String server = best;
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("VpnController: way back to '" + home.getDisplayName() + "': " + answered + " of " + total
                    + " servers answered the test, fastest '" + best + "' (" + bestDelay + " ms) - pinging Telegram through it");
        }
        m.selectStandby(tag, ok -> {
            if (token != probeToken || probeStage != 2) {
                return;
            }
            if (!ok) {
                probeFailed("the core did not switch its standby to '" + server + "'");
                return;
            }
            pingThroughStandby(token, server);
        });
    }

    /** Stage 2: a Telegram ping on the standby port, which reaches Telegram through home's server only. */
    private void pingThroughStandby(final int token, final String server) {
        final int startToken = pingToken;
        final SingBoxManager.Session session = probeSession;
        final boolean[] answered = new boolean[1];
        final Runnable[] deadline = new Runnable[1];
        final RequestTimeDelegate onAnswer = time -> {
            if (answered[0]) {
                return;
            }
            answered[0] = true;
            AndroidUtilities.cancelRunOnUIThread(deadline[0]);
            if (token != probeToken || probeStage != 2) {
                return; // cancelled meanwhile
            }
            probeStage = 0;
            if (startToken != pingToken || session != SingBoxManager.getInstance().getActiveSession()) {
                // The core restarted while the ping ran; the answer may come from the next core,
                // which can listen on the same port.
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("VpnController: ping through the standby dropped, the tunnel changed while it ran");
                }
                scheduleProbe(PROBE_RETRY_MS);
                return;
            }
            if (time < 0) {
                probeFailed("Telegram did not answer through '" + server + "'" + (time == PING_TIMED_OUT ? " within "
                        + PROBE_PING_TIMEOUT_MS / 1000 + " s" : ""));
                return;
            }
            if (!isAwayFromHome() || isCallActive()) {
                scheduleProbe(PROBE_RETRY_MS);
                return;
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("VpnController: Telegram answered through '" + server + "' of '" + home.getDisplayName() + "' in "
                        + time + " ms - going back from '" + currentVpn.getDisplayName() + "'");
            }
            startReturn(server);
        };
        deadline[0] = () -> onAnswer.run(PING_TIMED_OUT);
        AndroidUtilities.runOnUIThread(deadline[0], PROBE_PING_TIMEOUT_MS);
        ConnectionsManager.getInstance(UserConfig.selectedAccount).checkProxy(ProxySettings.builder()
                .setType(ProxySettings.Type.SOCKS5)
                .setAddress("127.0.0.1")
                .setPort(session.probePort)
                .build(), time -> AndroidUtilities.runOnUIThread(() -> onAnswer.run(time)));
    }

    private void probeFailed(String reason) {
        probeStage = 0;
        returnLevel++;
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("VpnController: no way back to '" + home.getDisplayName() + "' yet: " + reason
                    + "; next check in " + nextProbeDelay() / 60000 + " min");
        }
        scheduleProbe(nextProbeDelay());
    }

    /**
     * Moves the tunnel back to home, with {@code provenServer} (the one Telegram just answered
     * through) first in the group: until its first test round ends the group uses its first
     * server, and with dead servers in the list that round takes up to 75 s. Home has to answer
     * within {@link #RETURN_DEADLINE_MS}, or the app goes back.
     */
    private void startReturn(String provenServer) {
        if (home.sub != null) {
            if (home.sub.pinnedName != null) {
                // a pick would hold the group on one server, which may be the dead one
                home.sub.pinnedName = null;
                home.sub.pinnedAtElapsed = 0;
            }
            home.sub.firstName = provenServer;
        }
        returnFrom = currentVpn;
        returnStartElapsed = SystemClock.elapsedRealtime();
        currentVpn = home;
        consecutivePingFailures = 0;
        silentBackgroundTicks = 0;
        backgroundPingsWithoutTraffic = 0;
        mediaStallsSinceProgress = 0; // stalls on the old entry, or caused by this restart, are not home's
        trialAfterConnect = true;
        save();
        reconnect();
        notifyList();
        AndroidUtilities.cancelRunOnUIThread(returnDeadlineRunnable);
        AndroidUtilities.runOnUIThread(returnDeadlineRunnable, RETURN_DEADLINE_MS);
    }

    /** The return holds: the backoff starts over, and a failure of home in the next {@link #HOME_HOLD_MS} still counts. */
    private void commitReturn(String why) {
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("VpnController: back on '" + home.getDisplayName() + "', " + why);
        }
        endReturn();
        returnLevel = 0;
        homeCommitElapsed = SystemClock.elapsedRealtime();
    }

    /** The return deadline, or a core error during the return (posted from onSingBoxState). */
    private void onReturnDeadline() {
        if (returnFrom == null) {
            return;
        }
        SingBoxManager m = SingBoxManager.getInstance();
        if (m.getState() == SingBoxManager.STATE_CONNECTED && currentVpn == home) {
            // In the background a ping that times out is not counted, so the trial can end without
            // a verdict while home carries Telegram's traffic. That traffic is the better proof.
            if (tgnetBytesSinceLastTick || home.lastWorkedElapsed > returnStartElapsed) {
                commitReturn("Telegram traffic arrived through it, no ping answered within " + RETURN_DEADLINE_MS / 1000 + " s");
                return;
            }
            if (!ApplicationLoader.isNetworkOnline()) {
                // nothing to judge home by while offline; the change that brings the network back puts it on trial
                AndroidUtilities.runOnUIThread(returnDeadlineRunnable, PENDING_RESTART_CHECK_MS);
                return;
            }
        }
        returnFailed(m.getState() == SingBoxManager.STATE_ERROR
                ? "the core did not start" : "no answer within " + RETURN_DEADLINE_MS / 1000 + " s");
    }

    /** Home failed right after the return: back to the entry that worked before it, not through the list walk. */
    private void returnFailed(String reason) {
        AndroidUtilities.cancelRunOnUIThread(returnDeadlineRunnable);
        if (returnFrom == null) {
            return;
        }
        if (isCallActive()) {
            // a restart now would break the call; look again when it may be over
            AndroidUtilities.runOnUIThread(returnDeadlineRunnable, PENDING_RESTART_CHECK_MS);
            return;
        }
        VpnKeyInfo back = returnFrom;
        returnFrom = null;
        returnLevel++;
        returnFailures++;
        mediaStallsSinceProgress = 0;
        if (home != null) {
            home.lastFailedElapsed = SystemClock.elapsedRealtime(); // the list walk skips it for a while
        }
        if (!enabled || !vpnList.contains(back)) {
            return;
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("VpnController: return to '" + (home != null ? home.getDisplayName() : "?") + "' failed (" + reason + ") - back to '"
                    + back.getDisplayName() + "', next check in " + nextProbeDelay() / 60000 + " min");
        }
        currentVpn = back;
        consecutivePingFailures = 0;
        silentBackgroundTicks = 0;
        backgroundPingsWithoutTraffic = 0;
        trialAfterConnect = true;
        save();
        reconnect();
        notifyList();
        scheduleProbe(nextProbeDelay());
    }

    /** Standby test results from the core's log client, on a gomobile thread. */
    static void onStandbyStatus(SingBoxManager.Session session, VpnSubscription.GroupStatus status) {
        AndroidUtilities.runOnUIThread(() -> {
            VpnController controller = instance;
            if (controller == null || session != SingBoxManager.getInstance().getActiveSession()) {
                return;
            }
            controller.standbySession = session;
            controller.standbyStatus = status;
        });
    }

    // ---- subscriptions ----

    private VpnSubscription findSubscriptionById(String id) {
        if (TextUtils.isEmpty(id)) {
            return null;
        }
        for (VpnSubscription s : subscriptions) {
            if (s.id.equals(id)) {
                return s;
            }
        }
        return null;
    }

    public VpnSubscription findSubscriptionByUrl(String url) {
        for (VpnSubscription s : subscriptions) {
            if (s.url.equals(url)) {
                return s;
            }
        }
        return null;
    }

    /** The list entry that stands for a subscription, or null. */
    public VpnKeyInfo entryOf(VpnSubscription sub) {
        for (VpnKeyInfo info : vpnList) {
            if (info.sub == sub) {
                return info;
            }
        }
        return null;
    }

    private static VpnKeyInfo newEntry(VpnSubscription sub) {
        VpnKeyInfo entry = new VpnKeyInfo(sub.getDisplayName(), mirrorKey(sub, ""));
        entry.sub = sub;
        return entry;
    }

    /** The key an older build would run for this subscription: the first server the group uses. */
    private static String mirrorKey(VpnSubscription sub, String fallback) {
        List<VpnKeyInfo> included = sub.includedServers();
        if (!included.isEmpty()) {
            return included.get(0).key;
        }
        if (!sub.servers.isEmpty()) {
            return sub.servers.get(0).key;
        }
        return fallback == null ? "" : fallback;
    }

    /** Whether this subscription carries the tunnel right now (selected and the VPN on). */
    public boolean isRunning(VpnSubscription sub) {
        return isEnabled() && currentVpn != null && currentVpn.sub == sub;
    }

    /** The local proxy port when the tunnel is up, so a download goes through it; 0 means direct. */
    private int subscriptionProxyPort() {
        SingBoxManager m = SingBoxManager.getInstance();
        int port = m.getLocalPort();
        return enabled && currentVpn != null && m.getState() == SingBoxManager.STATE_CONNECTED && port > 0 ? port : 0;
    }

    /**
     * Whether the tunnel answered its last ping. A download that fails through a tunnel that is up
     * but not proven is tried again direct: if every key of a subscription was replaced, the dead
     * tunnel would otherwise block the only way to the new keys.
     */
    private boolean tunnelProven() {
        return currentVpn != null && currentVpn.available
                && SingBoxManager.getInstance().getState() == SingBoxManager.STATE_CONNECTED;
    }

    /** Worker thread. */
    private static VpnSubscriptionFetcher.Result fetchWithFallback(String url, int port, boolean directRetry) {
        VpnSubscriptionFetcher.Result r = VpnSubscriptionFetcher.fetch(url, port);
        if (r.error != null && port > 0 && directRetry) {
            VpnSubscriptionFetcher.Result direct = VpnSubscriptionFetcher.fetch(url, 0);
            direct.tunnelAttempt = r;
            return direct;
        }
        return r;
    }

    /** The error of a download in one line, for a toast and the subscription screen. */
    public static String errorText(VpnSubscriptionFetcher.Result r) {
        String text = (r.viaTunnel ? "via VPN: " : "direct: ") + r.errorLine;
        if (r.tunnelAttempt != null) {
            text = "via VPN: " + r.tunnelAttempt.errorLine + "; " + text;
        }
        return text;
    }

    /** Full error into the log (with logging on): every exception in the chain with its stack frames. */
    private void logSubscriptionFetch(VpnSubscription sub, VpnSubscriptionFetcher.Result r, String reason) {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        String head = "VpnController: subscription '" + sub.getDisplayName() + "' (" + reason + ") ";
        VpnSubscriptionFetcher.Result tunnel = r.tunnelAttempt;
        if (tunnel != null) {
            FileLog.e(head + "download through the VPN failed after " + tunnel.tookMs + " ms (the tunnel did not answer"
                    + " its last ping, so trying direct): " + tunnel.errorDetail);
        }
        String route = r.viaTunnel ? "through the VPN" : "direct";
        if (r.error != null) {
            FileLog.e(head + "download " + route + " failed after " + r.tookMs + " ms: " + r.errorDetail);
        } else {
            FileLog.d(head + "downloaded " + route + ": " + r.keys.size() + " servers, " + (r.base64 ? "base64" : "plain")
                    + ", " + r.bodyBytes + " bytes, " + r.tookMs + " ms"
                    + (TextUtils.isEmpty(r.skipped) ? "" : ", skipped " + r.skipped)
                    + (r.errorDetail != null ? "; " + r.errorDetail : ""));
        }
    }

    private static void toast(String text) {
        try {
            Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_LONG).show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * Downloads a new subscription and adds it as one entry at the top of the list. Nothing is
     * saved unless the download works. The caller handles a link that is already in the list.
     */
    public void addSubscription(String url, String name, SubscriptionCallback callback) {
        final VpnSubscription sub = new VpnSubscription(UUID.randomUUID().toString(), url);
        sub.name = name == null ? "" : name.trim();
        final int port = subscriptionProxyPort();
        final boolean directRetry = port > 0 && !tunnelProven();
        subscriptionWorker.execute(() -> {
            VpnSubscriptionFetcher.Result r = fetchWithFallback(url, port, directRetry);
            AndroidUtilities.runOnUIThread(() -> {
                logSubscriptionFetch(sub, r, "add");
                if (r.error != null) {
                    callback.onDone(sub, errorText(r), 0);
                    return;
                }
                VpnSubscription existing = findSubscriptionByUrl(url);
                if (existing != null) {
                    // added twice while the first download ran: the result updates that one
                    applySubscriptionUpdate(existing, r, true);
                    callback.onDone(existing, null, existing.servers.size());
                    return;
                }
                sub.title = r.title == null ? "" : r.title;
                sub.servers = new ArrayList<>(r.keys);
                sub.lastUpdateWallMs = System.currentTimeMillis();
                sub.lastAttemptWallMs = sub.lastUpdateWallMs;
                sub.lastUpdateViaTunnel = r.viaTunnel;
                subscriptions.add(sub);
                VpnKeyInfo entry = newEntry(sub);
                vpnList.add(0, entry);
                // Like addVpns, but no restart of a tunnel that already runs on another entry.
                boolean pick = currentVpn == null;
                if (pick) {
                    currentVpn = entry;
                    home = entry;
                }
                save();
                notifyList();
                if (pick) {
                    if (isEnabled()) {
                        reconnect();
                    }
                    updateBackgroundKeepAlive();
                }
                scheduleNextSubscriptionCheck();
                callback.onDone(sub, null, sub.servers.size());
            });
        });
    }

    /**
     * Downloads a subscription again. Manual: the "Update now" button, always goes out and reports
     * the result in a toast. Automatic: skipped while offline, and its failures step the backoff.
     */
    public void refreshSubscription(VpnSubscription sub, boolean manual) {
        if (sub.fetching || !subscriptions.contains(sub)) {
            return;
        }
        if (!manual && !ApplicationLoader.isNetworkOnline()) {
            return; // nothing to learn about the link offline; the timer looks again
        }
        sub.fetching = true;
        sub.lastAttemptWallMs = System.currentTimeMillis();
        final int generation = sub.generation;
        final int token = pingToken;
        final int port = subscriptionProxyPort();
        final boolean directRetry = port > 0 && !tunnelProven();
        final String url = sub.url;
        notifyList();
        subscriptionWorker.execute(() -> {
            VpnSubscriptionFetcher.Result r = fetchWithFallback(url, port, directRetry);
            AndroidUtilities.runOnUIThread(() -> onSubscriptionFetched(sub, generation, token, manual, r));
        });
    }

    private void onSubscriptionFetched(VpnSubscription sub, int generation, int token, boolean manual, VpnSubscriptionFetcher.Result r) {
        sub.fetching = false;
        logSubscriptionFetch(sub, r, manual ? "manual" : "hourly");
        if (generation != sub.generation || !subscriptions.contains(sub)) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("VpnController: subscription '" + sub.getDisplayName() + "' was deleted while it downloaded, result dropped");
            }
            return;
        }
        if (r.error != null) {
            String text = errorText(r);
            sub.lastError = r.error;
            sub.lastErrorDetail = text;
            // A failure through a tunnel that restarted while the request ran says nothing about the
            // link itself, so it does not step the backoff.
            boolean tunnelChanged = r.viaTunnel && token != pingToken;
            if (!manual && !tunnelChanged) {
                sub.failures++;
            }
            // Automatic failures: one toast per streak, and only where the user can see it.
            if (manual || (sub.failures == 1 && isInForeground())) {
                toast(sub.getDisplayName() + ": update failed, " + text);
            }
            notifyList();
            scheduleNextSubscriptionCheck();
            return;
        }
        applySubscriptionUpdate(sub, r, manual);
        if (manual) {
            toast(LocaleController.formatString(R.string.VpnSubscriptionUpdatedToast, sub.getDisplayName(), sub.servers.size()));
        }
    }

    /**
     * Stores the downloaded servers. The running group is restarted only when that changes
     * something: the server list itself changed (see {@link VpnSubscriptionParser#identity}, which
     * ignores the sni and sid that BlancVPN rotates on every download), or the tunnel is failing.
     * Every restart makes the core test all servers again. A manual update that changed nothing
     * asks the running group for a fresh test instead. Never in a call: then it waits for its end.
     */
    private void applySubscriptionUpdate(VpnSubscription sub, VpnSubscriptionFetcher.Result r, boolean manual) {
        String before = sub.identity();
        int oldCount = sub.servers.size();
        sub.servers = new ArrayList<>(r.keys);
        boolean changed = !before.equals(sub.identity());
        dropPinIfGone(sub);
        if (!TextUtils.isEmpty(r.title)) {
            sub.title = r.title;
        }
        sub.lastUpdateWallMs = System.currentTimeMillis();
        sub.lastUpdateViaTunnel = r.viaTunnel;
        sub.lastError = null;
        sub.lastErrorDetail = null;
        sub.failures = 0;
        String action = "the group keeps running";
        if (isRunning(sub)) {
            boolean failing = consecutivePingFailures > 0 || groupRetestRequested
                    || SingBoxManager.getInstance().getState() == SingBoxManager.STATE_ERROR;
            // servers whose host did not resolve when this core started are missing from the group
            SingBoxManager.Session session = SingBoxManager.getInstance().getActiveSession();
            boolean unresolved = session != null && session.sub == sub && session.unresolved[0] > 0
                    && sub.unresolvedRestarts < MAX_UNRESOLVED_RESTARTS;
            if (unresolved && !changed && !failing) {
                sub.unresolvedRestarts++;
            }
            if (changed || failing || unresolved) {
                action = restartOrDefer(changed ? "the server list changed" : failing ? "the tunnel is failing"
                        : session.unresolved[0] + " servers had no address at start");
            } else if (manual) {
                SingBoxManager.getInstance().requestUrlTest();
                action = "same servers, the group tests them again";
            }
        } else {
            action = "not running";
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("VpnController: subscription '" + sub.getDisplayName() + "' updated: " + oldCount + " -> "
                    + sub.servers.size() + " servers, " + sub.includedServers().size() + " used, "
                    + (changed ? "changed" : "same servers") + ", " + action);
        }
        save();
        notifyList();
        scheduleNextSubscriptionCheck();
    }

    /** Restarts the core for the current subscription now, or once the call in progress ends. Returns what it did, for the log. */
    private String restartOrDefer(String why) {
        if (isCallActive()) {
            pendingRestart = true;
            AndroidUtilities.cancelRunOnUIThread(pendingRestartRunnable);
            AndroidUtilities.runOnUIThread(pendingRestartRunnable, PENDING_RESTART_CHECK_MS);
            return why + ", restart after the call";
        }
        reconnect();
        return why + ", restarted";
    }

    private void runPendingRestart() {
        if (!pendingRestart) {
            return;
        }
        if (isCallActive()) {
            AndroidUtilities.runOnUIThread(pendingRestartRunnable, PENDING_RESTART_CHECK_MS);
            return;
        }
        pendingRestart = false;
        if (isEnabled() && currentVpn != null && currentVpn.sub != null) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("VpnController: call ended, restarting subscription '" + currentVpn.getDisplayName() + "' with its new settings");
            }
            reconnect();
        }
    }

    /** The submenu changed how the group runs: restart it a moment after the last change, if it runs. */
    private void settingsChanged(VpnSubscription sub) {
        notifyList(); // saved with the restart below, not on every slider step
        settingsChangedSub = sub;
        AndroidUtilities.cancelRunOnUIThread(settingsRestartRunnable);
        AndroidUtilities.runOnUIThread(settingsRestartRunnable, SETTINGS_RESTART_DELAY_MS);
    }

    private void applySettingsChange() {
        VpnSubscription sub = settingsChangedSub;
        settingsChangedSub = null;
        save();
        if (sub != null && isRunning(sub)) {
            String action = restartOrDefer("settings changed");
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("VpnController: subscription '" + sub.getDisplayName() + "': check every " + sub.getIntervalSec()
                        + " s, exclude '" + sub.exclude + "', " + sub.includedServers().size() + " of " + sub.servers.size()
                        + " servers used - " + action);
            }
        }
    }

    public void setSubscriptionInterval(VpnSubscription sub, int index) {
        if (sub.intervalIndex == index) {
            return;
        }
        sub.intervalIndex = index;
        settingsChanged(sub);
    }

    public void setSubscriptionExclude(VpnSubscription sub, String exclude) {
        String value = exclude == null ? "" : exclude.trim();
        if (value.equals(sub.exclude)) {
            return;
        }
        sub.exclude = value;
        dropPinIfGone(sub);
        settingsChanged(sub);
    }

    /** A picked server that an update removed or the exclude list now hides is no longer picked. */
    private void dropPinIfGone(VpnSubscription sub) {
        if (sub.pinnedName == null) {
            return;
        }
        for (VpnKeyInfo s : sub.includedServers()) {
            if (sub.pinnedName.equals(s.name)) {
                return;
            }
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("VpnController: subscription '" + sub.getDisplayName() + "': the picked server is gone, back to the fastest");
        }
        sub.pinnedName = null;
        sub.pinnedAtElapsed = 0;
    }

    /**
     * Picks one server of a subscription (submenu tap), or with null goes back to the fastest. If the
     * subscription is not in use, it becomes the current entry and the core starts on the pick.
     * If it runs, the core's selector switches in place, without a restart, and open connections
     * through the old server are closed so Telegram moves at once. With auto-switch on the pick
     * holds until the next health check (see runHealthCheck); with it off it stays.
     */
    public void pickSubscriptionServer(VpnSubscription sub, VpnKeyInfo server, String why) {
        if (server != null && sub.isExcluded(server)) {
            return;
        }
        sub.pinnedName = server == null ? null : server.name;
        sub.pinnedAtElapsed = server == null ? 0 : SystemClock.elapsedRealtime();
        save();
        VpnKeyInfo entry = entryOf(sub);
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("VpnController: subscription '" + sub.getDisplayName() + "': "
                    + (server == null ? "back to the fastest server" : "server '" + server.name + "' picked")
                    + " (" + why + ", auto-switch " + (autoSwitch ? "on: holds until the next check" : "off: stays") + ")");
        }
        if (entry == null) {
            notifyList();
            return;
        }
        if (entry != currentVpn || !isEnabled()) {
            selectVpn(entry);
            if (!isEnabled()) {
                setEnabled(true);
            }
            return; // the new core starts on the pick
        }
        consecutivePingFailures = 0; // another server now, the old one's failures do not count
        groupRetestRequested = false;
        if (SingBoxManager.getInstance().selectServer(sub.pinnedName)) {
            // A ping in flight measured the old server, and the switch closes its connection.
            pingToken++;
            VpnSubscription.GroupStatus status = sub.groupStatus;
            if (status != null) {
                // shown at once; the core's own event follows and replaces it
                status.pinned = sub.pinnedName != null;
                status.selectedName = status.pinned ? sub.pinnedName : status.fastestName;
            }
            currentVpn.ping = 0;
            AndroidUtilities.runOnUIThread(() -> measurePing(PING_CONNECT), PICK_PING_DELAY_MS);
            if (sub.pinnedName == null) {
                // the group may have stopped testing while a server was picked; fresh numbers now
                SingBoxManager.getInstance().requestUrlTest();
            }
        } else {
            String action = restartOrDefer("the server could not be switched in place");
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("VpnController: subscription '" + sub.getDisplayName() + "': " + action);
            }
        }
        if (sub.pinnedName != null && autoSwitch) {
            scheduleHealthCheck(); // re-arm: the pick holds one full interval
        }
        notifyList();
        notifyGroupStatus();
    }

    public void renameSubscription(VpnSubscription sub, String name) {
        sub.name = name == null ? "" : name.trim();
        save();
        notifyList();
    }

    /** Removes a subscription and its list entry. */
    public void deleteSubscription(VpnSubscription sub) {
        VpnKeyInfo entry = entryOf(sub);
        if (entry != null) {
            deleteVpn(entry);
        } else {
            sub.generation++;
            subscriptions.remove(sub);
            save();
            notifyList();
            scheduleNextSubscriptionCheck();
        }
    }

    /**
     * The running group's state, from the core's log client on a gomobile thread. Dropped when the
     * core that sent it is no longer the running one (a client of the previous core can still
     * deliver after the next one started).
     */
    static void onGroupStatus(SingBoxManager.Session session, VpnSubscription.GroupStatus status) {
        AndroidUtilities.runOnUIThread(() -> {
            VpnController controller = instance;
            if (controller == null || session.sub == null
                    || session != SingBoxManager.getInstance().getActiveSession()
                    || SingBoxManager.getInstance().getState() != SingBoxManager.STATE_CONNECTED
                    || !controller.subscriptions.contains(session.sub)) {
                return;
            }
            VpnSubscription.GroupStatus old = session.sub.groupStatus;
            if (old != null && old.tested) {
                status.tested = true; // a later round where nothing answered still counts as tested
            }
            session.sub.groupStatus = status;
            controller.reconcileSelector(session, status);
            if (BuildVars.LOGS_ENABLED && status.tested && status.selectedName != null
                    && (old == null || !status.selectedName.equals(old.selectedName))) {
                Integer delay = status.delays.get(status.selectedName);
                FileLog.d("VpnController: subscription '" + session.sub.getDisplayName() + "' now uses '" + status.selectedName
                        + "'" + (delay != null ? " (" + delay + " ms)" : "") + ", " + status.delays.size() + " of "
                        + session.tagNames.size() + " servers answered the last test");
            }
            controller.notifyGroupStatus();
        });
    }

    /**
     * Puts the core's selector on the app's choice when the two differ. The core keeps the last
     * selector choice in its cache file and on start that wins over the config's default, so a pick
     * made while the subscription was off could be ignored, or a pick that was already dropped could
     * come back on whatever server now has its old tag. A failed in-place switch ends up here too.
     * A few tries per core session, so a choice the core cannot take does not loop.
     */
    private void reconcileSelector(SingBoxManager.Session session, VpnSubscription.GroupStatus status) {
        VpnSubscription sub = session.sub;
        String want = sub.pinnedName != null && status.members.contains(sub.pinnedName) ? sub.pinnedName : null;
        boolean onWant = want == null ? !status.pinned : status.pinned && want.equals(status.selectedName);
        if (onWant || session.reconcileTries >= 3) {
            return;
        }
        session.reconcileTries++;
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("VpnController: subscription '" + sub.getDisplayName() + "': the core is on "
                    + (status.pinned ? "'" + status.selectedName + "'" : "the fastest server") + ", the app chose "
                    + (want != null ? "'" + want + "'" : "the fastest server") + " - switching (try " + session.reconcileTries + ")");
        }
        SingBoxManager.getInstance().selectServer(want);
    }

    /**
     * {@link #GROUP_FIRST_ROUND_MS} after a subscription's core started: its first round is over.
     * With no delay at all nothing answered (the core may have started before the network was up),
     * so ask for another round instead of waiting a whole interval on the first server.
     */
    private void checkGroupFirstRound() {
        SingBoxManager m = SingBoxManager.getInstance();
        SingBoxManager.Session session = m.getActiveSession();
        if (!isEnabled() || currentVpn.sub == null || m.getState() != SingBoxManager.STATE_CONNECTED
                || session == null || session.sub != currentVpn.sub) {
            return;
        }
        VpnSubscription sub = currentVpn.sub;
        VpnSubscription.GroupStatus status = sub.groupStatus;
        if (status == null) {
            status = new VpnSubscription.GroupStatus();
            status.members.addAll(session.tagNames.values());
            sub.groupStatus = status;
        }
        status.tested = true;
        if (status.delays.isEmpty()) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("VpnController: subscription '" + sub.getDisplayName() + "': no server answered the first test round, testing again");
            }
            m.requestUrlTest();
        }
        notifyGroupStatus();
    }

    private void notifyGroupStatus() {
        for (Listener l : new ArrayList<>(listeners)) {
            l.onVpnGroupStatusChanged();
        }
    }

    /** Milliseconds until a subscription should be downloaded again, 0 or less when it is due. */
    private static long msUntilDue(VpnSubscription s, long now) {
        if (s.fetching) {
            return SUBSCRIPTION_RETRY_MS[0];
        }
        if (s.failures > 0) {
            long since = now - s.lastAttemptWallMs;
            if (since < 0) {
                return 0; // the clock moved back
            }
            return SUBSCRIPTION_RETRY_MS[Math.min(s.failures, SUBSCRIPTION_RETRY_MS.length) - 1] - since;
        }
        long since = now - s.lastUpdateWallMs;
        if (since < 0) {
            return 0;
        }
        return SUBSCRIPTION_INTERVAL_MS - since;
    }

    private void checkSubscriptionsDue() {
        long now = System.currentTimeMillis();
        for (VpnSubscription s : new ArrayList<>(subscriptions)) {
            if (msUntilDue(s, now) <= 0) {
                refreshSubscription(s, false);
            }
        }
        scheduleNextSubscriptionCheck();
    }

    private void scheduleNextSubscriptionCheck() {
        long now = System.currentTimeMillis();
        long next = Long.MAX_VALUE;
        for (VpnSubscription s : subscriptions) {
            next = Math.min(next, msUntilDue(s, now));
        }
        scheduleSubscriptionCheck(next);
    }

    /**
     * A plain main-looper post, like the health tick: it does not fire in deep sleep, and catches up
     * when the device wakes. It also runs with auto-switch off, which the health tick does not.
     */
    private void scheduleSubscriptionCheck(long delayMs) {
        AndroidUtilities.cancelRunOnUIThread(subscriptionCheckRunnable);
        if (subscriptions.isEmpty()) {
            return;
        }
        AndroidUtilities.runOnUIThread(subscriptionCheckRunnable, Math.max(delayMs, SUBSCRIPTION_MIN_CHECK_MS));
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
