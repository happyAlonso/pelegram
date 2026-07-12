#include "Socks5UdpSocket.h"

#include <string.h>

#if defined(WEBRTC_POSIX)
#include <netinet/in.h>
#endif

#include "rtc_base/byte_order.h"
#include "rtc_base/ip_address.h"
#include "rtc_base/logging.h"
#include "rtc_base/network/received_packet.h"
#include "rtc_base/network/sent_packet.h"
#include "rtc_base/time_utils.h"

namespace rtc {

// static
Socks5UdpSocket* Socks5UdpSocket::Create(SocketFactory* factory,
                                         const SocketAddress& bind_address,
                                         const SocketAddress& proxy_address) {
  // The local socket talks only to the relay (sing-box, 127.0.0.1), so it takes the proxy's family;
  // the actual reflector destination (v4 or v6) is carried in the SOCKS5 UDP header per-datagram.
  Socket* udp = factory->CreateSocket(proxy_address.family(), SOCK_DGRAM);
  if (!udp) {
    return nullptr;
  }
  if (udp->Bind(SocketAddress(IPAddress(static_cast<uint32_t>(0)), 0)) < 0) {
    RTC_LOG(LS_ERROR) << "Socks5UdpSocket: UDP bind failed " << udp->GetError();
    delete udp;
    return nullptr;
  }
  Socket* control = factory->CreateSocket(proxy_address.family(), SOCK_STREAM);
  if (!control) {
    delete udp;
    return nullptr;
  }
  return new Socks5UdpSocket(udp, control, proxy_address);
}

Socks5UdpSocket::Socks5UdpSocket(Socket* udp,
                                 Socket* control,
                                 const SocketAddress& proxy_address)
    : udp_(udp), control_(control), proxy_address_(proxy_address) {
  udp_->SignalReadEvent.connect(this, &Socks5UdpSocket::OnUdpRead);
  control_->SignalConnectEvent.connect(this, &Socks5UdpSocket::OnControlConnect);
  control_->SignalReadEvent.connect(this, &Socks5UdpSocket::OnControlRead);
  control_->SignalCloseEvent.connect(this, &Socks5UdpSocket::OnControlClose);
  control_->Connect(proxy_address_);
}

Socks5UdpSocket::~Socks5UdpSocket() = default;

void Socks5UdpSocket::OnControlConnect(Socket* s) {
  // Greeting: version 5, one method, "no authentication".
  const uint8_t greeting[3] = {0x05, 0x01, 0x00};
  control_->Send(greeting, sizeof(greeting));
  phase_ = kGreeting;
}

void Socks5UdpSocket::OnControlRead(Socket* s) {
  uint8_t buf[512];
  int n = control_->Recv(buf, sizeof(buf), nullptr);
  if (n <= 0) {
    return;
  }
  control_in_.insert(control_in_.end(), buf, buf + n);

  if (phase_ == kGreeting) {
    if (control_in_.size() < 2) {
      return;
    }
    if (control_in_[0] != 0x05 || control_in_[1] != 0x00) {
      RTC_LOG(LS_ERROR) << "Socks5UdpSocket: greeting rejected";
      phase_ = kFailed;
      return;
    }
    control_in_.erase(control_in_.begin(), control_in_.begin() + 2);
    // UDP ASSOCIATE: ver=5, cmd=3, rsv=0, atyp=IPv4, 0.0.0.0:0 (let the proxy pick).
    const uint8_t req[10] = {0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0};
    control_->Send(req, sizeof(req));
    phase_ = kAssociating;
  }

  if (phase_ == kAssociating) {
    // Reply: ver, rep, rsv, atyp, bnd.addr, bnd.port.
    if (control_in_.size() < 4) {
      return;
    }
    if (control_in_[0] != 0x05 || control_in_[1] != 0x00) {
      RTC_LOG(LS_ERROR) << "Socks5UdpSocket: associate failed rep="
                        << (control_in_.size() > 1 ? control_in_[1] : 0xff);
      phase_ = kFailed;
      return;
    }
    uint8_t atyp = control_in_[3];
    IPAddress relay_ip;
    uint16_t relay_port;
    if (atyp == 0x01) {
      if (control_in_.size() < 4 + 4 + 2) {
        return;
      }
      relay_ip = IPAddress(GetBE32(&control_in_[4]));
      relay_port = GetBE16(&control_in_[8]);
    } else if (atyp == 0x04) {
      if (control_in_.size() < 4 + 16 + 2) {
        return;
      }
      in6_addr a6;
      memcpy(&a6, &control_in_[4], 16);
      relay_ip = IPAddress(a6);
      relay_port = GetBE16(&control_in_[20]);
    } else {
      phase_ = kFailed;
      return;
    }
    // A 0.0.0.0/:: relay address means "same host as the control connection".
    if (relay_ip.IsNil() || relay_ip.v4AddressAsHostOrderInteger() == 0) {
      relay_ip = proxy_address_.ipaddr();
    }
    relay_address_ = SocketAddress(relay_ip, relay_port);
    phase_ = kReady;
    RTC_LOG(LS_INFO) << "Socks5UdpSocket: associated, relay="
                     << relay_address_.ToString();
    SignalReadyToSend(this);
  }
}

void Socks5UdpSocket::OnControlClose(Socket* s, int err) {
  // The association only lives as long as the control connection.
  RTC_LOG(LS_INFO) << "Socks5UdpSocket: control closed err=" << err;
  phase_ = kFailed;
}

int Socks5UdpSocket::SendTo(const void* pv,
                            size_t cb,
                            const SocketAddress& addr,
                            const rtc::PacketOptions& options) {
  if (phase_ != kReady) {
    // Not associated yet - drop; ICE keeps retransmitting until we are ready.
    return static_cast<int>(cb);
  }

  std::vector<uint8_t> pkt;
  pkt.reserve(cb + 22);
  pkt.push_back(0x00);  // RSV
  pkt.push_back(0x00);
  pkt.push_back(0x00);  // FRAG
  if (addr.family() == AF_INET6) {
    pkt.push_back(0x04);
    in6_addr a6 = addr.ipaddr().ipv6_address();
    const uint8_t* b = reinterpret_cast<const uint8_t*>(&a6);
    pkt.insert(pkt.end(), b, b + 16);
  } else {
    pkt.push_back(0x01);
    uint32_t ip = addr.ipaddr().v4AddressAsHostOrderInteger();
    pkt.push_back((ip >> 24) & 0xff);
    pkt.push_back((ip >> 16) & 0xff);
    pkt.push_back((ip >> 8) & 0xff);
    pkt.push_back(ip & 0xff);
  }
  uint16_t port = addr.port();
  pkt.push_back((port >> 8) & 0xff);
  pkt.push_back(port & 0xff);
  const uint8_t* data = reinterpret_cast<const uint8_t*>(pv);
  pkt.insert(pkt.end(), data, data + cb);

  rtc::SentPacket sent_packet(options.packet_id, rtc::TimeMillis(),
                              options.info_signaled_after_sent);
  CopySocketInformationToPacketInfo(cb, *this, true, &sent_packet.info);
  int ret = udp_->SendTo(pkt.data(), pkt.size(), relay_address_);
  SignalSentPacket(this, sent_packet);
  return ret < 0 ? ret : static_cast<int>(cb);
}

int Socks5UdpSocket::Send(const void* pv,
                          size_t cb,
                          const rtc::PacketOptions& options) {
  // Not used: the call engine always sends with an explicit destination.
  SetError(ENOTCONN);
  return -1;
}

void Socks5UdpSocket::OnUdpRead(Socket* s) {
  uint8_t buf[65536];
  SocketAddress from;
  int64_t ts = 0;
  int n = udp_->RecvFrom(buf, sizeof(buf), &from, &ts);
  if (n < 4 + 4 + 2) {
    return;
  }
  if (buf[2] != 0x00) {
    // Fragmented datagrams are not supported (and Telegram never fragments).
    return;
  }
  uint8_t atyp = buf[3];
  size_t off;
  IPAddress src_ip;
  if (atyp == 0x01) {
    src_ip = IPAddress(GetBE32(&buf[4]));
    off = 8;
  } else if (atyp == 0x04) {
    if (n < 4 + 16 + 2) {
      return;
    }
    in6_addr a6;
    memcpy(&a6, &buf[4], 16);
    src_ip = IPAddress(a6);
    off = 20;
  } else {
    return;
  }
  if (static_cast<size_t>(n) < off + 2) {
    return;
  }
  uint16_t src_port = GetBE16(&buf[off]);
  off += 2;
  SocketAddress src(src_ip, src_port);
  NotifyPacketReceived(ReceivedPacket(
      rtc::ArrayView<const uint8_t>(buf + off, static_cast<size_t>(n) - off),
      src, webrtc::Timestamp::Micros(rtc::TimeMicros())));
}

SocketAddress Socks5UdpSocket::GetLocalAddress() const {
  return udp_->GetLocalAddress();
}

SocketAddress Socks5UdpSocket::GetRemoteAddress() const {
  return SocketAddress();
}

int Socks5UdpSocket::Close() {
  int a = udp_->Close();
  int b = control_->Close();
  phase_ = kFailed;
  return (a < 0 || b < 0) ? -1 : 0;
}

Socks5UdpSocket::State Socks5UdpSocket::GetState() const {
  return STATE_BOUND;
}

int Socks5UdpSocket::GetOption(Socket::Option opt, int* value) {
  return udp_->GetOption(opt, value);
}

int Socks5UdpSocket::SetOption(Socket::Option opt, int value) {
  return udp_->SetOption(opt, value);
}

int Socks5UdpSocket::GetError() const {
  return udp_->GetError();
}

void Socks5UdpSocket::SetError(int error) {
  udp_->SetError(error);
}

}  // namespace rtc
