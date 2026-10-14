#!/usr/bin/env bash
set -euo pipefail

# Build the Spotify native core (Rusty/librespot) from source.
# The visualizer PCM tap lives in Kotlin (dev.rusty.app.audio.PcmTap); no source patch is needed.
# Requires: curl, tar, Rust, cargo-ndk, Android NDK r27+.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EXTERNAL_VERSIONS="$ROOT/gradle/external-versions.properties"
external_version() { local key="$1"; sed -n "s/^${key}=//p" "$EXTERNAL_VERSIONS" | head -n 1; }
[[ -f "$EXTERNAL_VERSIONS" ]] || { echo "Missing $EXTERNAL_VERSIONS" >&2; exit 1; }
RUSTY_REF="${RUSTY_REF:-$(external_version SPOTIFY_RUSTY_REF)}"
WORK="${SPOTIFY_RUST_WORKDIR:-$ROOT/third_party/spotify/rusty-src}"
OUT="$ROOT/app/src/main/jniLibs"
# The pinned tag is fetched by the commit it pointed to, so a moved tag cannot change the source.
if [ "$RUSTY_REF" = "$(external_version SPOTIFY_RUSTY_REF)" ] && [ -n "$(external_version SPOTIFY_RUSTY_COMMIT)" ]; then
  RUSTY_SOURCE="$(external_version SPOTIFY_RUSTY_COMMIT)"
  URL="https://codeload.github.com/SerafiniJose/rusty/tar.gz/${RUSTY_SOURCE}"
else
  RUSTY_SOURCE="$RUSTY_REF"
  URL="https://codeload.github.com/SerafiniJose/rusty/tar.gz/refs/tags/${RUSTY_REF}"
fi
ARCHIVE="$ROOT/third_party/spotify/rusty-${RUSTY_SOURCE}.tar.gz"

command -v rustup >/dev/null || { echo "rustup not found; a rustup-managed Rust toolchain is required" >&2; exit 2; }
command -v curl >/dev/null || { echo "curl not found" >&2; exit 2; }
command -v tar >/dev/null || { echo "tar not found" >&2; exit 2; }

# Homebrew/distro rust often shadows rustup on PATH and has no Android rust-std.
# cargo ndk then fails with "can't find crate for core" for armv7-linux-androideabi.
RUSTUP_CARGO="$(rustup which cargo 2>/dev/null || true)"
if [ -z "$RUSTUP_CARGO" ] || [ ! -x "$RUSTUP_CARGO" ]; then
  echo "rustup cargo not found; install a rustup toolchain (rustup toolchain install stable)" >&2
  exit 2
fi
export PATH="$(dirname "$RUSTUP_CARGO"):$PATH"
command -v cargo >/dev/null || { echo "cargo not found; install Rust via rustup" >&2; exit 2; }
echo "Using $(rustc --version) from $(rustc --print sysroot)"

if ! command -v cargo-ndk >/dev/null 2>&1; then
  if [ "${SPOTIFY_AUTO_INSTALL_CARGO_NDK:-1}" = "1" ]; then
    echo "cargo-ndk not found; installing it with cargo"
    cargo install cargo-ndk --locked
  else
    echo "cargo-ndk not found (cargo install cargo-ndk)" >&2
    exit 2
  fi
fi

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -n "$SDK" ] && [ -d "$SDK/ndk" ]; then
    DETECTED_NDK="$(find "$SDK/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n1)"
    if [ -n "$DETECTED_NDK" ]; then
      export ANDROID_NDK_HOME="$DETECTED_NDK"
      echo "Using Android NDK: $ANDROID_NDK_HOME"
    fi
  fi
fi
: "${ANDROID_NDK_HOME:?Android NDK not found. Install NDK r27+ in the Android SDK or set ANDROID_NDK_HOME.}"

rm -rf "$WORK"
mkdir -p "$(dirname "$WORK")" "$OUT"
if [ ! -f "$ARCHIVE" ]; then
  echo "Fetching Rusty ${RUSTY_REF} source"
  curl -fL --retry 3 --connect-timeout 20 "$URL" -o "$ARCHIVE"
fi
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
tar -xzf "$ARCHIVE" -C "$TMP"
SRC="$(find "$TMP" -mindepth 1 -maxdepth 1 -type d -name 'rusty-*' | head -n1)"
[ -n "$SRC" ] || { echo "Rusty source archive layout not recognized" >&2; exit 3; }
mv "$SRC" "$WORK"

rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android >/dev/null
# Keep cargo artifacts outside $WORK: this script rm -rf's the extracted source
# on every rebuild, and a concurrent build.sh would otherwise delete target/
# mid-compile ("failed to write ... No such file or directory").
export CARGO_TARGET_DIR="${SPOTIFY_CARGO_TARGET_DIR:-$ROOT/third_party/spotify/cargo-target}"
mkdir -p "$CARGO_TARGET_DIR"
(
  cd "$WORK/rust"
  cargo ndk -t armeabi-v7a -t arm64-v8a -t x86 -t x86_64 --platform 26 -o "$OUT" build --release
)

# Rusty >= 2.7 no longer needs libc++_shared itself, but other native components in the APK
# load it; keep shipping the copy from the exact NDK used for this build.
copy_ndk_libcxx() {
  local abi="$1" triple src
  case "$abi" in
    arm64-v8a) triple="aarch64-linux-android" ;;
    armeabi-v7a) triple="arm-linux-androideabi" ;;
    x86) triple="i686-linux-android" ;;
    x86_64) triple="x86_64-linux-android" ;;
    *) return 1 ;;
  esac
  src="$(find "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" -path "*/sysroot/usr/lib/$triple/libc++_shared.so" -type f | head -n1)"
  [ -n "$src" ] && [ -f "$src" ] || { echo "libc++_shared.so for $abi not found in $ANDROID_NDK_HOME" >&2; exit 4; }
  cp "$src" "$OUT/$abi/libc++_shared.so"
}

for ABI in arm64-v8a armeabi-v7a x86 x86_64; do
  test -f "$OUT/$ABI/libspotify_receiver_core.so" || { echo "Missing $ABI Spotify core after build" >&2; exit 4; }
  copy_ndk_libcxx "$ABI"
done

rm -f "$OUT/.spotify-pcm-v39"
printf 'rusty_ref=%s\n' "$RUSTY_REF" > "$OUT/.spotify-rusty"
echo "Built Spotify core (Rusty ${RUSTY_REF}) under app/src/main/jniLibs."
