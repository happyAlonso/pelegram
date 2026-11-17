package org.telegram.messenger.vpn;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import libbox.CommandClient;
import libbox.CommandClientHandler;
import libbox.CommandClientOptions;
import libbox.ConnectionEvents;
import libbox.DnsQuery;
import libbox.Libbox;
import libbox.LogEntry;
import libbox.LogIterator;
import libbox.OutboundGroup;
import libbox.OutboundGroupItem;
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
    /** The core start this client belongs to; group events are tagged with it. */
    private final SingBoxManager.Session session;

    SingBoxLogClient(SingBoxManager.Session session) {
        this.session = session;
    }

    /** Connects on a background thread, retrying briefly until the command server is listening. */
    void start() {
        stopped = false;
        Thread t = new Thread(() -> {
            for (int i = 0; i < 20 && !stopped; i++) {
                try {
                    CommandClientOptions options = new CommandClientOptions();
                    options.addCommand(Libbox.CommandLog);
                    if (session.sub != null || session.home != null) {
                        // pushed by the core whenever a server's test result changes, no polling
                        options.addCommand(Libbox.CommandGroup);
                    }
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
            if (!stopped) {
                FileLog.e("singbox-core: log client could not connect, no core log"
                        + (session.sub != null ? " and no server status for this session" : ""));
            }
        }, "singbox-log");
        t.start();
    }

    boolean isConnected() {
        return client != null;
    }

    /** Points a selector group at one of its outbounds. Blocking; call off the UI thread. */
    boolean selectOutbound(String groupTag, String outboundTag) {
        CommandClient c = client;
        if (c == null) {
            FileLog.e("singbox-core: server not switched, the log client is not connected");
            return false;
        }
        try {
            c.selectOutbound(groupTag, outboundTag);
            FileLog.d("singbox-core: selector " + groupTag + " now on " + outboundTag);
            return true;
        } catch (Throwable e) {
            FileLog.e("singbox-core: switching the server failed: " + e);
            return false;
        }
    }

    /** Asks the core to test every member of the group now. Blocking; call off the UI thread. */
    boolean urlTest(String groupTag) {
        CommandClient c = client;
        if (c == null) {
            FileLog.d("singbox-core: server test not requested, the log client is not connected");
            return false;
        }
        try {
            c.urlTest(groupTag);
            FileLog.d("singbox-core: asked the group " + groupTag + " to test every server now");
            return true;
        } catch (Throwable e) {
            FileLog.e("singbox-core: server test request failed: " + e);
            return false;
        }
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

    /**
     * Logged (lower-cased) by pelegram's patch to the core's WireGuard device when data packets for
     * a session it never had keep arriving: a second client runs with the same private key, see
     * patches/wireguard-go-lx-foreign-session.patch.
     */
    private static final String KEY_IN_USE_ELSEWHERE = "another client is using the same private key";

    /** Log tag of the core's endpoints (box.go: "endpoint/" + type + "[" + tag + "]"). */
    private static final String ENDPOINT_TAG = "endpoint/";

    /**
     * wireguard-go names the peer in every line about it: "peer(AbCd…WxYz) - received handshake
     * response". The endpoint's other info lines are one per proxied connection and stay out.
     */
    private static final String PEER_PREFIX = "peer(";

    @Override
    public void writeLogs(LogIterator it) {
        try {
            while (it.hasNext()) {
                LogEntry e = it.next();
                String message = e.getMessage();
                // With logging on the core runs at debug (SingBoxConfigBuilder), and the WireGuard
                // device's per-peer lines (handshake sent / received / retried, keepalives) are
                // kept: without them a tunnel whose server never answers shows only dial timeouts.
                if (e.getLevel() >= LEVEL_INFO && !(BuildVars.LOGS_ENABLED && message != null
                        && message.contains(ENDPOINT_TAG) && message.contains(PEER_PREFIX))) {
                    continue;
                }
                FileLog.d("singbox-core: " + message);
                if (message != null && message.contains(KEY_IN_USE_ELSEWHERE)) {
                    VpnController.onKeyInUseElsewhere();
                }
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
    /**
     * The group's state from the core, on a gomobile thread. Copied into plain objects here (the
     * iterator is only valid during the call) and handed to the controller, which drops it if this
     * session is no longer the running one. A member whose last test failed has delay 0 and test
     * time 0, the same as one never tested; "tested" tells the two apart once any test is done.
     */
    @Override
    public void writeGroups(OutboundGroupIterator it) {
        try {
            VpnSubscription.GroupStatus status = new VpnSubscription.GroupStatus();
            status.members.addAll(session.tagNames.values());
            String selectorChoice = null;
            boolean seen = false;
            while (it != null && it.hasNext()) {
                OutboundGroup group = it.next();
                if (session.home != null && SingBoxConfigBuilder.TAG_STANDBY.equals(group.getTag())) {
                    // the standby servers of the subscription auto-switch left, tested only on request
                    VpnSubscription.GroupStatus standby = new VpnSubscription.GroupStatus();
                    standby.members.addAll(session.homeTagNames.values());
                    OutboundGroupItemIterator items = group.getItems();
                    while (items != null && items.hasNext()) {
                        OutboundGroupItem item = items.next();
                        String name = session.homeTagNames.get(item.getTag());
                        if (name != null && item.getURLTestDelay() > 0) {
                            standby.delays.put(name, item.getURLTestDelay());
                            standby.times.put(name, item.getURLTestTime());
                        }
                    }
                    VpnController.onStandbyStatus(session, standby);
                } else if (SingBoxConfigBuilder.TAG_PROXY.equals(group.getTag())) {
                    selectorChoice = group.getSelected(); // "auto" or a server tag
                    seen = true;
                } else if (SingBoxConfigBuilder.GROUP_AUTO_TAG.equals(group.getTag())) {
                    status.fastestName = session.tagNames.get(group.getSelected());
                    OutboundGroupItemIterator items = group.getItems();
                    while (items != null && items.hasNext()) {
                        OutboundGroupItem item = items.next();
                        String name = session.tagNames.get(item.getTag());
                        if (item.getURLTestTime() > 0) {
                            status.tested = true;
                        }
                        if (name != null && item.getURLTestDelay() > 0) {
                            status.delays.put(name, item.getURLTestDelay());
                        }
                    }
                    seen = true;
                }
            }
            if (!seen) {
                return;
            }
            status.pinned = selectorChoice != null && !SingBoxConfigBuilder.GROUP_AUTO_TAG.equals(selectorChoice);
            status.selectedName = status.pinned ? session.tagNames.get(selectorChoice) : status.fastestName;
            VpnController.onGroupStatus(session, status);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
    @Override public void writeOutbounds(OutboundGroupItemIterator message) {}
    @Override public void writeStatus(StatusMessage message) {}
    @Override public void writeDNSQuery(DnsQuery query) {}
}
