# Pelegram: embedded VPN transport (VLESS / AmneziaWG) instead of proxy

Plan for adding an in-app tunnel core so users paste a VPN key (vless://, hysteria2://,
AmneziaWG) and Telegram routes through it, replacing the MTProto/SOCKS5 proxy that TSPU now
kills on hostile carriers.

Base: fork of DrKLO/Telegram v12.8.1 (cloned here). License GPLv2 - the fork stays open source.

---

## 1. Core idea (why this is small)

Telegram's native networking (`jni/tgnet`) already speaks SOCKS5, including username/password
auth. We do not touch the C++ at all. We:

1. Embed a Go tunnel core (sing-box) compiled to an Android `.aar`.
2. The core runs a **local SOCKS5 inbound on `127.0.0.1:<port>`** and one outbound built from
   the user's pasted key.
3. On "connect", the app points Telegram's existing proxy at `127.0.0.1:<port>`.

So Telegram -> local sing-box -> (VLESS/Hysteria2/AmneziaWG) -> server -> Telegram DCs.

This is **app-scoped** (only Telegram's own traffic is tunneled). No `VpnService`, no TUN, no
extra Android permission. That is the right default for a Telegram client - see Option B for a
full-device VPN variant.

### The one hook, proven in code

`ConnectionsManager.setProxySettings(...)` pushes a proxy down to native; native does the SOCKS5
handshake. Connecting the tunnel is literally:

```java
// after sing-box reports the socks inbound is listening on localPort:
ConnectionsManager.setProxySettings(true, "127.0.0.1", localPort, "", "", "");
```

Relevant existing code (verified):

- `TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java:931`
  `public static void setProxySettings(boolean enabled, String address, int port, String username, String password, String secret)`
  -> `native_setProxySettings(...)` (`:981`).
- `TMessagesProj/jni/tgnet/ConnectionSocket.cpp:475` `openConnection(...)` connects via
  `proxyAddress:proxyPort`; the SOCKS5 state machine (`proxyAuthState` 1->2->5->6 for no-auth,
  plus the `0x02` user/pass branch) is in the same file. Empty `secret` selects the plain SOCKS5
  path (no MTProto/fake-TLS branch). 127.0.0.1 is handled by `inet_pton`.
- Full native chain: Java `native_setProxySettings` -> `jni/TgNetWrapper.cpp:226` (registered in
  the `JNINativeMethod[]` table at `:539`) -> `jni/tgnet/ConnectionsManager.cpp:3708`
  `ConnectionsManager::setProxySettings(...)` -> `ConnectionSocket.cpp`. Nothing here changes.
- `SharedConfig.ProxyInfo` (`SharedConfig.java:374`) holds `address, port, username, password,
  secret`; `proxyList`, `currentProxy`, `addProxy()` (`:1515`), `saveProxyList()`/`loadProxyList()`,
  and `isProxyEnabled()` (`:1529`) all live in the same file. Persistence = `mainconfig`
  SharedPreferences keys `proxy_ip/port/user/pass/secret` + boolean `proxy_enabled`.

Nothing in tgnet needs to change. All new work is Java/Kotlin + UI + the bundled core.

---

## 2. The core: sing-box (one lib covers every key type)

The user's keys span three protocols (from the tg_proxy infra):

| Key type | Example source | sing-box outbound |
|----------|----------------|-------------------|
| VLESS + REALITY + XHTTP (packet-up) | finn `2.26.66.220:443` (`#chsl-xhttp-1/2`) | `vless` |
| Hysteria2 (obfs salamander) | finn `2.26.66.220:8443/udp` | `hysteria2` |
| AmneziaWG | Amnezia servers | `wireguard` (AmneziaWG 2.0 params) |

sing-box handles all of them in a single gomobile AAR (`experimental/libbox`), with the local
SOCKS5 inbound and route -> outbound. One core, one config format (JSON).

**Fork choice:** upstream sing-box lacks full AmneziaWG 2.0 (Jc/Jmin/Jmax, S1-S4, H1-H4, I1-I5)
and XHTTP. Use a thin client fork that adds both behind build tags, kept rebaseable on upstream
tags (e.g. `sing-box-lx`). Verify at spike time; if the fork is unsuitable, fall back to upstream
sing-box + a separate `amneziawg-go` for the Amnezia keys.

**One compat risk to retire early:** the finn VLESS keys use Xray's `xhttp` + `mode=packet-up`.
sing-box's XHTTP dialect must interop with the Xray server. Mitigation: finn already exposes
**Hysteria2 on :8443**, which sing-box speaks natively - that path works out of the box, so the
feature is shippable even if XHTTP-packet-up needs server-side tweaks. Spike test #1 (below)
settles it in a day.

---

## 3. Config translation (pasted URI -> sing-box outbound JSON)

Users paste a share link. We parse it in Kotlin and build the sing-box JSON (a `socks` inbound +
one outbound + a route that sends everything to that outbound). This is the same approach
v2rayNG/Hiddify use. Schemes to support:

- `vless://uuid@host:port?type=xhttp&path=..&mode=packet-up&security=reality&pbk=..&fp=chrome&sni=..&sid=..#name`
- `hysteria2://auth@host:port?obfs=salamander&obfs-password=..&sni=..&insecure=1#name`
- AmneziaWG: the `[Interface]/[Peer]` `.conf` with the extra `Jc/Jmin/Jmax/S1/S2/H1..H4` lines,
  and/or Amnezia's `vpn://` base64 backup blob.

New class `SingBoxConfigBuilder` (Kotlin) with one parser per scheme -> a data model -> emit JSON.
Keep parsers small and unit-tested against the real finn/amnezia keys.

---

## 4. Build / toolchain work (PROVEN - both AARs built 2026-07-09)

Target ABI for now: **arm64-v8a only** (user decision). Env: `compileSdk 35`, `minSdk 21`,
`ndkVersion 27.2.12479018`, Java 17. Host Go is **1.26.4** and it builds fine - the sing-box
`go:linkname` hacks (`badlinkname`, `tfogo_checklinkname0`, `-ldflags -checklinkname=0`) compile
clean on 1.26, so no need to downgrade to the repo's pinned 1.24.7.

Installed toolchain: NDK 27.2.12479018 (via `sdkmanager`), and sing-box's **patched** gomobile
(`go install github.com/sagernet/gomobile/cmd/{gomobile,gobind}@v0.1.13`, i.e. `make lib_install`)
in `~/go/bin`. NOT the stock `golang.org/x/mobile` gomobile.

Build command (per core), arm64-only via direct `gomobile bind` (faster than the all-ABI make target):

```
export ANDROID_HOME=/home/perfecto/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018
gomobile bind -v -target=android/arm64 -androidapi 21 -trimpath \
  -tags "<TAGS>" -ldflags "-X github.com/sagernet/sing-box/constant.Version=<v> \
     -X internal/godebug.defaultGODEBUG=multipathtcp=0 -s -w -buildid= -checklinkname=0" \
  -o <out>.aar ./experimental/libbox
```

- **lx core** (`sing-box-lx`, covers VLESS+REALITY, Hysteria2, AmneziaWG 2.0): first
  `git submodule update --init --recursive submodules/wireguard-go` (the `Leadaxe/wireguard-go-awg2-lx`
  AWG2 fork - a shallow clone does NOT fetch it, and the build fails without it). Tags:
  `with_gvisor,with_quic,with_wireguard,with_utls,badlinkname,tfogo_checklinkname0,with_xhttp,with_awg,with_lx_command,with_lx_idle_suspend`.
- **upstream core** (`SagerNet/sing-box`, covers VLESS+REALITY, Hysteria2, stock WireGuard - no
  AWG2/XHTTP): no submodule needed. Tags:
  `with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,badlinkname,tfogo_checklinkname0`.

**Critical gotcha:** DROP `with_naive_outbound`. It links a prebuilt `cronet-go` static lib built
with a newer Clang emitting ARMv8.3 pointer-auth relocations (`reloc 315 = R_AARCH64_AUTH_ABS64`)
that NDK r27's `lld` rejects (`unknown relocation (315) against typeinfo ...`). naive is unused by
our key types, so dropping it costs nothing. Also drop `with_usbip`/`with_tailscale` (server/heavy,
unused).

Output: `libbox.aar` ~18 MB, contains `jni/arm64-v8a/libgojni.so` (~55 MB uncompressed) + a
`classes.jar` exposing the Java package **`libbox`** (`Libbox`, `BoxService`, `CommandClient`, ...).
Built artifacts live in `/home/projects/kotlin/pelegram/aars/{libbox-lx.aar,libbox-upstream.aar}`.

Integration: drop the chosen AAR in `TMessagesProj/libs/` (already holds `libgsaverification-client.aar`)
and add `implementation files('libs/libbox.aar')` to `TMessagesProj/build.gradle`. Build script is
`scratchpad/build_lx_arm64.sh` / `build_upstream_arm64.sh` - move into `Tools/` and pin commits for
the published fork. Add other ABIs later by switching `-target=android/arm64` to `android` (all) or
a comma list.

---

## 5. App code (new)

- `SingBoxManager` (Kotlin, foreground `Service`): start/stop the core, own its lifecycle, expose
  a `StateFlow` (idle / connecting / connected / error), surface the actual listen port. On
  "connected" -> `setProxySettings(true, 127.0.0.1, port, ...)`. On stop/disconnect -> restore the
  previously selected proxy (or none).
- `VpnKeyStore`: persist pasted keys next to the existing proxy list (reuse the `SharedConfig`
  persistence pattern; a VPN key is stored as its raw URI + display name + type).
- `SingBoxConfigBuilder`: section 3.
- Wire the core's own logging to a debug screen for field diagnosis under TSPU.

### Connection status / health (parity with proxies) - required

VPN keys must show whether they are working, exactly like the proxy list shows per-proxy status
(the checking spinner / green dot + ping ms / red unavailable). Two layers, both reuse existing
Telegram machinery:

1. **Live end-to-end health of the active key = free.** A connected key is a local SOCKS5 at
   `127.0.0.1:port`, so Telegram's existing `ConnectionsManager.checkProxy("127.0.0.1", port, ...)`
   (backed by `ProxyCheckInfo` + the `ping`/`checking`/`available` fields on `SharedConfig.ProxyInfo`,
   rendered by `ProxyListActivity`) measures the whole tunnel (Telegram -> core -> server -> DC) and
   drives the same green-dot + ping UI with no new UI code. The top-bar connection state
   (`ConnectionStateConnecting/ConnectingViaProxy/Connected`) also already reflects it.
2. **Per-key pre-check without disrupting the live connection = sing-box URLTest.** sing-box has a
   built-in `generate_204` latency probe per outbound (the lx fork extends it, SPEC 015/019
   URLTest command protocol). `SingBoxManager` runs it through a key's outbound and reports
   latency/up-down, so a saved-but-inactive key can show a ping before the user switches to it.

So a VPN key row renders identical status to a proxy row; layer 1 needs essentially no new code,
layer 2 is a thin call into the core's URLTest over the libbox CommandClient.

## 6. UI

Two options, pick per section 8 decision:

- **Minimal (recommended first):** reuse the proxy screens. `ProxyListActivity.java` (1127 lines)
  and `ProxySettingsActivity.java` (841 lines) already list/add/enable proxies. Add a "VPN key"
  entry type: a paste field + name, an enable toggle that starts `SingBoxManager` instead of
  setting a raw proxy. A VPN key shows in the same list with a distinct icon/subtitle.
- **Dedicated screen:** a new "VPN" row under Settings -> Data and Storage, with connect button,
  live state, ping, and a QR-scan/paste import. More polish; do after the minimal path works.

## 7. Deep-link import

`LaunchActivity.java:421` detects the `tg://proxy` / `tg://socks` scheme; the actual parse +
confirm bottom-sheet is `AndroidUtilities.isProxyLink()` / `handleProxyIntent()` (parse block
~`:4600`, `showProxyAlert()` ~`:4677`) reading `server/port/user/pass/secret` query params. Mirror
this for a one-tap import of vless/hysteria2/amneziawg share links (a custom scheme like
`pelegram://import?...`, or intercept `vless://`/`hysteria2://` opens): parse -> drop into
`VpnKeyStore` -> show a connect sheet reusing the `showProxyAlert` pattern. This gives the "one-tap
import link / subscription" the infra notes users need.

---

## 8. Open decisions

1. **Scope: app-only vs full-device VPN.**
   - **Option A (planned above):** app-scoped SOCKS. Simplest, no permission, only Telegram
     tunneled. Recommended - matches "use the vpn key instead of the proxy" exactly.
   - **Option B:** real `VpnService` + TUN via sing-box's `tun` inbound (all traffic, or per-app
     limited to the Telegram package). This is a genuine VPN. Adds a `VpnService` subclass, the
     system VPN consent dialog, an ongoing notification, and per-app routing. More work; do only if
     you want Pelegram to also protect non-Telegram traffic. Can be added later without redoing A.
2. **Core fork** (`sing-box-lx` vs upstream + `amneziawg-go`): settled by spike #1/#2.
3. **UI depth:** reuse proxy screens first, dedicated screen later.

## 9. Risks / caveats (from the TSPU findings)

- **XHTTP packet-up interop** with the Xray finn server - spike #1. Hysteria2 is the fallback path
  and already live.
- **Whitelist / mobile-shutdown mode:** during a regional whitelist shutdown, foreign IPs are
  unreachable at L3 and no VPN key helps. Keep a domestic-first-hop option (finn is foreign; a RU
  entry that tunnels onward, like the web2 pattern, survives better). Out of scope for v1 but note
  it in the UI copy.
- **AmneziaWG DDoS/fingerprinting** (RKN, mid-2026): AmneziaWG 2.0 junk/decoy params matter; make
  sure the fork exposes I1-I5.
- **GPLv2:** publish source; keep sing-box's license notices.
- **api_id/api_hash:** the fork needs its own from my.telegram.org (user is getting this). Until
  then, builds can use the placeholder in `BuildVars`.

---

## 10. Milestones

- **M0 - spike (1-2 days):** build `libbox.aar`; from a throwaway Android test, start sing-box with
  a socks inbound + the finn **Hysteria2** key; `curl`/tgnet through `127.0.0.1:port` reaches
  Telegram. Then repeat with the VLESS XHTTP key (retire the packet-up risk). Then an AmneziaWG key.
- **M1 - wiring:** `SingBoxManager` + `SingBoxConfigBuilder`; hardcode one key; toggle flips
  `setProxySettings`; Telegram connects through the tunnel. End-to-end on device.
- **M2 - UI + storage:** paste/import a key, save, enable/disable from the proxy list; state shown.
- **M3 - deep links + polish:** one-tap import, debug log screen, connection health, ABI split.
- **M4 - system VPN (optional, Option B).**

First concrete step after api_id: M0 spike #1 (Hysteria2 through an embedded sing-box socks inbound).
