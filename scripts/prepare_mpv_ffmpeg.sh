#!/usr/bin/env bash
# Apply project decoder patches to the pinned FFmpeg source, idempotently.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="${1:?MPV buildscripts directory required}"
for patch in "$ROOT"/native/ambientmpv/patches/ffmpeg-*.patch; do
    if git -C "$BUILD/deps/ffmpeg" apply --check "$patch" 2>/dev/null; then
        git -C "$BUILD/deps/ffmpeg" apply "$patch"
    else
        git -C "$BUILD/deps/ffmpeg" apply --reverse --check "$patch"
    fi
done
for patch in "$ROOT"/native/ambientmpv/patches/mpv-*.patch; do
    if git -C "$BUILD/deps/mpv" apply --check "$patch" 2>/dev/null; then
        git -C "$BUILD/deps/mpv" apply "$patch"
    else
        git -C "$BUILD/deps/mpv" apply --reverse --check "$patch"
    fi
done
