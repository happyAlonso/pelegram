package org.telegram.messenger.vpn;

import android.content.Context;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.ConnectionsManager;

import java.io.File;
import java.util.ArrayList;

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
 * ConnectionsManager.setProxySettings(true, "127.0.0.1", localPort, "", "", ""). On stop, close
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
    private SingBoxLogClient logClient;
    private volatile int state = STATE_IDLE;
    private volatile int localPort = -1;
    private String activeKey;

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

    /** Connect using a pasted VPN key. Runs off the caller's thread. */
    public void connect(final String rawKey) {
        Thread t = new Thread(() -> {
            try {
                startInternal(rawKey);
            } catch (Throwable e) {
                FileLog.e(e);
                stopInternalQuietly();
                setState(STATE_ERROR, e.getMessage());
            }
        }, "singbox-connect");
        t.start();
    }

    /** Disconnect and clear the proxy. Runs off the caller's thread. */
    public void disconnect() {
        Thread t = new Thread(() -> {
            stopInternalQuietly();
            setState(STATE_IDLE, null);
        }, "singbox-disconnect");
        t.start();
    }

    private void startInternal(String rawKey) throws Exception {
        synchronized (lock) {
            setState(STATE_CONNECTING, null);
            stopInternalQuietly();

            ensureSetup();

            int port = Libbox.availablePort(11890);
            String config = SingBoxConfigBuilder.build(rawKey, port);
            // Fail fast on a malformed config before touching the live proxy.
            Libbox.checkConfig(config);

            CommandServer server = new CommandServer(this, new SingBoxPlatformInterface());
            server.start();
            server.startOrReloadService(config, new OverrideOptions());

            commandServer = server;
            localPort = port;
            activeKey = rawKey;

            // Mirror the sing-box core log stream into FileLog (so dial/reset/DNS lines are reachable
            // via the app's log export) - only when logging is on, so normal runs pay nothing.
            if (BuildVars.LOGS_ENABLED) {
                logClient = new SingBoxLogClient();
                logClient.start();
            }
        }

        // Point tgnet at the local SOCKS5. Empty secret/user/pass = plain SOCKS5 path.
        ConnectionsManager.setProxySettings(true, "127.0.0.1", localPort, "", "", "");
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
            activeKey = null;
        }
        // Clear the proxy so Telegram goes direct once the tunnel is down.
        try {
            ConnectionsManager.setProxySettings(false, "", 1080, "", "", "");
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
