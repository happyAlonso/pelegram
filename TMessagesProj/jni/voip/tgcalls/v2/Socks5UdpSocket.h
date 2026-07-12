// A UDP packet socket that relays every datagram through a SOCKS5 proxy using
// the UDP ASSOCIATE command. This lets the modern (tgcalls v2) call engine send
// its UDP media to Telegram reflectors through the embedded proxy (sing-box) -
// the reflectors answer UDP, unlike a TCP CONNECT tunnel which they ignore.
//
// One socket (one UDP association) relays to any number of destinations: the
// destination is carried per-datagram in the SOCKS5 UDP request header, matching
// how webrtc uses a single UDP socket to reach every relay/peer.

#ifndef TG_CALLS_SOCKS5_UDP_SOCKET_H_
#define TG_CALLS_SOCKS5_UDP_SOCKET_H_

#include <cstdint>
#include <memory>
#include <vector>

#include "rtc_base/async_packet_socket.h"
#include "rtc_base/socket.h"
#include "rtc_base/socket_address.h"
#include "rtc_base/socket_factory.h"

namespace rtc {

class Socks5UdpSocket : public AsyncPacketSocket {
 public:
  // Binds a local UDP socket and opens the SOCKS5 control connection to
  // `proxy_address`, issuing UDP ASSOCIATE. Returns null on immediate failure.
  static Socks5UdpSocket* Create(SocketFactory* factory,
                                 const SocketAddress& bind_address,
                                 const SocketAddress& proxy_address);

  ~Socks5UdpSocket() override;

  SocketAddress GetLocalAddress() const override;
  SocketAddress GetRemoteAddress() const override;
  int Send(const void* pv, size_t cb, const rtc::PacketOptions& options) override;
  int SendTo(const void* pv,
             size_t cb,
             const SocketAddress& addr,
             const rtc::PacketOptions& options) override;
  int Close() override;
  State GetState() const override;
  int GetOption(Socket::Option opt, int* value) override;
  int SetOption(Socket::Option opt, int value) override;
  int GetError() const override;
  void SetError(int error) override;

 private:
  enum Phase { kConnecting, kGreeting, kAssociating, kReady, kFailed };

  Socks5UdpSocket(Socket* udp, Socket* control, const SocketAddress& proxy_address);

  void OnControlConnect(Socket* s);
  void OnControlRead(Socket* s);
  void OnControlClose(Socket* s, int err);
  void OnUdpRead(Socket* s);

  std::unique_ptr<Socket> udp_;
  std::unique_ptr<Socket> control_;
  SocketAddress proxy_address_;
  SocketAddress relay_address_;
  Phase phase_ = kConnecting;
  std::vector<uint8_t> control_in_;
};

}  // namespace rtc

#endif  // TG_CALLS_SOCKS5_UDP_SOCKET_H_
