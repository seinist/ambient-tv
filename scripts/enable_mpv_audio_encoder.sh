#!/usr/bin/env bash
# mpv-android disables encoders globally. lavcac3enc needs AC3; mcdeint needs Snow.
set -euo pipefail
ffmpeg_script="${1:?Usage: enable_mpv_audio_encoder.sh <mpv buildscripts directory>}/scripts/ffmpeg.sh"
[[ -f "$ffmpeg_script" ]] || { echo "Missing FFmpeg build script: $ffmpeg_script" >&2; exit 1; }
# Keep all existing codec and Vulkan flags; append only to the encoder allow-list.
sed -i.bak '/--enable-encoder=/ { /ac3/! s/$/,ac3/; }' "$ffmpeg_script"
grep -Eq -- '--enable-encoder=([^[:space:]]*,)?ac3(,|[[:space:]]|$)' "$ffmpeg_script" || {
    echo 'Could not enable the AC3 encoder in the upstream FFmpeg build' >&2
    exit 1
}
# mcdeint uses Snow internally with no_bitstream=1 for motion estimation and
# reconstructed frames. This never requests server transcoding or writes a stream.
sed -i.bak '/--enable-encoder=/ { /snow/! s/$/,snow/; }' "$ffmpeg_script"
grep -Eq -- '--enable-encoder=([^[:space:]]*,)?snow(,|[[:space:]]|$)' "$ffmpeg_script" || {
    echo 'Could not enable the Snow encoder required by mcdeint' >&2
    exit 1
}
# The pinned FFmpeg revision uses ff_af_queue_* from AC3 without selecting its
# object when only this audio encoder is enabled. Fix the dependency, rather than
# pulling in an unrelated encoder merely to make the shared library loadable.
ffmpeg_configure="${1}/deps/ffmpeg/configure"
if [[ -f "$ffmpeg_configure" ]]; then
    sed -i.bak '/^ac3_encoder_select=/ { /audio_frame_queue/! s/ac3_encoder_select="/ac3_encoder_select="audio_frame_queue /; }' "$ffmpeg_configure"
fi
