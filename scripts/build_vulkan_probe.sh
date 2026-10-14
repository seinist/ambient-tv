#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="$1"
OUT="$2"
shift 2
case "$(uname -s)" in Linux) host=linux-x86_64 ;; Darwin) host=darwin-x86_64 ;; *) exit 1 ;; esac
for abi in "$@"; do
    case "$abi" in
        arm64-v8a) triple=aarch64-linux-android ;;
        armeabi-v7a) triple=armv7a-linux-androideabi ;;
        x86) triple=i686-linux-android ;;
        x86_64) triple=x86_64-linux-android ;;
        *) exit 1 ;;
    esac
    mkdir -p "$OUT/$abi"
    "$NDK/toolchains/llvm/prebuilt/$host/bin/${triple}29-clang++" \
        -std=c++17 -shared -fPIC -O2 -static-libstdc++ \
        "$ROOT/native/vulkan/probe.cpp" -ldl -landroid -lnativewindow \
        -Wl,-z,defs,-z,max-page-size=16384 -o "$OUT/$abi/libambientvulkan.so"
done
