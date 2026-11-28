#!/usr/bin/env bash
# Idempotent source integration into the pinned native playback checkouts.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SVP="$ROOT/native/ambientsvp"
if [[ "${1:-}" == --hash ]]; then
    case "${2:-}" in mpv) adapter=vf_ambientsvp.c;; vlc) adapter=vlc_ambientsvp.c;; *) exit 2;; esac
    extra=("$SVP/include/ambientsvp_quality.h" "$SVP/include/ambientsvp_vulkan.h" "$SVP/adapters/svp_placebo_quality.h")
    if [[ "${2:-}" == mpv ]]; then extra+=("$SVP/adapters/svp_mpv_vulkan.h" "$SVP/adapters/svp_mpv_mediacodec.h" "$SVP/include/ambientsvp_vulkan.h" "$SVP/include/ambientsvp_android_vulkan.h" "$ROOT/scripts/prepare_svp_mpv_mediacodec.py" "$ROOT/scripts/prepare_svp_mpv_fel.py" "$SVP/adapters/svp_mpv_fel_pair.h" "$SVP/adapters/svp_mpv_fel_vo.h" "$ROOT/scripts/prepare_svp_mpv_hdr.py" "$SVP/adapters/svp_mpv_hdr_spatial.h" "$SVP/adapters/hdr10plus/"*.h "$SVP/include/svp_hdr_curve.h"); else extra+=("$SVP/adapters/svp_vlc_android.h" "$SVP/include/ambientsvp_android_stream.h" "$SVP/include/svp_sdr_profile.h" "$SVP/include/svp_av1_config.h" "$SVP/include/svp_hevc_hdr_guard.h" "$ROOT/scripts/prepare_svp_vlc_mediacodec.py" "$ROOT/scripts/prepare_svp_vlc_hdr_bridge.py" "$SVP/adapters/svp_vlc_hdr_pair.h" "$SVP/adapters/svp_vlc_hdr_schedule.h" "$SVP/adapters/svp_vlc_hdr_display.h"); fi
    sha256sum "${extra[@]}" "$SVP/adapters/$adapter" "$SVP/include/ambientsvp.h" "$SVP/include/ambientsvp_loader.h" "$SVP/include/svp_retry.h" "$SVP/include/svp_planes.h" "$0" \
        | cut -d ' ' -f1 | sha256sum | cut -d ' ' -f1
    exit 0
fi
case "${1:?mpv or vlc}" in
mpv)
    SOURCE="${2:-$ROOT/.native/mpv-android/buildscripts/deps/mpv}"
    cp "$SVP/adapters/vf_ambientsvp.c" "$SOURCE/video/filter/vf_ambientsvp.c"
    cp "$SVP/adapters/svp_mpv_vulkan.h" "$SVP/include/ambientsvp_vulkan.h" "$SVP/include/svp_hdr_curve.h" "$SVP/include/ambientsvp_quality.h" "$SOURCE/video/filter/"
    cp "$SVP/include/ambientsvp.h" "$SVP/include/ambientsvp_loader.h" "$SVP/include/svp_retry.h" "$SVP/include/svp_planes.h" "$SOURCE/video/filter/"
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
    python3 "$ROOT/scripts/prepare_svp_mpv_mediacodec.py" "$SOURCE"
    python3 "$ROOT/scripts/prepare_svp_mpv_fel.py" "$SOURCE"
    python3 "$ROOT/scripts/prepare_svp_mpv_hdr.py" "$SOURCE"
    ;;
vlc)
    SOURCE="${2:-$ROOT/.native/libvlcjni/vlc}"
    cp "$SVP/adapters/vlc_ambientsvp.c" "$SOURCE/modules/video_filter/ambientsvp.c"
    cp "$SVP/include/ambientsvp.h" "$SVP/include/ambientsvp_loader.h" "$SVP/include/svp_retry.h" "$SVP/include/svp_planes.h" "$SVP/include/ambientsvp_quality.h" "$SOURCE/modules/video_filter/"
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
    if [[ -f "$SOURCE/.ambient-ahb-experimental" ]]; then
        # PR510 owns the decoder and AHB lifetime in this isolated build.
        # Do not apply the competing SurfaceControl decoder patches.
        python3 "$ROOT/scripts/prepare_svp_vlc_hdr_bridge.py" "$SOURCE" --restore
        cp "$SVP/adapters/svp_vlc_hdr_schedule.h" "$SOURCE/modules/video_filter/svp_vlc_android.h"
        cp "$SVP/adapters/svp_vlc_hdr_pair.h" "$SOURCE/modules/video_filter/"
    else
        python3 "$ROOT/scripts/prepare_svp_vlc_mediacodec.py" "$SOURCE"
    fi
    ;;
*) exit 2 ;;
esac
