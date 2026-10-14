#!/usr/bin/env bash
set -euo pipefail

# Install the Spotify native engine (Rusty/librespot core).
# Builds from source when Rust + cargo-ndk + an Android NDK are available; otherwise downloads
# Rusty's prebuilt core for the pinned tag. Since Rusty 2.7 the visualizer PCM tap is in Kotlin
# (dev.rusty.app.audio.PcmTap), so the prebuilt core is fully featured.
#
# Note: upstream Rusty ships ARM prebuilts only (arm64-v8a / armeabi-v7a). x86 / x86_64
# require a source build via scripts/build_spotify_engine.sh (cargo-ndk).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EXTERNAL_VERSIONS="$ROOT/gradle/external-versions.properties"
RUSTY_REF="${RUSTY_REF:-$(sed -n 's/^SPOTIFY_RUSTY_REF=//p' "$EXTERNAL_VERSIONS" | head -n 1)}"
[ -n "$RUSTY_REF" ] || { echo "SPOTIFY_RUSTY_REF missing in $EXTERNAL_VERSIONS" >&2; exit 1; }
OUT="$ROOT/app/src/main/jniLibs"

detect_ndk_home() {
  if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
    printf '%s\n' "$ANDROID_NDK_HOME"
    return 0
  fi
  if [ -n "${ANDROID_NDK_ROOT:-}" ] && [ -d "$ANDROID_NDK_ROOT" ]; then
    printf '%s\n' "$ANDROID_NDK_ROOT"
    return 0
  fi
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -n "$sdk" ] && [ -d "$sdk/ndk" ]; then
    find "$sdk/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n1
    return 0
  fi
  return 1
}

if [ "${SPOTIFY_PREBUILT:-0}" != "1" ] && command -v cargo >/dev/null 2>&1 && command -v cargo-ndk >/dev/null 2>&1; then
  if NDK_HOME="$(detect_ndk_home)"; then
    export ANDROID_NDK_HOME="$NDK_HOME"
    echo "Building Spotify core from source for all ABIs (ARM + x86/x86_64)."
    RUSTY_REF="$RUSTY_REF" exec "$ROOT/scripts/build_spotify_engine.sh"
  fi
fi

pinned_value() { sed -n "s/^$1=//p" "$EXTERNAL_VERSIONS" | head -n 1; }
PINNED_REF="$(pinned_value SPOTIFY_RUSTY_REF)"
# Prebuilt native code is only accepted from the pinned commit with the pinned SHA-256 sums.
# An explicit RUSTY_REF override (developer escape hatch) downloads that ref unverified.
if [ "$RUSTY_REF" = "$PINNED_REF" ]; then
  FETCH_REF="$(pinned_value SPOTIFY_RUSTY_COMMIT)"
  [ -n "$FETCH_REF" ] || { echo "SPOTIFY_RUSTY_COMMIT missing in $EXTERNAL_VERSIONS" >&2; exit 1; }
  VERIFY=1
else
  FETCH_REF="$RUSTY_REF"
  VERIFY=0
  echo "WARNING: RUSTY_REF=$RUSTY_REF differs from the pin ($PINNED_REF); prebuilt cores are NOT checksum-verified." >&2
fi
command -v sha256sum >/dev/null || { echo "sha256sum not found" >&2; exit 2; }
BASE="https://raw.githubusercontent.com/SerafiniJose/rusty/${FETCH_REF}/app/src/main/jniLibs"
for ABI in arm64-v8a armeabi-v7a; do
  mkdir -p "$OUT/$ABI"
  target="$OUT/$ABI/libspotify_receiver_core.so"
  echo "Fetching $ABI/libspotify_receiver_core.so from Rusty ($RUSTY_REF @ $FETCH_REF)"
  curl -fL --retry 3 --connect-timeout 15 "$BASE/$ABI/libspotify_receiver_core.so" -o "$target.download"
  if [ "$VERIFY" = "1" ]; then
    key="SPOTIFY_CORE_SHA256_$(printf '%s' "$ABI" | tr 'a-z-' 'A-Z_')"
    expected="$(pinned_value "$key")"
    actual="$(sha256sum "$target.download" | cut -d ' ' -f1)"
    if [ -z "$expected" ] || [ "$actual" != "$expected" ]; then
      rm -f "$target.download"
      echo "Checksum mismatch for $ABI Spotify core: expected ${expected:-<missing $key>}, got $actual" >&2
      exit 4
    fi
  fi
  mv "$target.download" "$target"
done
rm -f "$OUT/.spotify-pcm-v39"
printf 'rusty_ref=%s\nsource=prebuilt-arm-only\n' "$RUSTY_REF" > "$OUT/.spotify-rusty"

echo "Spotify native engine (prebuilt Rusty ${RUSTY_REF}, ARM only) installed under app/src/main/jniLibs."
echo "Note: x86/x86_64 Spotify cores require a source build: ./scripts/build_spotify_engine.sh"
