#!/usr/bin/env bash
# Build the standalone diagnostic runner with the same pinned Android NDK.
set -euo pipefail
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the repository NDK}"
abi="${1:-arm64-v8a}"
out="${2:-out/core-benchmark-runner}"
root="$(cd "$(dirname "$0")/.." && pwd)"
case "$abi" in
  arm64-v8a) triple=aarch64-linux-android ;;
  armeabi-v7a) triple=armv7a-linux-androideabi ;;
  x86_64) triple=x86_64-linux-android ;;
  x86) triple=i686-linux-android ;;
  *) exit 2 ;;
esac
pin=ae22debd425c33fe355ac2e51d9a140d7e8b3fa6
temp=$(mktemp -d)
trap 'rm -rf "$temp"' EXIT
git -C "$temp" init -q
git -C "$temp" fetch -q --depth 1 https://github.com/libretro/libretro-common.git "$pin"
test "$(git -C "$temp" rev-parse FETCH_HEAD)" = "$pin"
git -C "$temp" show FETCH_HEAD:include/libretro.h > "$temp/libretro.h"
git -C "$temp" show FETCH_HEAD:include/libretro_vulkan.h > "$temp/libretro_vulkan.h"
mkdir -p "$(dirname "$out")"
compiler="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/${triple}29-clang++"
"$compiler" -std=c++17 -O2 -Wall -Wextra -Werror -I"$temp" \
  "$root/scripts/native_core_candidates/core_benchmark_runner.cpp" -ldl -lEGL -lGLESv3 -lvulkan \
  -static-libstdc++ -Wl,-z,max-page-size=16384 -o "$out"
