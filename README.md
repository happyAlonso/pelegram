# pelegram

pelegram is a fork of the [Telegram App for Android](https://github.com/DrKLO/Telegram) that adds
an embedded VPN transport, so you can keep Telegram working on networks where deep packet
inspection (DPI) blocks the standard MTProto and SOCKS5 proxies.

Instead of entering a proxy, you paste a modern VPN key (VLESS, Hysteria2, or AmneziaWG). The app
runs the tunnel itself and routes Telegram through it. Everything else about the client is stock
Telegram.

## Why

On some networks the censor now fingerprints and blocks the proxy protocols Telegram ships with:
the MTProto handshake gets throttled by its shape, and SOCKS5 is dropped outright. Obfuscated
transports that look like ordinary TLS or QUIC (VLESS+REALITY, Hysteria2) or obfuscated WireGuard
(AmneziaWG) still get through. pelegram brings one of those tunnels inside the app so you do not
need a separate VPN client just to use Telegram.

## Features

- **Paste a VPN key instead of a proxy.** Supported key types:
  - `vless://` - VLESS, including REALITY and XHTTP
  - `hysteria2://` - Hysteria2 (QUIC, with Salamander obfuscation)
  - `ss://` - Shadowsocks / Outline (applies Outline's `prefix` disguise)
  - AmneziaWG 2.0 - paste the `vpn://...` key exported by the **AmneziaVPN** app (the official Amnezia client), or a raw `awg-quick` `[Interface]` / `[Peer]` config

  Or tap the QR icon on the Add VPN screen to scan a key from a QR code.
- **App-scoped.** Only Telegram's own traffic goes through the tunnel. There is no system-wide VPN,
  no `VpnService`, and no extra Android permission. The core exposes a local SOCKS5 endpoint and the
  app points Telegram's existing proxy layer at it.
- **Connection status, like proxies.** A VPN key shows whether it is working and its latency, the
  same way the proxy list does.
- **Calls and the in-app browser go through the tunnel too.** 1:1 calls (both directions, including
  with stock Telegram users) and the in-app browser / web views route through the VPN, with DNS
  resolved on the far side of the tunnel.
- Powered by an embedded [sing-box](https://sing-box.sagernet.org/) core.

## How to use

1. Open **Settings -> Data and Storage -> Proxy** (the VPN keys live alongside proxies).
2. Add a key: paste your `vless://` / `hysteria2://` link, or - for AmneziaWG - the `vpn://...` key from the **AmneziaVPN** app.
3. Enable it. The app starts the tunnel and routes Telegram through it. The row shows connecting,
   then a latency once it is up.
4. To stop, disable the key. Telegram goes back to a direct connection.

You can also open a supported share link to import a key in one tap.

### Key format examples

```
vless://<uuid>@<host>:<port>?type=xhttp&security=reality&pbk=<pubkey>&fp=chrome&sni=<sni>&sid=<shortid>#name
hysteria2://<auth>@<host>:<port>?obfs=salamander&obfs-password=<pw>&sni=<sni>&insecure=1#name
ss://<base64(method:password)>@<host>:<port>#name
```

**Shadowsocks / Outline:** paste the `ss://` access key. pelegram applies Outline's `prefix`
disguise - the Shadowsocks salt is shaped so the first bytes on the wire look like a TLS
ClientHello - so Outline keys get through DPI that blocks plain Shadowsocks. (The Shadowsocks core
is patched for this; a standard sing-box build would send a random salt and be blocked.)

**AmneziaWG** connections are added from the **AmneziaVPN** app - the official Amnezia client
(https://amnezia.org), not "AmneziaWG", "Amnezia WG" or any other app. In AmneziaVPN, share/export
the connection and paste the resulting `vpn://...` key into pelegram. A raw `awg-quick`
`[Interface]` / `[Peer]` config (with the `Jc/Jmin/Jmax`, `S1..S4`, `H1..H4` fields) also works.

## Status

- Based on official Telegram **12.10.1** (TL layer 229), kept current with periodic rebases onto upstream.
- Embedded sing-box core, key parsing, the key management UI, and connect/disconnect wiring are
  integrated and shipping.
- Calls, the in-app browser, auto-switch between keys, reconnect throttling, background push, and
  boot autostart are in place.
- Current builds target **arm64-v8a** only.

## Notes on design

- **Background auto-switch is driven by tgnet's traffic, not by a timer** (1.2.5). 1.2.4 floored
  background health checks at 5 minutes to stop them pinning the radio, which meant a server dying with
  the app closed took 15-40 minutes to be switched away from - a real user log showed 40 minutes with no
  working tunnel overnight. The fix is to stop asking on a schedule: `ConnectionsManager.onBytesReceived`
  sets a flag on `VpnController`, and the background tick (60s) just reads it. Bytes arriving means they
  came through the tunnel, so the server is alive - proven for free, and more truthfully than a ping,
  which can time out on a server that is carrying real traffic. Only a tunnel that is completely silent
  for 2 consecutive ticks *while the device is awake* gets a real ping, then up to three, then a switch:
  ~2-4 minutes. A healthy backgrounded client now spends zero pings. Deep sleep needs no handling because
  the ticks are main-looper posts and stop with the device. Note tgnet's *connection state* is not usable
  for this - `ConnectionStateConnectingToProxy` is also its normal backgrounded resting state (390 flips
  in 40h on a perfectly healthy tunnel), so it cannot tell asleep from broken.

## Todo / known issues

- **Battery drain compared to the official client.** Three causes, two of them fixed. (a) *Fixed in 1.2.4:* with
  auto-switch on, the health check re-pinged the current connection every 10s by default, screen off
  or not. Each check is a `ConnectionsManager.checkProxy`, which suspends and re-dials the proxy
  connection, so sing-box opened a fresh outbound to the VPN server every time. That pinned the
  cellular radio in its high-power state (RRC inactivity timers run 10-20s) and, worse, reset tgnet's
  background sleep timer - a proxy connection coming up sets `lastPauseTime` in
  `onConnectionConnected`, so the next `select()` pass takes the "resume network and timers" branch
  and the poll interval drops from ~3min back to 1s. With the sleep timer itself at 10s
  (`CONNECTION_BACKGROUND_KEEP_TIME`) the app could never stay asleep. Now the user's interval applies
  only in the foreground, the ladder is 30s/1m/2m/5m defaulting to 2m, and backgrounded the app does
  not ping on a timer at all - see the auto-switch note below. (b) *Fixed in 1.3.1:* an address the VPN
  server could not route to cost 2m7s per attempt instead of failing fast. The wireguard endpoint dials
  inside its own userspace network stack, which reads the deadline from its caller, and no caller set
  one, so each dial ran until that stack's SYN schedule exhausted. A user log recorded 1487 of them
  against a single dead Telegram media address in 30 hours, 63 open at once, at least one open for 28%
  of the whole window - each a stream of encrypted UDP retransmits holding the cellular radio out of
  its idle state. Two changes. The generated config now sets `connect_timeout` to 10s on the endpoint,
  and the core is patched to apply it to the dial through the tunnel (`patches/`); 10s sits above a
  real handshake and below tgnet's own 8-12s socket timeout, so nothing is spent on a connection tgnet
  has already given up on. tgnet now also counts consecutive failed connects the way it already counted
  connected-but-silent drops, and backs the re-dial off 1s, 2s, 4s and so on to 30s from the third
  failure, so a datacenter that comes back is still retried within 30s. (c) *Structural, not fixable:*
  the fork can't use FCM, so it force-enables tgnet's push
  connection and runs a foreground service whenever the VPN is on. The official client shares one FCM
  socket across the whole device and lets the process be frozen.
- **The VPN overwrites the user's "Background Connection" setting.** `updateBackgroundKeepAlive()`
  writes `pushConnection` on every VPN enable/disable/select, so a user who deliberately turns
  Background Connection off in Notifications settings gets it silently turned back on. The fork does
  need that connection while the VPN is on (no FCM), but it should not clobber an explicit user
  choice, and it never restores the previous value when the VPN goes off.
- **Out of memory on a media-heavy channel over a slow VPN link.** Opening a channel with a lot of
  photos/videos while the tunnel is running slowly could exhaust the app's heap and restart it:
  downloads stall and pile up faster than they complete and free their buffers. Mitigated in 1.2.3 by
  `MemoryGuard`, which caps how many file-load operations run in parallel while the tunnel carries the
  traffic (and drops to one under pressure), sheds the image caches on Android's `onTrimMemory` /
  `onLowMemory` signals, and writes an OOM report - heap and native usage, tunnel state, the download
  queue depth and the stack - into the logs directory so it ships with **Send Logs**. With logging
  enabled it also writes an `.hprof` heap dump to `files/oom/`. That dump is what closed the case.
  *Root cause found and fixed in 1.3.2:* a heap dump from a 1.3.1 install 75 hours up (the one behind
  the "chat opens slowly" complaint) held 63 dead `LaunchActivity` instances, 265 MB of the 417 MB
  reachable heap, every one pinned through the global `NotificationCenter` by an upstream one-line
  bug: `ChatBackgroundDrawable.onDetachedFromWindow` inverts its `contains` check, never removes the
  view, and so never lets its `ImageReceiver` drop its three observers. A second, smaller leak kept
  dead `ChatMessageCell`s through animated-emoji holders in the static `globalEmojiCache` - released
  now on detach and on recycle. The inverted check is still present in upstream DrKLO master.
  `MemoryGuard`'s caps stay: the slow-tunnel pileup they were built for is real regardless, and the
  freeze-then-restart the leak produced (GC storms once the heap ceiling is near) is exactly what the
  reporter felt as a chat that would not open.
- **The tunnel MTU is too large for some links.** *Fixed in 1.3.3.* An earlier log carried 853
  `sendmsg: message too long` from the AmneziaWG socket, 776 of them inside one hour: `EMSGSIZE`, the
  encapsulated packet bigger than the path underneath it, dropped before it left the phone. The
  other direction was measured on the VPN server on 2026-09-03 against a live client whose media had
  stopped loading. Pinged through the tunnel, packets of 1268 bytes and below arrived, packets of
  1288, 1300, 1328 and 1420 bytes were lost 10 times out of 10, and packets above the server's own
  1420-byte tunnel MTU arrived again, because the kernel fragments those into smaller pieces. So
  that path silently drops any single packet between roughly 1280 and 1420 bytes; two other clients
  on the same server were clean, which is why the fault followed one user and one network. At the
  usual tunnel MTU of 1280 every full-size TCP segment is exactly 1280 bytes and lands in that hole,
  so messages (small packets) keep flowing while a media download receives nothing, times out, and
  is retried into the same hole. Android's kernel would probe its way below a black hole like this;
  the userspace TCP stack inside the tunnel does not, which is why a system-wide VPN worked when the
  app tunnel did not. `SingBoxConfigBuilder` now caps the wireguard/AmneziaWG MTU at 1200 whatever
  the key asks for (an AmneziaVPN `vpn://` key carries 1280 in `last_config`), which keeps every
  packet below the measured cliff at a cost of about 6% more packets per megabyte. The server side
  is the other half: its tunnel interface runs at MTU 1420 with no TCP MSS clamping, so clients of
  the official Amnezia app on the same network hit the same hole. Lowering that MTU or clamping MSS
  there is an operator change, not an app change.
- **Media that never loads while messages keep flowing.** *Fixed in 1.3.3.* A user log from
  2026-09-02 caught it: a 6.3 MB video from DC2 had its eight chunk requests re-sent until tgnet's
  `RETRY_LIMIT` (ten reconnects without a byte back) while requests on the generic connection answered
  in 80 ms and the proxy ping was 92 ms. Switching servers did not help; killing the app did, and so
  did a system-wide VPN, which users had found on their own. Two things in tgnet turned one unreachable
  media address into a download that never finished. With any proxy configured, `Connection::connect`
  marks every dial static, and `Datacenter::getCurrentAddress` then returns the same address each time
  no matter how the rotation counters move, so an address the VPN server cannot reach is dialed again
  after every 12 s timeout, only the port changing. That is right for an MTProto proxy, which picks
  the endpoint itself; through the app tunnel the server dials exactly what it is handed. And after
  one full pass over a datacenter's list, `Connection::onDisconnectedInternal` widens the strategy from
  `USE_IPV4_ONLY` to `USE_IPV4_IPV6_RANDOM` by itself; the family it then picks is kept while any
  connection receives data, and Java re-pushes IPv4-only only on a network or proxy change, which is
  exactly what reconnecting the VPN does. That is the earlier "connections still go to IPv6
  datacenters" item (257 dials to `2001:67c:4e8:f002::a` in a 1.1.7 log), now with its line: the pick
  never consulted `getIpStrategy()`. 1.3.3 tells the core when the app tunnel is the proxy
  (`ConnectionsManager.setVpnTunnelActive`): the strategy widening is skipped, media connections
  rotate addresses on failure with one attempt per address instead of four ports each, and the
  generated sing-box route rejects IPv6 destinations while the tunnel carries only IPv4, so a stray
  IPv6 dial fails in milliseconds instead of holding a connection for 12 s ten times over. Which
  address was dead stays unproven: the native tgnet log is off in release builds, and the VPN server's
  own route to that media address is the other candidate, which the rotation now covers as well.
- **A download that gives up takes its queue slot with it.** *Fixed in 1.3.3.* When tgnet exhausts
  its retries on a chunk request it returns `RETRY_LIMIT`, and the file loader restarts the same
  operation at once, against the same datacenter session that has already swallowed ten requests.
  Nothing about that session has changed, so the eleventh request is answered by the same silence,
  and the operation loops. A queue runs only a few operations at a time (fewer while the tunnel is
  up, see `MemoryGuard`), so two or three of these at the head of a queue stop every other download
  behind them: a user log caught a chat opening with five photos, all of them parked at queue
  positions 4 and above, no chunk request sent in seventy seconds. The loop is what makes a
  transient network fault look permanent, and it is why turning the VPN off and on cures it - that
  suspends every connection and starts fresh sessions. `RETRY_LIMIT` now does the same thing for the
  one datacenter that gave up (`ConnectionsManager.resetDownloadSessions`), leaving the generic
  connection carrying messages untouched.
- **Auto-switch cannot see a server whose downloads are dead.** *Fixed in 1.3.3.* The health check
  measures a proxy connection and messages travel on the generic connection, so a server that
  answers both while dropping every file chunk is healthy by every measure the app had. That is the
  "media doesn't load but chats work" report exactly, and auto-switch sat it out. Downloads giving
  up now count towards a switch the way failed pings do: three within five minutes with the tunnel
  connected rolls over to the next server, and any file that finishes clears the count.
- **The stories preloader re-runs constantly.** 11104 of 12498 file-load operations in that log were
  stories, covering 4803 distinct files; one 14.5 MB video was queued 214 times. The repeats are cheap
  because the file is already cached, so each one finishes in about 3 ms and sends nothing over the
  network. The trigger is leaving a mini-app or web view, which brings the dialog list back and makes
  the stories bar preload again. This is upstream behaviour and not a battery problem, but it dominates
  the log and hides real download activity while reading one.

## Building from source

Requirements: Android SDK (compile SDK 36), NDK `27.2.12479018`, JDK 17, and Go (for building the
tunnel core). See `VPN_FEATURE_PLAN.md` for the full design and integration notes.

1. **Fetch the submodules.** Upstream keeps ffmpeg, dav1d, libvpx, libyuv, openh264, ogg/opus/opusfile,
   tlottie and jlatexmath as git submodules:
   ```
   git submodule update --init --recursive --depth=1
   ```
   The prebuilt static libraries under `TMessagesProj/jni/ffmpeg/<abi>` are committed, so the
   submodules are only needed for `:jlatexmath`, `tlottie`, and for rebuilding those libraries.
2. **Provide your own api_id / api_hash.** pelegram reads them at build time from a gitignored
   `secrets/` directory at the repo root so they never land in source control:
   ```
   secrets/api_id      # your numeric api_id from https://my.telegram.org
   secrets/api_hash     # your api_hash
   ```
   Get them at https://my.telegram.org. Without this, the build falls back to Telegram's public
   placeholder values.
3. **Build the tunnel core** (produces `TMessagesProj/libs/libbox-lx.aar`, arm64-v8a):
   ```
   ANDROID_HOME=~/Android/Sdk ./Tools/build-singbox-lx.sh
   ```
   A prebuilt `libbox-lx.aar` is committed, so you can skip this unless you want to rebuild the core.
4. **Point Gradle at your SDK** with a `local.properties` file (`sdk.dir=/path/to/Android/Sdk`).
5. **Build the APK:**
   ```
   ./gradlew :TMessagesProj_App:assembleAfatDebug
   ```

## Credits and license

pelegram is built on the official [Telegram for Android](https://github.com/DrKLO/Telegram) source
and is released under the **GNU General Public License v2** (see `LICENSE`). As a fork you must keep
your source published to comply with the license.

The embedded tunnel uses [sing-box](https://github.com/SagerNet/sing-box) (and the AmneziaWG 2.0
build from its client fork). Their licenses and notices are retained.

This is an unofficial client. It is not affiliated with or endorsed by Telegram. Do not present it
as the official Telegram app, and do not reuse Telegram's name or logo in a way that implies it is
official. Please study Telegram's [security guidelines](https://core.telegram.org/mtproto/security_guidelines)
and take good care of your users' data and privacy.

### API and protocol documentation

- Telegram API: https://core.telegram.org/api
- MTProto protocol: https://core.telegram.org/mtproto
