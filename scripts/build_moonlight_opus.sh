#!/usr/bin/env bash
# Build the reviewed Opus source with the same NDK as the consuming JNI library.
set -euo pipefail
root="$1"
ndk="$2"
abi="$3"
case "$abi" in
  arm64-v8a) tune="-mtune=cortex-a57" ;;
  armeabi-v7a|x86|x86_64) tune="" ;;
  *) echo "Unsupported ABI: $abi" >&2; exit 1 ;;
esac
pin=22244de5a79bd1d6d623c32e72bf1954b56235be
[[ "$(git -C "$root/source" rev-parse HEAD)" == "$pin" ]]
[[ -z "$(git -C "$root/source" status --porcelain --untracked-files=no)" ]]
test -f "$ndk/build/cmake/android.toolchain.cmake"
command -v cmake >/dev/null
command -v ninja >/dev/null
out="$root/ambient-built/$abi"
mkdir -p "$out"
# Do not assume optional ARM or x86 instructions globally; upstream runtime dispatch
# selects intrinsics. No fast-math, preserve hardening and floating-point semantics.
cmake -S "$root/source" -B "$out/build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-29 -DANDROID_STL=none \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DCMAKE_C_FLAGS_RELEASE="-O3 -DNDEBUG -flto=thin $tune" \
  -DOPUS_BUILD_SHARED_LIBRARY=OFF -DOPUS_BUILD_TESTING=OFF \
  -DOPUS_BUILD_PROGRAMS=OFF -DOPUS_DISABLE_INTRINSICS=OFF \
  -DOPUS_HARDENING=ON -DOPUS_FAST_MATH=OFF -DOPUS_FLOAT_APPROX=OFF \
  -DOPUS_X86_PRESUME_AVX2=OFF -DOPUS_X86_PRESUME_SSE4_1=OFF
cmake --build "$out/build" --target opus --parallel
test -s "$out/build/libopus.a"
cp "$out/build/libopus.a" "$out/libopus.a.tmp"
mv "$out/libopus.a.tmp" "$out/libopus.a"
