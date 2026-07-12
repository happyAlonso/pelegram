/*
 *  Copyright 2004 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

#include "RawTcpSocket.h"

#include <stdint.h>
#include <string.h>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <memory>

#include <openssl/aes.h>
#include <openssl/rand.h>

#include "api/array_view.h"
#include "rtc_base/byte_order.h"
#include "rtc_base/checks.h"
#include "rtc_base/logging.h"
#include "rtc_base/network/sent_packet.h"
#include "rtc_base/time_utils.h"  // for TimeMillis

#if defined(WEBRTC_POSIX)
#include <errno.h>
#endif  // WEBRTC_POSIX

namespace rtc {

static const size_t kMaxPacketSize = 64 * 1024;

static const size_t kBufSize = kMaxPacketSize + 4;

// RawTcpSocket
// Binds and connects `socket` and creates RawTcpSocket for
// it. Takes ownership of `socket`. Returns null if bind() or
// connect() fail (`socket` is destroyed in that case).
RawTcpSocket* RawTcpSocket::Create(Socket* socket,
                                       const SocketAddress& bind_address,
                                       const SocketAddress& remote_address) {
  return new RawTcpSocket(
      AsyncTCPSocketBase::ConnectSocket(socket, bind_address, remote_address));
}

RawTcpSocket::RawTcpSocket(Socket* socket)
    : AsyncTCPSocketBase(socket, kBufSize) {}

void RawTcpSocket::AesCtrProcess(uint8_t* data, size_t len, TCPO2State* state) {
  AES_KEY aes;
  AES_set_encrypt_key(state->key, 256, &aes);
#ifdef OPENSSL_IS_BORINGSSL
  AES_ctr128_encrypt(data, data, len, &aes, state->iv, state->ecount,
                     &state->num);
#else
  unsigned int num = state->num;
  CRYPTO_ctr128_encrypt(data, data, len, &aes, state->iv, state->ecount, &num,
                        block128_f(AES_encrypt));
  state->num = num;
#endif
}

// Port of libtgvoip NetworkSocket::GenerateTCPO2States: build the 64-byte
// obfuscated init header and derive both keystream states from its random nonce.
void RawTcpSocket::GenerateObfuscatedInit(uint8_t* buffer) {
  uint8_t nonce[64];
  uint32_t* first = reinterpret_cast<uint32_t*>(nonce);
  uint32_t* second = first + 1;
  const uint32_t first1 = 0x44414548U, first2 = 0x54534f50U,
                 first3 = 0x20544547U, first4 = 0x20544547U,
                 first5 = 0xeeeeeeeeU;
  const uint32_t second1 = 0;
  do {
    RAND_bytes(nonce, sizeof(nonce));
  } while (*first == first1 || *first == first2 || *first == first3 ||
           *first == first4 || *first == first5 || *second == second1 ||
           nonce[0] == 0xef);

  // send keystream: key = nonce[8..40], iv = nonce[40..56]
  memcpy(send_state_.key, nonce + 8, 32);
  memcpy(send_state_.iv, nonce + 8 + 32, 16);
  send_state_.num = 0;
  memset(send_state_.ecount, 0, sizeof(send_state_.ecount));

  // recv keystream: from the reversed 48-byte tail
  uint8_t reversed[48];
  memcpy(reversed, nonce + 8, sizeof(reversed));
  std::reverse(reversed, reversed + sizeof(reversed));
  memcpy(recv_state_.key, reversed, 32);
  memcpy(recv_state_.iv, reversed + 32, 16);
  recv_state_.num = 0;
  memset(recv_state_.ecount, 0, sizeof(recv_state_.ecount));

  // protocol identifier at offset 56 (abridged: 0xefefefef)
  *reinterpret_cast<uint32_t*>(nonce + 56) = 0xefefefefU;
  memcpy(buffer, nonce, 56);
  AesCtrProcess(nonce, sizeof(nonce), &send_state_);
  memcpy(buffer + 56, nonce + 56, 8);
}

int RawTcpSocket::Send(const void* pv,
                         size_t cb,
                         const rtc::PacketOptions& options) {
  if (cb > kBufSize) {
    SetError(EMSGSIZE);
    return -1;
  }

  // If we are blocking on send, then silently drop this packet
  if (!IsOutBufferEmpty())
    return static_cast<int>(cb);

  // First send on the connection: emit the plaintext 64-byte obfuscated init
  // header, which seeds the keystreams. Everything after it is AES-CTR encrypted.
  if (!obf_initialized_) {
    obf_initialized_ = true;
    uint8_t init[64];
    GenerateObfuscatedInit(init);
    AppendToOutBuffer(init, sizeof(init));
  }

  // "Abridged" framing: length in 4-byte words. Reflector packets are already
  // padded to a multiple of 4 by ReflectorPort; round down defensively.
  size_t words = cb / 4;
  uint8_t hdr[4];
  size_t hdrLen = 0;
  if (words < 0x7f) {
    hdr[0] = static_cast<uint8_t>(words);
    hdrLen = 1;
  } else {
    hdr[0] = 0x7f;
    hdr[1] = static_cast<uint8_t>(words & 0xff);
    hdr[2] = static_cast<uint8_t>((words >> 8) & 0xff);
    hdr[3] = static_cast<uint8_t>((words >> 16) & 0xff);
    hdrLen = 4;
  }
  std::vector<uint8_t> frame(hdrLen + cb);
  memcpy(frame.data(), hdr, hdrLen);
  memcpy(frame.data() + hdrLen, pv, cb);
  AesCtrProcess(frame.data(), frame.size(), &send_state_);
  AppendToOutBuffer(frame.data(), frame.size());

  RTC_LOG(LS_WARNING) << "RawTcpSocket Send: " << cb << " bytes (obf) to " << GetRemoteAddress().ToString();
  int res = FlushOutBuffer();
  if (res <= 0) {
    // drop packet if we made no progress
    ClearOutBuffer();
    return res;
  }

  rtc::SentPacket sent_packet(options.packet_id, rtc::TimeMillis(),
                              options.info_signaled_after_sent);
  CopySocketInformationToPacketInfo(cb, *this, false, &sent_packet.info);
  SignalSentPacket(this, sent_packet);

  // We claim to have sent the whole thing, even if we only sent partial
  return static_cast<int>(cb);
}

size_t RawTcpSocket::ProcessInput(rtc::ArrayView<const uint8_t> data) {
  RTC_LOG(LS_WARNING) << "RawTcpSocket ProcessInput: got " << data.size() << " bytes (obf) from " << GetRemoteAddress().ToString();
  SocketAddress remote_addr(GetRemoteAddress());

  // AES-CTR is a continuous stream, so every ciphertext byte must be decrypted
  // exactly once and in order. Decrypt the freshly-arrived bytes, append them to
  // the plaintext buffer, and always report the whole input as consumed so the
  // base never redelivers (and thus never re-decrypts) them.
  size_t base = recv_plain_.size();
  recv_plain_.resize(base + data.size());
  memcpy(recv_plain_.data() + base, data.data(), data.size());
  AesCtrProcess(recv_plain_.data() + base, data.size(), &recv_state_);

  // Split complete abridged packets out of the plaintext buffer.
  size_t off = 0;
  while (true) {
    size_t avail = recv_plain_.size() - off;
    if (avail < 1)
      break;
    uint8_t b0 = recv_plain_[off];
    size_t hdrLen;
    size_t pktLen;
    if (b0 < 0x7f) {
      hdrLen = 1;
      pktLen = static_cast<size_t>(b0) * 4;
    } else {
      if (avail < 4)
        break;
      hdrLen = 4;
      pktLen = (static_cast<size_t>(recv_plain_[off + 1]) |
                (static_cast<size_t>(recv_plain_[off + 2]) << 8) |
                (static_cast<size_t>(recv_plain_[off + 3]) << 16)) * 4;
    }
    if (pktLen > kMaxPacketSize) {
      // Desynced/garbage - drop everything to avoid runaway.
      RTC_LOG(LS_WARNING) << "RawTcpSocket: bogus obf packet len " << pktLen;
      recv_plain_.clear();
      return data.size();
    }
    if (avail < hdrLen + pktLen)
      break;

    rtc::ReceivedPacket received_packet(
        rtc::ArrayView<const uint8_t>(recv_plain_.data() + off + hdrLen, pktLen),
        remote_addr, webrtc::Timestamp::Micros(rtc::TimeMicros()));
    NotifyPacketReceived(received_packet);
    off += hdrLen + pktLen;
  }

  if (off > 0) {
    recv_plain_.erase(recv_plain_.begin(), recv_plain_.begin() + off);
  }
  return data.size();
}

}  // namespace rtc
