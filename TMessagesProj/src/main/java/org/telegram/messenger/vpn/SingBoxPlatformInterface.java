package org.telegram.messenger.vpn;

import libbox.ConnectionOwner;
import libbox.InterfaceUpdateListener;
import libbox.NetworkInterfaceIterator;
import libbox.Notification;
import libbox.PlatformInterface;
import libbox.PlatformUser;
import libbox.ShellSession;
import libbox.StringIterator;
import libbox.TunOptions;
import libbox.WIFIState;

/**
 * Minimal libbox PlatformInterface for the app-scoped SOCKS mode.
 *
 * We run sing-box with a `socks` inbound only (no `tun` inbound), so openTun() is never
 * invoked and the OS-integration hooks below are not needed. The usePlatform*/useProcFS
 * toggles return false so the Go core does its own interface handling instead of calling
 * back into these stubs. If a future TUN (full-device) mode is added, this class is where
 * the VpnService fd + interface monitoring get implemented.
 */
public class SingBoxPlatformInterface implements PlatformInterface {

    @Override
    public boolean usePlatformAutoDetectInterfaceControl() {
        return false;
    }

    @Override
    public void autoDetectInterfaceControl(int fd) {
    }

    @Override
    public int openTun(TunOptions options) throws Exception {
        // No tun inbound in SOCKS mode. Reaching here means a tun was configured by mistake.
        throw new UnsupportedOperationException("openTun not supported in app-scoped SOCKS mode");
    }

    @Override
    public boolean usePlatformShell() {
        return false;
    }

    @Override
    public void checkPlatformShell() {
    }

    @Override
    public ShellSession openShellSession(PlatformUser user, String command, StringIterator environ, String term, int rows, int cols) throws Exception {
        throw new UnsupportedOperationException("shell not supported");
    }

    @Override
    public boolean useProcFS() {
        return false;
    }

    @Override
    public ConnectionOwner findConnectionOwner(int ipProtocol, String sourceAddress, int sourcePort, String destinationAddress, int destinationPort) throws Exception {
        throw new UnsupportedOperationException("process lookup not supported");
    }

    @Override
    public PlatformUser lookupUser(String username) throws Exception {
        throw new UnsupportedOperationException("user lookup not supported");
    }

    @Override
    public String lookupSFTPServer() throws Exception {
        return "";
    }

    @Override
    public String readSystemSSHHostKey() throws Exception {
        return "";
    }

    @Override
    public NetworkInterfaceIterator getInterfaces() throws Exception {
        throw new UnsupportedOperationException("getInterfaces not supported in SOCKS mode");
    }

    @Override
    public boolean includeAllNetworks() {
        return false;
    }

    @Override
    public boolean underNetworkExtension() {
        return false;
    }

    @Override
    public void registerMyInterface(String name) {
    }

    @Override
    public void startDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
    }

    @Override
    public void closeDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
    }

    @Override
    public void startNeighborMonitor(libbox.NeighborUpdateListener listener) {
    }

    @Override
    public void closeNeighborMonitor(libbox.NeighborUpdateListener listener) {
    }

    @Override
    public void clearDNSCache() {
    }

    @Override
    public libbox.LocalDNSTransport localDNSTransport() {
        return null;
    }

    @Override
    public WIFIState readWIFIState() {
        return null;
    }

    @Override
    public void sendNotification(Notification notification) {
    }

    @Override
    public String tailscaleHostname() {
        return "";
    }
}
