#!/usr/bin/env bash
# Side-by-side audit APK; real MPV playback, blocked owner, coalescing, surface lifetime and stop.
set -euo pipefail
serial="${1:-emulator-5554}"
cd "$(dirname "$0")/.."
abi="$(rtk proxy adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$abi" in x86|x86_64|arm64-v8a|armeabi-v7a) ;; *) echo "Unsupported ABI: $abi" >&2; exit 2 ;; esac
fixture_dir="$(mktemp -d /tmp/mpv-responsiveness.XXXXXX)"
trap 'rm -rf "$fixture_dir"' EXIT
rtk proxy ffmpeg -hide_banner -loglevel error -f lavfi -i testsrc2=size=640x360:rate=25 \
    -f lavfi -i sine=frequency=440:sample_rate=48000 -t 60 -c:v libx264 -preset ultrafast \
    -pix_fmt yuv420p -c:a aac -movflags +faststart "$fixture_dir/clip.mp4"
rtk adb -s "$serial" push "$fixture_dir/clip.mp4" /data/local/tmp/mpv-responsiveness.mp4
rtk proxy ./gradlew :app:testDebugUnitTest --tests 'com.ambient.tv.ui.player.backend.Mpv*' \
    --tests 'com.ambient.tv.ui.player.PlaybackOutputChangeWindowTest' \
    :app:assembleDebug :app:assembleDebugAndroidTest \
    -PAPK_ABI="$abi" -PAUDIT_APP_SUFFIX=.mpvresponsiveness
rtk adb -s "$serial" install -r "app/build/outputs/apk/debug/app-$abi-debug.apk"
rtk adb -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
result="$(rtk proxy adb -s "$serial" shell am instrument -w -e mpvResponsiveness true \
    -e clip file:/data/local/tmp/mpv-responsiveness.mp4 \
    com.ambient.tv.mpvresponsiveness.test/com.ambient.tv.ui.player.PlaybackAuditInstrumentation)"
printf '%s\n' "$result"
[[ "$result" == *"MPV RESPONSIVENESS AUDIT PASSED"* && "$result" != *"FAIL:"* ]]
