#!/usr/bin/env bash
# Compiler wrapper for VLC's explicit NDK cross-compiler paths and ndk-build.
# ccache itself validates each compilation's actual compiler, args and headers.
set -euo pipefail
case "${0##*/}" in
    clang) compiler="${AMBIENT_VLC_REAL_CLANG:?}" ;;
    clang++) compiler="${AMBIENT_VLC_REAL_CLANG:?}++" ;;
    ndk-ccache) compiler="${1:?}"; shift ;;
    *) echo "Unexpected VLC compiler wrapper name" >&2; exit 2 ;;
esac
case "$PWD/" in
    */contrib/contrib-android-*/ffmpeg/*|*/contrib/contrib-android-*/ffmpeg-*) component=ffmpeg ;;
    */contrib/contrib-android-*/libplacebo/*|*/contrib/contrib-android-*/libplacebo-*) component=libplacebo ;;
    */contrib/*) component=contrib ;;
    */vlc/build-android-*/*|*/vlc/*) component=vlc ;;
    *) component=jni ;;
esac
# ndk-build may run from the wrapper's parent rather than the library source.
if [[ "${0##*/}" == ndk-ccache ]]; then component=jni; fi
export CCACHE_DIR="${AMBIENT_VLC_CCACHE_ROOT:?}/$component"
export CCACHE_COMPILERCHECK=content
export CCACHE_MAXSIZE="${AMBIENT_VLC_CCACHE_MAXSIZE:-750M}"
mkdir -p "$CCACHE_DIR"
exec ccache "$compiler" "$@"
