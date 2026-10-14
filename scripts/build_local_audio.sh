#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
ndk="$1"; output="$2"; shift 2
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
for abi in "$@"; do
  case "$abi" in
    arm64-v8a) arch=arm64; triple=aarch64-linux-android ;;
    armeabi-v7a) arch=armv7l; triple=armv7a-linux-androideabi ;;
    x86) arch=x86; triple=i686-linux-android ;;
    x86_64) arch=x86_64; triple=x86_64-linux-android ;;
    *) exit 2 ;;
  esac
  prefix="${MPV_BUILD_ROOT:-$root/.native/mpv-android}/buildscripts/prefix/$arch"
  for header in libavcodec/avcodec.h libavutil/avutil.h libswresample/swresample.h; do
    if [[ ! -f "$prefix/include/$header" ]]; then
      printf 'Missing FFmpeg header for %s: %s\n' "$abi" "$prefix/include/$header" >&2
      printf 'The ignored .native/ build dependencies are not included in a repository copy or clone.\nRun scripts/build_mpv_android.sh, or restore matching FFmpeg headers from the build that produced app/src/main/jniLibs/.\n' >&2
      exit 1
    fi
  done
  mkdir -p "$output/$abi"
  "$toolchain/${triple}29-clang" -shared -fPIC -O2 -Wall -Wextra -Werror \
    -Wno-unused-parameter -I"$prefix/include" "$root/native/ambientaudio/local_audio.c" \
    -L"$root/app/src/main/jniLibs/$abi" -lavcodec -lavutil -lswresample \
    -Wl,-z,defs -Wl,-z,max-page-size=16384 -Wl,-soname,libambientaudio.so \
    -o "$output/$abi/libambientaudio.so"
done
