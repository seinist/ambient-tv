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
"${CXX:-clang++}" -std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined \
    "$SRC/test_conversion_key.cpp" -o "$TMP/test_conversion_key"
"$TMP/test_conversion_key"
"${CC:-clang}" -std=c11 -Wall -Wextra -Werror -fsanitize=address,undefined \
    "$SRC/test_hdr10plus_payload.c" -lm -o "$TMP/test_hdr10plus_payload"
"$TMP/test_hdr10plus_payload"
"${CC:-clang}" -std=c11 -Wall -Wextra -Werror -fsanitize=address,undefined \
    "$SRC/test_hdr10plus_spatial.c" -lm -o "$TMP/test_hdr10plus_spatial"
"$TMP/test_hdr10plus_spatial"
"${CC:-clang}" -std=c11 -Wall -Wextra -Werror -fsanitize=address,undefined \
    "$SRC/test_dovi_rpu_au.c" -o "$TMP/test_dovi_rpu_au"
"$TMP/test_dovi_rpu_au"
GLSLC="$NDK/shader-tools/$host/glslc"
if [[ ! -x "$GLSLC" ]]; then GLSLC="$(command -v glslc || true)"; fi
[[ -n "$GLSLC" && -x "$GLSLC" ]] || { echo "glslc unavailable" >&2; exit 1; }
for stage in vert frag; do
    "$GLSLC" --target-env=vulkan1.1 "$SRC/sample.$stage" -o "$TMP/sample.$stage.spv"
done
for triple in aarch64-linux-android armv7a-linux-androideabi i686-linux-android x86_64-linux-android; do
    "$NDK/toolchains/llvm/prebuilt/$host/bin/${triple}29-clang++" \
        -std=c++17 -Wall -Wextra -Werror -Wno-missing-field-initializers \
        -fsyntax-only "$SRC/test_android_compile.cpp" "$SRC/display_owner.cpp"
    "$NDK/toolchains/llvm/prebuilt/$host/bin/${triple}29-clang++" \
        -std=c++17 -Wall -Wextra -Werror -Wno-missing-field-initializers \
        -shared -fPIC -Wl,--no-undefined "$SRC/display_owner.cpp" -landroid \
        -o "$TMP/owner-$triple.so"
done
# Compile the C handoff API with the actual pinned libplacebo public headers,
# including its generated API-version header. This is a header contract check,
# not a linked VLC/libplacebo build.
if [[ -n "${LIBPLACEBO_SOURCE:-}" ]]; then
    mkdir -p "$TMP/pl-headers/libplacebo"
    sed -e 's/@majorver@/5/g' -e 's/@apiver@/264/g' -e 's/@extra_defs@/#define PL_HAVE_VULKAN 1/g' \
        "$LIBPLACEBO_SOURCE/src/include/libplacebo/config.h.in" > "$TMP/pl-headers/libplacebo/config.h"
    printf '\n#define PL_HAVE_DOVI 1\n' >> "$TMP/pl-headers/libplacebo/config.h"
    for triple in aarch64-linux-android armv7a-linux-androideabi i686-linux-android x86_64-linux-android; do
        "$NDK/toolchains/llvm/prebuilt/$host/bin/${triple}29-clang" \
            -std=c11 -Wall -Wextra -Werror -Wno-missing-field-initializers \
            -I "$TMP/pl-headers" -isystem "$LIBPLACEBO_SOURCE/src/include" \
            -fsyntax-only "$SRC/test_placebo_compile.c"
        if [[ -n "${VLC_SOURCE:-}" ]]; then
            # Compile the actual patched C modules with the pinned public VLC
            # and libplacebo headers. This deliberately does not pretend to be
            # a configured/autotools link of the full VLC plugin.
            for module in display instance_vulkan utils; do
                "$NDK/toolchains/llvm/prebuilt/$host/bin/${triple}29-clang" \
                    -std=gnu11 -D_GNU_SOURCE -D_FILE_OFFSET_BITS=64 \
                    -DMODULE_NAME=libplacebo -DAMBIENT_VLC_AHB_EXPERIMENT '-DN_(s)=s' \
                    -Werror=implicit-function-declaration -Werror=incompatible-pointer-types -Werror=int-conversion \
                    -I "$TMP/pl-headers" -isystem "$LIBPLACEBO_SOURCE/src/include" \
                    -I "$VLC_SOURCE/include" \
                    -fsyntax-only "$VLC_SOURCE/modules/video_output/libplacebo/$module.c"
            done
            # Validate the actual decoder and NDK per-buffer metadata reader,
            # rather than relying solely on display/instance syntax checks.
            for module in mediacodec mediacodec_ndk mediacodec_jni; do
                "$NDK/toolchains/llvm/prebuilt/$host/bin/${triple}29-clang" \
                    -std=gnu11 -D_GNU_SOURCE -D_FILE_OFFSET_BITS=64 \
                    -DMODULE_NAME=mediacodec '-DN_(s)=s' \
                    -Werror=implicit-function-declaration -Werror=incompatible-pointer-types -Werror=int-conversion \
                    -I "$VLC_SOURCE/include" -I "$VLC_SOURCE/modules/codec" \
                    -I "$VLC_SOURCE/modules/codec/omxil" \
                    -fsyntax-only "$VLC_SOURCE/modules/codec/omxil/$module.c"
            done
            # The actual MP4 demux integration is compiled as well as the
            # host NAL merger; a passing isolated test cannot validate the
            # VLC track, edit-list and block APIs on its own.
            for module in mp4 libmp4; do
                "$NDK/toolchains/llvm/prebuilt/$host/bin/${triple}29-clang" \
                    -std=gnu11 -D_GNU_SOURCE -D_FILE_OFFSET_BITS=64 \
                    -DMODULE_NAME=mp4 -DHAVE_ATTRIBUTE_PACKED=1 '-DN_(s)=s' '-D_(s)=s' \
                    -Werror=implicit-function-declaration -Werror=incompatible-pointer-types -Werror=int-conversion \
                    -I "$VLC_SOURCE/include" \
                    -fsyntax-only "$VLC_SOURCE/modules/demux/mp4/$module.c"
            done
            for module in utils display; do
                "$NDK/toolchains/llvm/prebuilt/$host/bin/${triple}29-clang" \
                    -std=gnu11 -D_GNU_SOURCE -D_FILE_OFFSET_BITS=64 \
                    -DMODULE_NAME=android -DUSE_OPENGL_ES2 '-DN_(s)=s' \
                    -Werror=implicit-function-declaration -Werror=incompatible-pointer-types -Werror=int-conversion \
                    -I "$VLC_SOURCE/include" \
                    -fsyntax-only "$VLC_SOURCE/modules/video_output/android/$module.c"
            done
        fi
    done
    echo "Pinned libplacebo, MediaCodec and Android window C API checks passed for four Android ABIs"
fi
echo "AHB lifetime tests, shaders and four Android ABI syntax checks passed (no GPU execution)"
