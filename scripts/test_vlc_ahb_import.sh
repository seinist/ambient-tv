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
# Native mocked submission test uses host Vulkan declarations, not a GPU.
"${CXX:-clang++}" -std=c++17 -Wall -Wextra -Werror -Wno-missing-field-initializers \
    -fsanitize=address,undefined "$SRC/test_submission.cpp" -o "$TMP/test_submission"
"$TMP/test_submission"
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
# Compile the C handoff API with the actual pinned libplacebo public headers,
# including its generated API-version header. This is a header contract check,
# not a linked VLC/libplacebo build.
if [[ -n "${LIBPLACEBO_SOURCE:-}" ]]; then
    mkdir -p "$TMP/pl-headers/libplacebo"
    sed -e 's/@majorver@/5/g' -e 's/@apiver@/264/g' -e 's/@extra_defs@/#define PL_HAVE_VULKAN 1/g' \
        "$LIBPLACEBO_SOURCE/src/include/libplacebo/config.h.in" > "$TMP/pl-headers/libplacebo/config.h"
    for triple in aarch64-linux-android armv7a-linux-androideabi i686-linux-android x86_64-linux-android; do
        "$NDK/toolchains/llvm/prebuilt/$host/bin/${triple}29-clang" \
            -std=c11 -Wall -Wextra -Werror -Wno-missing-field-initializers \
            -I "$TMP/pl-headers" -isystem "$LIBPLACEBO_SOURCE/src/include" \
            -fsyntax-only "$SRC/test_placebo_compile.c"
    done
    echo "Pinned libplacebo C API checks passed for four Android ABIs"
fi
echo "AHB lifetime tests, shaders and four Android ABI syntax checks passed (no GPU execution)"
