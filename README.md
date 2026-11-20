# pelegram

pelegram is a fork of the [Telegram App for Android](https://github.com/DrKLO/Telegram) that adds
an embedded VPN transport, so you can keep Telegram working on networks where deep packet
inspection (DPI) blocks the standard MTProto and SOCKS5 proxies.

Instead of entering a proxy, you paste a modern VPN key (VLESS, Hysteria2, Shadowsocks or
AmneziaWG) or a subscription link. The app runs the tunnel itself and routes Telegram through it. Everything else about the client is stock
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
  - AmneziaWG 2.0 and 3.x - paste the `vpn://...` key exported by the **AmneziaVPN** app (the official Amnezia client), or a raw `awg-quick` `[Interface]` / `[Peer]` config

  Or tap the QR icon on the Add VPN screen to scan a key from a QR code.
- **App-scoped.** Only Telegram's own traffic goes through the tunnel. There is no system-wide VPN,
  no `VpnService`, and no extra Android permission. The core exposes a local SOCKS5 endpoint and the
  app points Telegram's existing proxy layer at it.
- **Connection status, like proxies.** A VPN key shows whether it is working and its latency, the
  same way the proxy list does.
- **Calls and the in-app browser go through the tunnel too.** 1:1 calls (both directions, including
  with stock Telegram users) and the in-app browser / web views route through the VPN, with DNS
  resolved on the far side of the tunnel. The browser's own requests (address bar suggestions,
  popups, favicons, embedded players) and its downloads use the tunnel as well. While the VPN is on,
  web links always open in the in-app browser. If the tunnel is not connected yet, or is reconnecting
  after a server switch, pages fail to load instead of going out with the real address.
- **Subscription links.** An `https://` subscription link becomes one row in the VPN list. Its servers
  run as one group that sends new connections through the fastest one, and the list updates every
  hour. When auto-switch has moved to another key, the app checks the subscription through that key
  from time to time and goes back to it once Telegram answers through it.
- Powered by an embedded [sing-box](https://sing-box.sagernet.org/) core.

## How to use

1. Open **Settings -> Data and Storage -> VPN**. Before you sign in, the button at the top right of the
   login screen opens the same list, so the first login can already go through the tunnel.
2. Add a key: paste your `vless://` / `hysteria2://` / `ss://` link, an `https://` subscription link, or - for AmneziaWG - the `vpn://...` key from the **AmneziaVPN** app.
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
ClientHello - so Outline keys get through DPI that blocks plain Shadowsocks. The Shadowsocks core
is patched for this. A standard sing-box build would send a random salt and be blocked.

**AmneziaWG** connections are added from the **AmneziaVPN** app - the official Amnezia client
(https://amnezia.org), not "AmneziaWG", "Amnezia WG" or any other app. In AmneziaVPN, share/export
the connection and paste the resulting `vpn://...` key into pelegram. A raw `awg-quick`
`[Interface]` / `[Peer]` config (with the `Jc/Jmin/Jmax`, `S1..S4`, `H1..H4` fields) also works.

## Status

- Based on official Telegram **13.2.2** (TL layer 233), kept current with periodic rebases onto upstream.
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

- **Higher battery use than the official client, by design.** The fork cannot use FCM, so it keeps
  tgnet's push connection on and runs a foreground service whenever the VPN is on. The official
  client shares one FCM socket across the whole device and lets the process be frozen.
- **The VPN overwrites the user's "Background Connection" setting.** `updateBackgroundKeepAlive()`
  writes `pushConnection` on every VPN enable, disable and select, so a user who turns Background
  Connection off in Notifications settings gets it turned back on. The fork needs that connection
  while the VPN is on (no FCM), but it should not override an explicit user choice, and it never
  restores the previous value when the VPN goes off.
- **The stories preloader re-runs constantly.** 11104 of 12498 file-load operations in one log were
  stories, covering 4803 distinct files, and one 14.5 MB video was queued 214 times. The repeats are
  cheap because the file is already cached, so each one finishes in about 3 ms and sends nothing over
  the network. Leaving a mini-app or web view brings the dialog list back and makes the stories bar
  preload again. This is upstream behaviour and not a battery problem, but it dominates the log and
  hides real download activity.
- **The app feels slow after 100 hours or more of uptime.** Reported on 7 October and not diagnosed:
  the logs sent that day did not cover the slow process. The app keeps 96 MB of logs (about three
  days), so a report sent before the app is restarted should show whether the slowdown is the tunnel
  or the app itself.
- **Some requests outside the in-app browser still go direct while the VPN is on.** They are message
  translation (translate.googleapis.com), images and music artwork that the app loads from web
  addresses, card tokenization in the payment form, Telegram's DNS-over-HTTPS lookup of server
  addresses, and video that the app's own player streams from a web address (embedded videos in
  Instant View: media3's `DefaultHttpDataSource` opens its connection without a proxy). They go out
  with the real address. Links tapped in a chat bubble open in the external browser, because
  upstream never uses the in-app browser there. Links that you open with "Open in system browser",
  and links that another installed app handles, leave pelegram and the tunnel on purpose.
- **One AmneziaWG key on two devices.** A WireGuard server keeps one session per key, so two clients
  on the same key keep taking the session from each other, and the tunnel goes silent about 20 seconds
  after each handshake. No client-side change can fix that. pelegram detects it, marks the server
  "Key in use on another device" for 30 minutes and, with auto-switch on, moves to the next server.

## Building from source

Requirements: Android SDK (compile SDK 36), NDK `27.2.12479018`, JDK 17, and Go (for building the
tunnel core). See `VPN_FEATURE_PLAN.md` for the full design and integration notes.

1. **Fetch the submodules.** Upstream keeps ffmpeg, dav1d, libvpx, libyuv, openh264, ogg/opus/opusfile,
   boringssl, abseil, tdlib, wamr, tlottie, jlatexmath, boost-regex, libprisma, wallet-engine, twidget,
   widget-sandbox and the androidx media3 player module (`TMessagesProj_Modules/media`) as git
   submodules. widget-sandbox has a nested QuickJS submodule, so the fetch must be recursive:
   ```
   git submodule update --init --recursive --depth=1
   ```
   The prebuilt static libraries under `TMessagesProj/jni/prebuild/lib/<abi>` are committed, so most
   submodules only supply headers. Three are compiled: `TMessagesProj_Modules/media` (Gradle includes
   it from `settings.gradle`), `abseil` and `jlatexmath`. The rest, `wamr` included, are read for
   headers, so none can be skipped. Upstream also turned tgcalls into a submodule in 13.0
   (`TMessagesProj/jni/tgcalls`). pelegram keeps it as plain files instead, at upstream's pinned commit
   plus the call-routing changes (`tgcalls/v2/Socks5UdpSocket`, `ReflectorPort`,
   `NativeNetworkingImpl`), so that directory needs no fetch.
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
