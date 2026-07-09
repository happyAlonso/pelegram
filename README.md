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
  - AmneziaWG 2.0 - paste the `vpn://...` key exported by the **AmneziaVPN** app (the official Amnezia client), or a raw `awg-quick` `[Interface]` / `[Peer]` config
- **App-scoped.** Only Telegram's own traffic goes through the tunnel. There is no system-wide VPN,
  no `VpnService`, and no extra Android permission. The core exposes a local SOCKS5 endpoint and the
  app points Telegram's existing proxy layer at it.
- **Connection status, like proxies.** A VPN key shows whether it is working and its latency, the
  same way the proxy list does.
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
```

**AmneziaWG** connections are added from the **AmneziaVPN** app - the official Amnezia client
(https://amnezia.org), not "AmneziaWG", "Amnezia WG" or any other app. In AmneziaVPN, share/export
the connection and paste the resulting `vpn://...` key into pelegram. A raw `awg-quick`
`[Interface]` / `[Peer]` config (with the `Jc/Jmin/Jmax`, `S1..S4`, `H1..H4` fields) also works.

## Status

- Embedded sing-box core, config parsing, and the connect/disconnect wiring are integrated.
- Current builds target **arm64-v8a** only.
- The in-app UI for pasting and managing keys is being finished; the transport and status plumbing
  are in place.

## Building from source

Requirements: Android SDK (compile SDK 35), NDK `27.2.12479018`, JDK 17, and Go (for building the
tunnel core). See `VPN_FEATURE_PLAN.md` for the full design and integration notes.

1. **Provide your own api_id / api_hash.** pelegram reads them at build time from a gitignored
   `secrets/` directory at the repo root so they never land in source control:
   ```
   secrets/api_id      # your numeric api_id from https://my.telegram.org
   secrets/api_hash     # your api_hash
   ```
   Get them at https://my.telegram.org. Without this, the build falls back to Telegram's public
   placeholder values.
2. **Build the tunnel core** (produces `TMessagesProj/libs/libbox-lx.aar`, arm64-v8a):
   ```
   ANDROID_HOME=~/Android/Sdk ./Tools/build-singbox-lx.sh
   ```
   A prebuilt `libbox-lx.aar` is committed, so you can skip this unless you want to rebuild the core.
3. **Point Gradle at your SDK** with a `local.properties` file (`sdk.dir=/path/to/Android/Sdk`).
4. **Build the APK:**
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
