package org.telegram.messenger.vpn;

import android.content.Context;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import libbox.CommandServer;
import libbox.CommandServerHandler;
import libbox.Libbox;
import libbox.OverrideOptions;
import libbox.SetupOptions;
import libbox.SystemProxyStatus;

/**
 * Runs the embedded sing-box core in app-scoped SOCKS mode and points tgnet's proxy at it.
 *
 * Flow: build a config exposing a local SOCKS5 on 127.0.0.1:localPort -> start the core ->
 * ConnectionsManager.setProxySettings(true, <SOCKS5 127.0.0.1:localPort>). On stop, close
 * the core and clear the proxy. tgnet already speaks SOCKS5, so no native changes are involved.
 *
 * This is the integration scaffold - lifecycle + wiring are complete; on-device tuning (config
 * interop for vless-xhttp / AmneziaWG, and moving the URLTest health check onto the CommandClient)
 * is the next pass once it runs via adb.
 */
public class SingBoxManager implements CommandServerHandler {

    public static final int STATE_IDLE = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;
    public static final int STATE_ERROR = 3;

    public interface StateListener {
        void onSingBoxState(int state, String message);
    }

    private static volatile SingBoxManager instance;

    public static SingBoxManager getInstance() {
        SingBoxManager local = instance;
        if (local == null) {
            synchronized (SingBoxManager.class) {
                local = instance;
                if (local == null) {
                    local = instance = new SingBoxManager();
                }
            }
        }
        return local;
    }

    private static boolean libboxSetupDone;

    private final Object lock = new Object();
    private final ArrayList<StateListener> listeners = new ArrayList<>();

    private CommandServer commandServer;
    private volatile SingBoxLogClient logClient;
    private volatile int state = STATE_IDLE;
    private volatile int localPort = -1;

    public int getState() {
        return state;
    }

    public int getLocalPort() {
        return localPort;
    }

    public void addListener(StateListener l) {
        synchronized (listeners) {
            if (!listeners.contains(l)) listeners.add(l);
        }
    }

    public void removeListener(StateListener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    private void setState(int newState, String message) {
        state = newState;
        ArrayList<StateListener> copy;
        synchronized (listeners) {
            copy = new ArrayList<>(listeners);
        }
        for (StateListener l : copy) {
            try {
                l.onSingBoxState(newState, message);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Connects and disconnects run one at a time, in call order, on this thread. They used to get a
     * thread each, so two requests in a row (a row tap calls selectVpn and setEnabled, two quick taps
     * on different keys) raced: either could finish last, and the proxy port and CONNECTED state of
     * one start could be published after the next start had begun.
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "singbox-worker"));
    /** Numbers every connect/disconnect request; a queued request that is no longer the latest is skipped. */
    private final AtomicInteger requestSeq = new AtomicInteger();

    /** Builds the core config for the local port the core will listen on. */
    public interface ConfigSource {
        String build(int localPort) throws Exception;
    }

    /**
     * One core start. Events from the core (group status) carry the session they came from, so an
     * event from a core that was already replaced can be told apart and dropped.
     */
    public static final class Session {
        final ConfigSource source;
        /** The subscription this core runs as a group, null for a single key. */
        public final VpnSubscription sub;
        /** Group member tag -> server name, filled when the config is built. Empty for a single key. */
        public final Map<String, String> tagNames = new ConcurrentHashMap<>();
        /** [0]: servers left out of the group because their host had no address when it was built. */
        public final int[] unresolved = new int[1];
        /** UI thread: times VpnController moved the selector back to the app's choice in this session. */
        public int reconcileTries;

        Session(ConfigSource source, VpnSubscription sub) {
            this.source = source;
            this.sub = sub;
        }
    }

    private volatile Session activeSession;

    /** The session of the core that runs now, or null. */
    public Session getActiveSession() {
        return activeSession;
    }

    /** Connect using a pasted VPN key. Runs off the caller's thread; a later request supersedes it. */
    public void connect(final String rawKey) {
        connect(new Session(port -> SingBoxConfigBuilder.build(rawKey, port), null));
    }

    /** Run a subscription's servers as one urltest group, see SingBoxConfigBuilder.buildGroup. */
    public void connectSubscription(VpnSubscription sub, List<VpnKeyInfo> servers, int intervalSec, String pinnedName) {
        final List<VpnKeyInfo> snapshot = new ArrayList<>(servers);
        final Session[] holder = new Session[1];
        holder[0] = new Session(port -> SingBoxConfigBuilder.buildGroup(snapshot, port, intervalSec,
                holder[0].tagNames, holder[0].unresolved, pinnedName), sub);
        connect(holder[0]);
    }

    /**
     * Points the running subscription's selector at one server (by name) or, with null, back at the
     * fastest. No restart: only new connections move. False when it cannot be done in place (no
     * running subscription, server not in the group, log client not connected); the caller then
     * restarts the core, whose config starts on the same choice.
     */
    public boolean selectServer(String serverName) {
        final SingBoxLogClient client = logClient;
        Session session = activeSession;
        if (client == null || !client.isConnected() || session == null || session.sub == null) {
            return false;
        }
        String tag = SingBoxConfigBuilder.GROUP_AUTO_TAG;
        if (serverName != null) {
            tag = null;
            for (Map.Entry<String, String> e : session.tagNames.entrySet()) {
                if (serverName.equals(e.getValue())) {
                    tag = e.getKey();
                    break;
                }
            }
            if (tag == null) {
                return false;
            }
        }
        final String target = tag;
        new Thread(() -> client.selectOutbound(SingBoxConfigBuilder.TAG_PROXY, target), "singbox-select").start();
        return true;
    }

    private void connect(final Session session) {
        final int seq = requestSeq.incrementAndGet();
        worker.execute(() -> {
            if (seq != requestSeq.get()) {
                return; // a newer connect or disconnect is queued behind this one
            }
            try {
                startInternal(session);
            } catch (Throwable e) {
                FileLog.e(e);
                stopInternalQuietly();
                setState(STATE_ERROR, e.getMessage());
            }
        });
    }

    /**
     * Asks the running group to test every server now instead of at its next interval. Used when
     * the tunnel stops answering: with a long interval the group would otherwise keep a dead server
     * until a dial to it fails. No-op for a single key or before the log client is connected.
     */
    public void requestUrlTest() {
        final SingBoxLogClient client = logClient;
        Session session = activeSession;
        if (client == null || session == null || session.sub == null) {
            return;
        }
        new Thread(() -> client.urlTest(SingBoxConfigBuilder.GROUP_AUTO_TAG), "singbox-urltest").start();
    }

    /** Disconnect and clear the proxy. Runs off the caller's thread; a later request supersedes it. */
    public void disconnect() {
        final int seq = requestSeq.incrementAndGet();
        worker.execute(() -> {
            if (seq != requestSeq.get()) {
                return;
            }
            // IDLE first: its listener event then reaches the UI thread before any proxy-check failure
            // that the stop itself causes, so VpnController never books that failure on a live state.
            setState(STATE_IDLE, null);
            stopInternalQuietly();
        });
    }

    private void startInternal(Session session) throws Exception {
        synchronized (lock) {
            setState(STATE_CONNECTING, null);
            stopInternalQuietly();

            ensureSetup();

            int port = Libbox.availablePort(11890);
            String config = session.source.build(port);
            // Fail fast on a malformed config before touching the live proxy.
            Libbox.checkConfig(config);

            CommandServer server = new CommandServer(this, new SingBoxPlatformInterface());
            server.start();
            server.startOrReloadService(config, new OverrideOptions());

            commandServer = server;
            localPort = port;
            activeSession = session;

            // Mirror the sing-box core log stream into FileLog (so dial/reset/DNS lines are reachable
            // via the app's log export). Always on: the client also catches the core's shared-key
            // report (SingBoxLogClient.KEY_IN_USE_ELSEWHERE), which must work with logging off.
            // With logging off the core runs at warn, so the stream is only warnings and errors,
            // and FileLog.d drops them unwritten. For a subscription it also streams the group status.
            logClient = new SingBoxLogClient(session);
            logClient.start();
            if (session.sub != null) {
                FileLog.d("singbox: subscription '" + session.sub.getDisplayName() + "' runs as a group of "
                        + session.tagNames.size() + " servers"
                        + (session.unresolved[0] > 0 ? ", " + session.unresolved[0] + " left out: their host had no address" : ""));
            }
        }

        // Point tgnet at the local SOCKS5. Empty secret/user/pass = plain SOCKS5 path. Flag the
        // tunnel first: setProxySettings reconnects every datacenter, and those first dials must
        // already rotate addresses and keep the IPv4-only strategy (see ConnectionsManager).
        ConnectionsManager.setVpnTunnelActive(true);
        ConnectionsManager.setProxySettings(true, ProxySettings.builder()
                .setType(ProxySettings.Type.SOCKS5)
                .setAddress("127.0.0.1")
                .setPort(localPort)
                .build());
        setState(STATE_CONNECTED, null);
    }

    private void stopInternalQuietly() {
        synchronized (lock) {
            if (logClient != null) {
                logClient.stop();
                logClient = null;
            }
            if (commandServer != null) {
                try {
                    commandServer.closeService();
                } catch (Throwable ignored) {
                }
                try {
                    commandServer.close();
                } catch (Throwable ignored) {
                }
                commandServer = null;
            }
            localPort = -1;
            activeSession = null;
        }
        // Clear the proxy so Telegram goes direct once the tunnel is down.
        try {
            ConnectionsManager.setProxySettings(false, null);
            ConnectionsManager.setVpnTunnelActive(false);
        } catch (Throwable ignored) {
        }
    }

    private void ensureSetup() throws Exception {
        if (libboxSetupDone) return;
        Context ctx = ApplicationLoader.applicationContext;
        File base = new File(ctx.getFilesDir(), "singbox");
        File work = new File(base, "work");
        File tmp = new File(ctx.getCacheDir(), "singbox");
        base.mkdirs();
        work.mkdirs();
        tmp.mkdirs();

        SetupOptions options = new SetupOptions();
        options.setBasePath(base.getAbsolutePath());
        options.setWorkingPath(work.getAbsolutePath());
        options.setTempPath(tmp.getAbsolutePath());
        Libbox.setup(options);
        libboxSetupDone = true;
    }

    // ---- CommandServerHandler (core -> app callbacks) ----

    @Override
    public void serviceReload() {
    }

    @Override
    public void serviceStop() {
        disconnect();
    }

    @Override
    public void setSystemProxyEnabled(boolean enabled) {
    }

    @Override
    public SystemProxyStatus getSystemProxyStatus() {
        return null;
    }

    @Override
    public int connectSSHAgent() {
        return 0;
    }

    @Override
    public void triggerNativeCrash() {
    }

    @Override
    public void writeDebugMessage(String message) {
        // FileLog.d already gates on BuildVars.LOGS_ENABLED internally.
        FileLog.d("singbox: " + message);
    }
}
