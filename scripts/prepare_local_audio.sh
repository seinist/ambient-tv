#!/usr/bin/env bash
# Shared preflight for Gradle and build.sh; rebuild matching headers and libraries.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
check_only=0
if [[ "${1:-}" == --check ]]; then check_only=1; shift; fi
build_root="${MPV_BUILD_ROOT:-$root/.native/mpv-android}"
ready() {
  local abi arch header lib ref commit marker
  ref="${FFMPEG_REF:-$(sed -n 's/^FFMPEG_REF=//p' "$root/gradle/external-versions.properties")}"
  commit="$(sed -n 's/^FFMPEG_COMMIT=//p' "$root/gradle/external-versions.properties")"
  marker="$root/app/src/main/jniLibs/.mpv-version"
  [[ -f "$marker" ]] || return 1
  grep -Fx "ffmpeg_ref=$ref" "$marker" >/dev/null || return 1
  if [[ "$ref" == "$(sed -n 's/^FFMPEG_REF=//p' "$root/gradle/external-versions.properties")" ]]; then
    grep -Fx "ffmpeg_commit=$commit" "$marker" >/dev/null || return 1
  fi
  for abi in "$@"; do
    case "$abi" in
      arm64-v8a) arch=arm64 ;;
      armeabi-v7a) arch=armv7l ;;
      x86|x86_64) arch="$abi" ;;
      *) return 1 ;;
    esac
    for header in libavcodec/avcodec.h libavutil/avutil.h libavutil/audio_fifo.h libavutil/opt.h libswresample/swresample.h; do
      [[ -s "$build_root/buildscripts/prefix/$arch/include/$header" ]] || return 1
    done
    for lib in avcodec avutil swresample; do
      [[ -s "$root/app/src/main/jniLibs/$abi/lib$lib.so" ]] || return 1
    done
  done
}
if ready "$@"; then exit 0; fi
if (( check_only )); then exit 1; fi
echo 'Missing or outdated local audio dependencies; downloading/building the MPV/FFmpeg runtime.'
bash "$root/scripts/build_mpv_android.sh"
if ! ready "$@"; then
  echo 'MPV build finished without the required local audio headers/libraries.' >&2
  exit 1
fi
