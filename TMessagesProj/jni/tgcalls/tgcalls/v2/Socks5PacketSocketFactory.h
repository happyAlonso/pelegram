#ifndef TGCALLS_SOCKS5_PACKET_SOCKET_FACTORY_H_
#define TGCALLS_SOCKS5_PACKET_SOCKET_FACTORY_H_

#include <memory>
#include <string>

#include "api/packet_socket_factory.h"
#include "api/async_dns_resolver.h"
#include "rtc_base/socket_address.h"
#include "rtc_base/socket_factory.h"

#include "Socks5UdpSocket.h"

namespace tgcalls {

// pelegram: wraps a PacketSocketFactory so every UDP socket is relayed through the
// local SOCKS5 proxy via UDP ASSOCIATE. This is how a call reaches Telegram's
// reflectors (1:1, NativeNetworkingImpl) and group call servers (GroupNetworkManager)
// through the embedded VPN: they answer UDP, so we keep the media UDP and tunnel
// it, instead of a TCP CONNECT tunnel they ignore. TCP sockets and DNS go to the
// wrapped implementation, so a proxied caller must disable TCP candidates
class Socks5PacketSocketFactory : public rtc::PacketSocketFactory {
public:
    Socks5PacketSocketFactory(std::unique_ptr<rtc::PacketSocketFactory> impl, rtc::SocketFactory *socketFactory, rtc::SocketAddress proxyAddress) :
    _impl(std::move(impl)),
    _socketFactory(socketFactory),
    _proxyAddress(proxyAddress) {
    }

    virtual ~Socks5PacketSocketFactory() {
    }

    virtual rtc::AsyncPacketSocket *CreateUdpSocket(const rtc::SocketAddress& address, uint16_t min_port, uint16_t max_port) override {
        return rtc::Socks5UdpSocket::Create(_socketFactory, address, _proxyAddress);
    }

    virtual rtc::AsyncListenSocket *CreateServerTcpSocket(const rtc::SocketAddress &local_address, uint16_t min_port, uint16_t max_port, int opts) override {
        return _impl->CreateServerTcpSocket(local_address, min_port, max_port, opts);
    }

    virtual rtc::AsyncPacketSocket *CreateClientTcpSocket(const rtc::SocketAddress &local_address, const rtc::SocketAddress& remote_address, const rtc::ProxyInfo& proxy_info, const std::string &user_agent, const rtc::PacketSocketTcpOptions& tcp_options) override {
        return _impl->CreateClientTcpSocket(local_address, remote_address, proxy_info, user_agent, tcp_options);
    }

    virtual std::unique_ptr<webrtc::AsyncDnsResolverInterface> CreateAsyncDnsResolver() override {
        return _impl->CreateAsyncDnsResolver();
    }
private:
    std::unique_ptr<rtc::PacketSocketFactory> _impl;
    rtc::SocketFactory *_socketFactory = nullptr;
    rtc::SocketAddress _proxyAddress;
};

} // namespace tgcalls

#endif
