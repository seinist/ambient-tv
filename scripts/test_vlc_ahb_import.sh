#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/native/vlc-aar/experimental"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/30.0.16248370}}"
case "$(uname -s)" in
    Linux) host=linux-x86_64 ;;
    Darwin) host=darwin-x86_64 ;;
    *) echo "Unsupported host" >&2; exit 1 ;;
esac
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
for test in test_unique_fd test_frame_lease test_slot_pool; do
    "${CXX:-clang++}" -std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined \
        "$SRC/$test.cpp" -o "$TMP/$test"
    "$TMP/$test"
done
GLSLC="$NDK/shader-tools/$host/glslc"
if [[ ! -x "$GLSLC" ]]; then GLSLC="$(command -v glslc || true)"; fi
[[ -n "$GLSLC" && -x "$GLSLC" ]] || { echo "glslc unavailable" >&2; exit 1; }
for stage in vert frag; do
    "$GLSLC" --target-env=vulkan1.1 "$SRC/sample.$stage" -o "$TMP/sample.$stage.spv"
done
for triple in aarch64-linux-android armv7a-linux-androideabi i686-linux-android x86_64-linux-android; do
    "$NDK/toolchains/llvm/prebuilt/$host/bin/${triple}29-clang++" \
        -std=c++17 -Wall -Wextra -Werror -Wno-missing-field-initializers \
        -fsyntax-only "$SRC/test_android_compile.cpp"
done
echo "AHB lifetime tests, shaders and four Android ABI syntax checks passed (no GPU execution)"
