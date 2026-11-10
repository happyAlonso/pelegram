#!/usr/bin/env bash
#
# Build the embedded sing-box (lx fork) tunnel core into an arm64-v8a AAR and drop it into
# TMessagesProj/libs/. Covers VLESS+REALITY, Hysteria2, and AmneziaWG 2.0 / 3.x in one libbox.aar.
#
# Requirements:
#   - Android NDK 27.2.12479018 (set ANDROID_HOME; script derives ANDROID_NDK_HOME)
#   - Go (>= 1.24; 1.26 verified)
#   - sagernet's patched gomobile/gobind: go install github.com/sagernet/gomobile/cmd/{gomobile,gobind}@v0.1.13
#
# Usage: ANDROID_HOME=~/Android/Sdk ./Tools/build-singbox-lx.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC_DIR="${SINGBOX_LX_DIR:-$REPO_ROOT/../sing-box-lx}"
SINGBOX_LX_REPO="${SINGBOX_LX_REPO:-https://github.com/Leadaxe/sing-box-lx.git}"
: "${ANDROID_HOME:?set ANDROID_HOME to your Android SDK path}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
export PATH="$PATH:$(go env GOPATH)/bin"

# Pinned so the published fork is reproducible. Bump deliberately after re-auditing the delta.
SINGBOX_LX_REF="${SINGBOX_LX_REF:-v1.14.4-lx.33}"
VERSION_TAG="${VERSION_TAG:-1.14.4-lx.33}"

# NOTE: with_naive_outbound is intentionally omitted - it links a prebuilt cronet-go .a whose
# ARMv8.3 pointer-auth relocations (reloc 315) NDK r27's lld rejects. naive is unused here.
TAGS="with_gvisor,with_quic,with_wireguard,with_utls,badlinkname,tfogo_checklinkname0,with_xhttp,with_awg,with_lx_command,with_lx_idle_suspend"
LDFLAGS="-X github.com/sagernet/sing-box/constant.Version=$VERSION_TAG -X internal/godebug.defaultGODEBUG=multipathtcp=0 -s -w -buildid= -checklinkname=0"

if [ ! -d "$SRC_DIR" ]; then
  git clone --depth 1 --branch "$SINGBOX_LX_REF" "$SINGBOX_LX_REPO" "$SRC_DIR"
fi
cd "$SRC_DIR"
# AmneziaWG (2.0 and 3.x) lives in the Leadaxe/wireguard-go-awg2-lx submodule, and go.mod points
# sing-tun, gvisor and utls at submodules too; a shallow clone omits all four
git submodule update --init --depth 1 submodules/wireguard-go submodules/sing-tun submodules/gvisor submodules/utls

gomobile bind -v \
  -target=android/arm64 \
  -androidapi 21 \
  -trimpath \
  -tags "$TAGS" \
  -ldflags "$LDFLAGS" \
  -o "$REPO_ROOT/TMessagesProj/libs/libbox-lx.aar" \
  ./experimental/libbox

echo "Built $REPO_ROOT/TMessagesProj/libs/libbox-lx.aar"
