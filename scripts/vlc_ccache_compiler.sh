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
# Configuration feature probes must use the exact driver semantics.
# ccache is useful only for object compilation, not driver queries,
# preprocessing, linking, or warning-as-error capability probes.
compile=0
for arg in "$@"; do
    case "$arg" in
        -Werror|--version|-E|-M|-MM|-fsyntax-only) exec "$compiler" "$@" ;;
        -c) compile=1 ;;
    esac
done
if [[ "$compile" == 0 ]]; then exec "$compiler" "$@"; fi
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
