# Native core patches

Two independent changes are built into `TMessagesProj/libs/libbox-lx.aar`.

**Outline `prefix` disguise.** The Shadowsocks core is patched so that `ss://` keys apply
Outline's `prefix`: the Shadowsocks salt begins with attacker-supplied bytes shaped like a
TLS ClientHello, which gets the flow past DPI that fingerprints plain Shadowsocks. Stock
sing-box writes a fully random salt.

**Bounded tunnel connect.** The wireguard endpoint is patched to put a deadline on a TCP
connect made through the tunnel. See the file list below for why.

These patch files exist so the prebuilt AAR can be rebuilt from source. They are not
consumed by the Gradle build - the shipped artifact is the committed `.aar`.

## Which module (v1.0.3 lesson)

sing-box builds the outbound method for AEAD ciphers (`chacha20-ietf-poly1305`,
`aes-*-gcm`) from **`github.com/sagernet/sing-shadowsocks2`** - NOT the legacy
`sing-shadowsocks`. v1.0.3 patched the legacy module by mistake, so the `SetSaltPrefix`
type assertion in the outbound silently failed and the salt stayed random (verified by
capturing the client's first bytes on the wire). The patch must live in v2, and the
outbound now returns an error instead of silently skipping when the method can't take a
prefix.

## Files

- `sing-shadowsocks2-v0.2.1-salt-prefix.patch` - patches
  `github.com/sagernet/sing-shadowsocks2@v0.2.1` (`shadowaead/method.go`) with two things:
  - `Method.SetSaltPrefix([]byte)` - the client's `writeRequest` writes the prefix ahead of
    the random salt tail.
  - a `coalesceConn` wrapper (used only when a prefix is set) that holds the first writes for
    up to 10ms so the salt + address + first client payload ship in ONE TCP segment. Without
    it, sing-box sends a ~86-byte salt+address-only first packet, which is itself the
    Shadowsocks fingerprint DPI blocks - the prefix bytes are then irrelevant. This mirrors
    Outline's `ClientDataWait` (10ms). Verified on the wire: first data segment goes from
    86 bytes (header only) to ~1.7KB (header + payload), matching Outline.
- `sing-box-lx-shadowsocks-prefix.patch` - wires it into sing-box: a base64 `prefix`
  option on the shadowsocks outbound, decoded and pushed via `SetSaltPrefix` (erroring if
  unsupported), plus the local `replace` pointing at the patched v2 module.
- `sing-box-lx-wireguard-connect-timeout.patch` - patches `protocol/wireguard/endpoint.go`
  so a TCP connect through the tunnel carries a deadline. The dial runs inside gVisor
  (`DialTCPWithBind`), which reads its deadline from the caller's context, and no caller
  sets one. An address the VPN server cannot route to is therefore retried on gVisor's own
  SYN schedule until it exhausts, 2m7s later. A user log from 22-23 Aug 2026 recorded 1487
  of those against a single dead Telegram media address in 30 hours, 63 of them open at
  once, each one a stream of encrypted UDP retransmits holding the cellular radio out of
  its idle state. The patch reads `connect_timeout` from the endpoint options and applies
  it when the caller set no deadline of its own, defaulting to `C.TCPConnectTimeout` - the
  same bound the direct dialer already puts on a connect it owns. UDP has no connect and is
  left alone. `SingBoxConfigBuilder` emits `"connect_timeout": "10s"`, which sits above a
  real handshake (measured tunnel latency is 60-300 ms) and below tgnet's own 8-12 s socket
  timeout, so nothing is spent on a connection tgnet has already abandoned. Ships with
  `endpoint_connect_timeout_lx_test.go`, which covers the bound, the caller-deadline
  override and the UDP case.

## Rebuild the AAR

```sh
# 1. patched sing-shadowsocks2 (baseline v0.2.1)
git clone https://github.com/sagernet/sing-shadowsocks2 sing-shadowsocks2-lx
git -C sing-shadowsocks2-lx checkout v0.2.1
git -C sing-shadowsocks2-lx apply /path/to/patches/sing-shadowsocks2-v0.2.1-salt-prefix.patch

# 2. sing-box-lx (Leadaxe fork) next to it, with both sing-box patches
git clone https://github.com/Leadaxe/sing-box-lx
git -C sing-box-lx apply /path/to/patches/sing-box-lx-shadowsocks-prefix.patch
git -C sing-box-lx apply /path/to/patches/sing-box-lx-wireguard-connect-timeout.patch
# the shadowsocks patch's replace expects ../sing-shadowsocks2-lx relative to sing-box-lx

# 3. build (arm64) - GOFLAGS=-mod=mod so the local replace is honored
cd sing-box-lx
export ANDROID_HOME=... ANDROID_NDK_HOME=... GOFLAGS=-mod=mod
gomobile bind -target=android/arm64 -androidapi 21 -trimpath \
  -tags "with_gvisor,with_quic,with_wireguard,with_utls,badlinkname,tfogo_checklinkname0,with_xhttp,with_awg,with_lx_command,with_lx_idle_suspend" \
  -o TMessagesProj/libs/libbox-lx.aar ./experimental/libbox
```

## Verify the prefix is actually on the wire

A working connection is NOT proof - a Shadowsocks server accepts any salt, and an
un-DPI'd test host connects with or without the prefix. Point the outbound at a local
listener and confirm the first bytes equal the prefix:

```
prefix "FgMBAMKoAQE=" (base64) -> 16 03 01 00 c2 a8 01 01
first 8 bytes captured on the wire must equal those, followed by the random salt tail.
```

## Config shape

The Android parser (`SingBoxConfigBuilder.parseShadowsocks`) reads the Outline key's
`prefix` query param, URL-decodes the double-encoding to raw bytes, and emits:

```json
{ "type": "shadowsocks", "...": "...", "prefix": "<base64 of the raw prefix bytes>" }
```
