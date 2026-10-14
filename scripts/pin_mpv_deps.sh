#!/usr/bin/env bash
# Pins the mpv-android dependencies that upstream include/download-deps.sh takes unpinned.
#
#   pin_mpv_deps.sh prefetch <buildscripts>
#       Before download-deps.sh: downloads the source tarballs its depinfo.sh names and checks
#       their SHA-256 (MPV_DEP_SHA256_<file>). download-deps.sh skips directories that exist.
#   pin_mpv_deps.sh pin <buildscripts>
#       After download-deps.sh: moves the git dependencies it cloned from their default branch
#       (mpv, libplacebo, dav1d, libass) and the freetype2 tag to the pinned commits
#       (MPV_DEP_<NAME>_COMMIT).
#
# Pins live in gradle/external-versions.properties. A missing pin, a checksum mismatch or a
# commit that cannot be checked out stops the build instead of shipping unreviewed code.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EXTERNAL_VERSIONS="$ROOT/gradle/external-versions.properties"
external_version() { sed -n "s/^$1=//p" "$EXTERNAL_VERSIONS" | head -n 1; }
fail() { echo "pin_mpv_deps: $*" >&2; exit 4; }

usage="usage: pin_mpv_deps.sh prefetch|pin <mpv-android buildscripts dir>"
MODE="${1:?$usage}"
BUILDSCRIPTS="${2:?$usage}"
DEPS="$BUILDSCRIPTS/deps"
[[ -f "$EXTERNAL_VERSIONS" ]] || fail "missing $EXTERNAL_VERSIONS"

fetch_tarball() {
  local dir="$1" url="$2" file expected actual download
  [[ -d "$DEPS/$dir" ]] && return 0
  file="$(basename "$url")"
  expected="$(external_version "MPV_DEP_SHA256_$file")"
  [[ -n "$expected" ]] || fail "no SHA-256 pin for $file (add MPV_DEP_SHA256_$file to $EXTERNAL_VERSIONS)"
  mkdir -p "$DEPS"
  download="$DEPS/.$file.download"
  echo "Fetching $file"
  curl -fsSL --retry 3 --connect-timeout 20 "$url" -o "$download"
  actual="$(sha256sum "$download" | cut -d ' ' -f1)"
  if [[ "$actual" != "$expected" ]]; then
    rm -f "$download"
    fail "checksum mismatch for $file: expected $expected, got $actual"
  fi
  # Extract next to the target and move it in place: an interrupted run must not leave a
  # directory that download-deps.sh would then accept as complete.
  rm -rf "$DEPS/$dir.partial"
  mkdir "$DEPS/$dir.partial"
  tar -xf "$download" -C "$DEPS/$dir.partial" --strip-components=1
  mv "$DEPS/$dir.partial" "$DEPS/$dir"
  rm -f "$download"
}

prefetch() {
  [[ -f "$BUILDSCRIPTS/include/depinfo.sh" ]] || fail "missing $BUILDSCRIPTS/include/depinfo.sh"
  # The same URLs as upstream download-deps.sh, built from the versions of its pinned depinfo.sh.
  # shellcheck source=/dev/null
  . "$BUILDSCRIPTS/include/depinfo.sh"
  fetch_tarball mbedtls "https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-$v_mbedtls/mbedtls-$v_mbedtls.tar.bz2"
  fetch_tarball fribidi "https://github.com/fribidi/fribidi/releases/download/v$v_fribidi/fribidi-$v_fribidi.tar.xz"
  fetch_tarball harfbuzz "https://github.com/harfbuzz/harfbuzz/releases/download/$v_harfbuzz/harfbuzz-$v_harfbuzz.tar.xz"
  fetch_tarball unibreak "https://github.com/adah1972/libunibreak/releases/download/libunibreak_${v_unibreak//./_}/libunibreak-${v_unibreak}.tar.gz"
  fetch_tarball libxml2 "https://gitlab.gnome.org/GNOME/libxml2/-/archive/v${v_libxml2}/libxml2-v${v_libxml2}.tar.gz"
  fetch_tarball fontconfig "https://gitlab.freedesktop.org/fontconfig/fontconfig/-/archive/${v_fontconfig}/fontconfig-${v_fontconfig}.tar.gz"
  fetch_tarball lua "https://www.lua.org/ftp/lua-$v_lua.tar.gz"
  fetch_tarball curl "https://curl.se/download/curl-$v_curl.tar.gz"
}

pin_git() {
  local dir="$1" key want have
  key="MPV_DEP_$(printf '%s' "$dir" | tr 'a-z' 'A-Z')_COMMIT"
  want="$(external_version "$key")"
  [[ "$want" =~ ^[0-9a-f]{40}$ ]] || fail "missing or invalid $key in $EXTERNAL_VERSIONS"
  [[ -d "$DEPS/$dir/.git" ]] || fail "$DEPS/$dir is not a git checkout (did download-deps.sh run?)"
  have="$(git -C "$DEPS/$dir" rev-parse HEAD)"
  if [[ "$have" != "$want" ]]; then
    echo "Pinning $dir to $want"
    git -C "$DEPS/$dir" cat-file -e "$want^{commit}" 2>/dev/null || git -C "$DEPS/$dir" fetch origin "$want"
    # Our own patches (SVP filter, FFmpeg/meson tweaks) are applied after this step on every
    # build; drop the previous pin's copy first so it cannot block the checkout.
    git -C "$DEPS/$dir" reset --hard --quiet HEAD
    git -C "$DEPS/$dir" checkout --detach --quiet "$want"
    if [[ -f "$DEPS/$dir/.gitmodules" ]]; then git -C "$DEPS/$dir" submodule update --init --recursive; fi
  fi
  [[ "$(git -C "$DEPS/$dir" rev-parse HEAD)" == "$want" ]] || fail "$dir is not at $want"
}

pin() {
  for dir in mpv libplacebo dav1d libass freetype2; do pin_git "$dir"; done
}

case "$MODE" in
  prefetch) prefetch ;;
  pin) pin ;;
  *) echo "$usage" >&2; exit 2 ;;
esac
