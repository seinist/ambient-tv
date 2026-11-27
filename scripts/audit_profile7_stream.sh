#!/usr/bin/env bash
# Run only on a local, rights-cleared original Profile-7 file. Never upload
# decoded frames or the source. No flag changes and no transcoding.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[[ $# -ge 1 ]] || { echo "usage: $0 SOURCE [--require-fel] [--require-bl-upscale]" >&2; exit 2; }
source="$(realpath "$1")"; shift
[[ -f "$source" ]] || exit 2
FFMPEG_SOURCE="${FFMPEG_SOURCE:?pinned patched FFmpeg source with static avformat, avcodec, avutil built}"
PL_SOURCE="${PL_SOURCE:?pinned patched libplacebo source}"
PL_BUILD="${PL_BUILD:?pinned libplacebo build}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cd "$ROOT"
n=native/vlc-aar/experimental
cc -std=gnu11 -I "$FFMPEG_SOURCE" "$n/extract_profile7_audit.c" \
    "$FFMPEG_SOURCE/libavformat/libavformat.a" "$FFMPEG_SOURCE/libavcodec/libavcodec.a" \
    "$FFMPEG_SOURCE/libavutil/libavutil.a" -lm -pthread -o "$tmp/extract"
cc -std=gnu11 -I "$PL_SOURCE/src/include" -I "$PL_BUILD/src/include" "$n/test_profile7_stream_gpu.c" \
    -L "$PL_BUILD/src" -Wl,-rpath,"$(realpath "$PL_BUILD/src")" -lplacebo -lm -o "$tmp/gpu"
printf 'Source SHA256: '; sha256sum "$source" | cut -d' ' -f1
"$tmp/extract" "$source" "$tmp/matched.bin" "$@"
"$tmp/gpu" "$tmp/matched.bin"
