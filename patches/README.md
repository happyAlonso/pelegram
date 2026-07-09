# Native core patches

The Shadowsocks core bundled in `TMessagesProj/libs/libbox-lx.aar` is patched so that
Outline (`ss://`) keys apply Outline's `prefix` disguise: the Shadowsocks salt begins
with attacker-supplied bytes shaped like a TLS ClientHello, which gets the flow past DPI
that fingerprints plain Shadowsocks. Stock sing-box writes a fully random salt.

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

- `sing-shadowsocks2-v0.2.1-salt-prefix.patch` - adds `Method.SetSaltPrefix([]byte)` to
  `github.com/sagernet/sing-shadowsocks2@v0.2.1` (`shadowaead/method.go`); the client's
  `writeRequest` writes the prefix ahead of the random salt tail.
- `sing-box-lx-shadowsocks-prefix.patch` - wires it into sing-box: a base64 `prefix`
  option on the shadowsocks outbound, decoded and pushed via `SetSaltPrefix` (erroring if
  unsupported), plus the local `replace` pointing at the patched v2 module.

## Rebuild the AAR

```sh
# 1. patched sing-shadowsocks2 (baseline v0.2.1)
git clone https://github.com/sagernet/sing-shadowsocks2 sing-shadowsocks2-lx
git -C sing-shadowsocks2-lx checkout v0.2.1
git -C sing-shadowsocks2-lx apply /path/to/patches/sing-shadowsocks2-v0.2.1-salt-prefix.patch

# 2. sing-box-lx (Leadaxe fork) next to it, with the wiring patch
git clone https://github.com/Leadaxe/sing-box-lx
git -C sing-box-lx apply /path/to/patches/sing-box-lx-shadowsocks-prefix.patch
# the patch's replace expects ../sing-shadowsocks2-lx relative to sing-box-lx

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
