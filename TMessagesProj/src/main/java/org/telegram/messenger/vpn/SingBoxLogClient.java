package org.telegram.messenger.vpn;

import org.telegram.messenger.FileLog;

import libbox.CommandClient;
import libbox.CommandClientHandler;
import libbox.CommandClientOptions;
import libbox.ConnectionEvents;
import libbox.Libbox;
import libbox.LogEntry;
import libbox.LogIterator;
import libbox.OutboundGroupItemIterator;
import libbox.OutboundGroupIterator;
import libbox.StatusMessage;
import libbox.StringIterator;

/**
 * Subscribes to the sing-box core log stream (Libbox.CommandLog) and mirrors each line into
 * Telegram's FileLog, so the core connection logs (shadowsocks dial / reset / errors, DNS) show up
 * in the app's built-in log export. Diagnostic aid for the ss:// DPI issue - the core normally logs
 * to logcat, which the user cannot reach.
 */
final class SingBoxLogClient implements CommandClientHandler {
    private volatile CommandClient client;
    private volatile boolean stopped;

    /** Connects on a background thread, retrying briefly until the command server is listening. */
    void start() {
        stopped = false;
        Thread t = new Thread(() -> {
            for (int i = 0; i < 20 && !stopped; i++) {
                try {
                    CommandClientOptions options = new CommandClientOptions();
                    options.addCommand(Libbox.CommandLog);
                    options.setStatusInterval(2_000_000_000L);
                    CommandClient c = new CommandClient(this, options);
                    c.connect();
                    client = c;
                    FileLog.d("singbox-core: log client connected");
                    return;
                } catch (Throwable e) {
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }, "singbox-log");
        t.start();
    }

    void stop() {
        stopped = true;
        CommandClient c = client;
        client = null;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    // sing-box log levels (from sing/common/logger): 0 panic, 1 fatal, 2 error, 3 warn, 4 info,
    // 5 debug, 6 trace. Higher = noisier. The config's log.level=warn does NOT filter this
    // CommandLog stream (the subscription gets everything), so drop info/debug/trace here - those
    // were ~1 line per proxied connection and dominated the app log. Warn/error still come through.
    private static final int LEVEL_INFO = 4;

    @Override
    public void writeLogs(LogIterator it) {
        try {
            while (it.hasNext()) {
                LogEntry e = it.next();
                if (e.getLevel() >= LEVEL_INFO) {
                    continue;
                }
                FileLog.d("singbox-core: " + e.getMessage());
            }
        } catch (Throwable ignored) {
        }
    }

    @Override public void clearLogs() {}
    @Override public void connected() {}
    @Override public void disconnected(String message) {}
    @Override public void initializeClashMode(StringIterator modeList, String currentMode) {}
    @Override public void setDefaultLogLevel(int level) {}
    @Override public void updateClashMode(String newMode) {}
    @Override public void writeConnectionEvents(ConnectionEvents events) {}
    @Override public void writeGroups(OutboundGroupIterator message) {}
    @Override public void writeOutbounds(OutboundGroupItemIterator message) {}
    @Override public void writeStatus(StatusMessage message) {}
}
