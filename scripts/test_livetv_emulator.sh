#!/usr/bin/env bash
# Isolated Android-TV audit; never defaults to a connected physical device.
set -euo pipefail
serial="${1:-emulator-5554}"
case "$serial" in emulator-[0-9]*) ;; *) echo "An emulator serial is required" >&2; exit 2 ;; esac
cd "$(dirname "$0")/.."
rtk adb -s "$serial" get-state
abi="$(rtk proxy adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$abi" in x86|x86_64|arm64-v8a|armeabi-v7a) ;; *) echo "Unsupported emulator ABI: $abi" >&2; exit 2 ;; esac
fixture_dir="$(mktemp -d /tmp/ambient-livetv-audit.XXXXXX)"
rtk proxy ffmpeg -hide_banner -loglevel error -f lavfi -i testsrc2=size=640x360:rate=25 \
    -f lavfi -i sine=frequency=440:sample_rate=48000 -t 45 -c:v libx264 -preset ultrafast \
    -pix_fmt yuv420p -c:a aac -movflags +faststart "$fixture_dir/clip.mp4"
rtk proxy mkdir "$fixture_dir/hls"
rtk proxy ffmpeg -hide_banner -loglevel error -i "$fixture_dir/clip.mp4" -c copy \
    -hls_time 3 -hls_list_size 0 -hls_segment_filename "$fixture_dir/hls/segment-%03d.ts" "$fixture_dir/hls/index.m3u8"
rtk adb -s "$serial" push "$fixture_dir/clip.mp4" /data/local/tmp/livetv-audit-20261002.mp4
rtk adb -s "$serial" shell mkdir -p /data/local/tmp/livetv-hls-audit
for segment in "$fixture_dir"/hls/*; do
    rtk adb -s "$serial" push "$segment" "/data/local/tmp/livetv-hls-audit/$(basename "$segment")"
done
rtk proxy ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PAUDIT_APP_SUFFIX=.livetvaudit --offline
rtk adb -s "$serial" install -r "app/build/outputs/apk/debug/app-$abi-debug.apk"
rtk adb -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Android's am instrument can exit 0 even when this custom runner reports a failed assertion.
result="$(rtk proxy adb -s "$serial" shell am instrument -w -e liveTv true \
    com.ambient.tv.livetvaudit.test/com.ambient.tv.ui.player.PlaybackAuditInstrumentation)"
printf '%s\n' "$result"
[[ "$result" == *"LIVE TV AUDIT PASSED"* && "$result" != *"FAIL:"* ]]
