#!/usr/bin/env bash
# Incremental FFmpeg/libmpv rebuild for an already prepared MPV Android runtime.
# Rebuild both against the same headers; libmpv rejects older FFmpeg runtime versions.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="${MPV_BUILD_ROOT:-$ROOT/.native/mpv-android}/buildscripts"
ABIS="${MPV_DEINTERLACE_ARCHES:-arm64 armv7l x86 x86_64}"
bash "$ROOT/scripts/enable_mpv_audio_encoder.sh" "$BUILD"
bash "$ROOT/scripts/prepare_mpv_ffmpeg.sh" "$BUILD"
for arch in $ABIS; do
    case "$arch" in
        arm64) abi=arm64-v8a ;;
        armv7l) abi=armeabi-v7a ;;
        x86) abi=x86 ;;
        x86_64) abi=x86_64 ;;
        *) echo "Unknown architecture: $arch" >&2; exit 1 ;;
    esac
    [[ -f "$ROOT/app/src/main/jniLibs/$abi/libmpv.so" ]] || { echo "MPV runtime missing for $abi" >&2; exit 1; }
    (cd "$BUILD"; cores="${NATIVE_JOBS:-4}" ./buildall.sh --arch "$arch" -n ffmpeg)
    config="$BUILD/deps/ffmpeg/_build_$arch/config_components.h"
    for component in SNOW_ENCODER MCDEINT_FILTER NNEDI_FILTER BWDIF_FILTER YADIF_FILTER W3FDIF_FILTER ESTDIF_FILTER KERNDEINT_FILTER FIELDMATCH_FILTER DECIMATE_FILTER; do
        grep -q "^#define CONFIG_${component} 1$" "$config" || { echo "Missing $component for $abi" >&2; exit 1; }
    done
    (cd "$BUILD"; cores="${NATIVE_JOBS:-4}" ./buildall.sh --arch "$arch" -n mpv)
    mpv_library="$BUILD/prefix/$arch/usr/local/lib/libmpv.so"
    [[ -f "$mpv_library" ]] || mpv_library="$BUILD/prefix/$arch/lib/libmpv.so"
    [[ -f "$mpv_library" ]] || { echo "Built libmpv missing for $abi" >&2; exit 1; }
    # Publish only after the matching decoder and player have linked successfully.
    cp -L "$mpv_library" "$ROOT/app/src/main/jniLibs/$abi/libmpv.so"
    for library in avcodec avdevice avfilter avformat avutil swresample swscale; do
        cp -L "$BUILD/prefix/$arch/lib/lib${library}.so" "$ROOT/app/src/main/jniLibs/$abi/lib${library}.so"
    done
done
