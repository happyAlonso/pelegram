# Native core patches

The Shadowsocks core bundled in `TMessagesProj/libs/libbox-lx.aar` is patched so that
Outline (`ss://`) keys apply Outline's `prefix` disguise: the Shadowsocks salt begins
with attacker-supplied bytes shaped like a TLS ClientHello, which gets the flow past DPI
that fingerprints plain Shadowsocks. Stock sing-box writes a fully random salt.

These patch files exist so the prebuilt AAR can be rebuilt from source. They are not
consumed by the Gradle build - the shipped artifact is the committed `.aar`.

## Files

- `sing-shadowsocks-v0.2.8-salt-prefix.patch` - adds `Method.SetSaltPrefix([]byte)` to
  `github.com/sagernet/sing-shadowsocks@v0.2.8` (`shadowaead/protocol.go`); the client
  writes the prefix ahead of the random salt.
- `sing-box-lx-shadowsocks-prefix.patch` - wires it into sing-box: a `prefix` option
  (base64) on the shadowsocks outbound, decoded and pushed via `SetSaltPrefix`, plus the
  local `replace` directive pointing at the patched module.

## Rebuild the AAR

```sh
# 1. patched sing-shadowsocks (baseline v0.2.8)
git clone https://github.com/sagernet/sing-shadowsocks sing-shadowsocks-lx
git -C sing-shadowsocks-lx checkout v0.2.8
git -C sing-shadowsocks-lx apply /path/to/patches/sing-shadowsocks-v0.2.8-salt-prefix.patch

# 2. sing-box-lx (Leadaxe fork) next to it, with the wiring patch
git clone https://github.com/Leadaxe/sing-box-lx
git -C sing-box-lx apply /path/to/patches/sing-box-lx-shadowsocks-prefix.patch
# the patch's replace expects ../sing-shadowsocks-lx relative to sing-box-lx

# 3. build (arm64) - GOFLAGS=-mod=mod so the local replace is honored
cd sing-box-lx
export ANDROID_HOME=... ANDROID_NDK_HOME=... GOFLAGS=-mod=mod
gomobile bind -target=android/arm64 -androidapi 21 -trimpath \
  -tags "with_gvisor,with_quic,with_wireguard,with_utls,badlinkname,tfogo_checklinkname0,with_xhttp,with_awg,with_lx_command,with_lx_idle_suspend" \
  -o TMessagesProj/libs/libbox-lx.aar ./experimental/libbox
```

## Config shape

The Android parser (`SingBoxConfigBuilder.parseShadowsocks`) reads the Outline key's
`prefix` query param, URL-decodes the double-encoding to raw bytes, and emits:

```json
{ "type": "shadowsocks", "...": "...", "prefix": "<base64 of the raw prefix bytes>" }
```
