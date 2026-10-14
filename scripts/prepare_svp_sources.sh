#!/usr/bin/env bash
# Idempotent source integration into the pinned native playback checkouts.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SVP="$ROOT/native/ambientsvp"
if [[ "${1:-}" == --hash ]]; then
    case "${2:-}" in mpv) adapter=vf_ambientsvp.c;; vlc) adapter=vlc_ambientsvp.c;; *) exit 2;; esac
    sha256sum "$SVP/adapters/$adapter" "$SVP/include/ambientsvp.h" "$SVP/include/ambientsvp_loader.h" "$0" \
        | cut -d ' ' -f1 | sha256sum | cut -d ' ' -f1
    exit 0
fi
case "${1:?mpv or vlc}" in
mpv)
    SOURCE="${2:-$ROOT/.native/mpv-android/buildscripts/deps/mpv}"
    cp "$SVP/adapters/vf_ambientsvp.c" "$SOURCE/video/filter/vf_ambientsvp.c"
    cp "$SVP/include/ambientsvp.h" "$SVP/include/ambientsvp_loader.h" "$SOURCE/video/filter/"
    if ! grep -q 'vf_ambientsvp.c' "$SOURCE/meson.build"; then
        sed -i "/'video\/filter\/vf_sub.c',/a\    'video/filter/vf_ambientsvp.c'," "$SOURCE/meson.build"
    fi
    if ! grep -q 'extern.*vf_ambientsvp' "$SOURCE/filters/user_filters.h"; then
        sed -i '/extern const struct mp_user_filter_entry vf_sub;/a\extern const struct mp_user_filter_entry vf_ambientsvp;' "$SOURCE/filters/user_filters.h"
    fi
    if ! grep -q '&vf_ambientsvp,' "$SOURCE/filters/user_filters.c"; then
        sed -i '/    \&vf_sub,/a\    \&vf_ambientsvp,' "$SOURCE/filters/user_filters.c"
    fi
    grep -q 'vf_ambientsvp.c' "$SOURCE/meson.build"
    ;;
vlc)
    SOURCE="${2:-$ROOT/.native/libvlcjni/vlc}"
    cp "$SVP/adapters/vlc_ambientsvp.c" "$SOURCE/modules/video_filter/ambientsvp.c"
    cp "$SVP/include/ambientsvp.h" "$SVP/include/ambientsvp_loader.h" "$SOURCE/modules/video_filter/"
    if ! grep -q 'libambientsvp_plugin_la_SOURCES' "$SOURCE/modules/video_filter/Makefile.am"; then
        sed -i '/libfps_plugin_la_SOURCES =/a\libambientsvp_plugin_la_SOURCES = video_filter/ambientsvp.c\nlibambientsvp_plugin_la_LIBADD = $(LIBDL)' "$SOURCE/modules/video_filter/Makefile.am"
        sed -i '/^[[:space:]]*libfps_plugin.la /a\	libambientsvp_plugin.la \\' "$SOURCE/modules/video_filter/Makefile.am"
    fi
    grep -q 'libambientsvp_plugin.la' "$SOURCE/modules/video_filter/Makefile.am"
    # A temporal filter must run before the VLC playback clock, not in the
    # interactive chain which may feed the same picture repeatedly and forbids
    # timestamp changes. This list is sorted for bsearch.
    if ! grep -q '"ambientsvp",' "$SOURCE/src/video_output/video_output.c"; then
        sed -i '/        "amf_frc",/i\        "ambientsvp",' "$SOURCE/src/video_output/video_output.c"
    fi
    grep -q '"ambientsvp",' "$SOURCE/src/video_output/video_output.c"
    ;;
*) exit 2 ;;
esac
