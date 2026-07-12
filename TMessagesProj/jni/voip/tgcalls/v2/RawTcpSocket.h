/*
 *  Copyright 2004 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

#ifndef TG_CALLS_RAW_TCP_SOCKET_H_
#define TG_CALLS_RAW_TCP_SOCKET_H_

#include <stddef.h>

#include <cstdint>
#include <memory>
#include <vector>

#include "api/array_view.h"
#include "rtc_base/async_packet_socket.h"
#include "rtc_base/buffer.h"
#include "rtc_base/socket.h"
#include "rtc_base/socket_address.h"
#include "rtc_base/async_tcp_socket.h"

namespace rtc {

// AES-CTR keystream state for one direction of the MTProto obfuscated2 (TCPO2)
// transport. key/iv seed the cipher; ecount/num carry the running CTR position
// so the stream stays continuous across Send/Receive calls.
struct TCPO2State {
  uint8_t key[32] = {0};
  uint8_t iv[16] = {0};
  uint8_t ecount[16] = {0};
  uint32_t num = 0;
};

// Talks to a Telegram voip reflector over TCP using the MTProto obfuscated2
// transport (a 64-byte AES-keyed random handshake followed by AES-CTR-encrypted
// "abridged" framing). This is what the reflector actually expects on its TCP
// port; a plaintext framing gets no response. Mirrors libtgvoip's
// NetworkSocketTCPObfuscated so a modern (tgcalls v2) call can be relayed over
// TCP - which is what lets it traverse the embedded HTTP-CONNECT proxy.
class RawTcpSocket : public AsyncTCPSocketBase {
 public:
  // Binds and connects `socket` and creates RawTcpSocket for
  // it. Takes ownership of `socket`. Returns null if bind() or
  // connect() fail (`socket` is destroyed in that case).
  static RawTcpSocket* Create(Socket* socket,
                                const SocketAddress& bind_address,
                                const SocketAddress& remote_address);
  explicit RawTcpSocket(Socket* socket);
  ~RawTcpSocket() override {}

  RawTcpSocket(const RawTcpSocket&) = delete;
  RawTcpSocket& operator=(const RawTcpSocket&) = delete;

  int Send(const void* pv,
           size_t cb,
           const rtc::PacketOptions& options) override;
  size_t ProcessInput(rtc::ArrayView<const uint8_t>) override;

 private:
  // Fill `buffer` (64 bytes) with the obfuscated init header and derive the
  // send/recv keystream states from it.
  void GenerateObfuscatedInit(uint8_t* buffer);
  // AES-CTR process `len` bytes in place, advancing `state` (encrypt == decrypt).
  static void AesCtrProcess(uint8_t* data, size_t len, TCPO2State* state);

  bool obf_initialized_ = false;
  TCPO2State send_state_;
  TCPO2State recv_state_;
  // Decrypted bytes not yet split into complete abridged packets.
  std::vector<uint8_t> recv_plain_;
};

}  // namespace rtc

#endif  // TG_CALLS_RAW_TCP_SOCKET_H_
