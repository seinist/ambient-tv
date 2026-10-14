#!/usr/bin/env bash
# Isolated native output-switch and D-pad regression test; no configured servers are used.
set -euo pipefail
serial="${1:-emulator-5554}"
case "$serial" in emulator-[0-9]*) ;; *) echo "An emulator serial is required" >&2; exit 2 ;; esac
cd "$(dirname "$0")/.."
abi="$(rtk proxy adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$abi" in x86|x86_64|arm64-v8a|armeabi-v7a) ;; *) echo "Unsupported ABI: $abi" >&2; exit 2 ;; esac
fixture_dir="$(mktemp -d /tmp/ambient-output-audit.XXXXXX)"
rtk proxy ffmpeg -hide_banner -loglevel error -f lavfi -i testsrc2=size=640x360:rate=25 \
    -f lavfi -i sine=frequency=440:sample_rate=48000 -t 45 -c:v libx264 -preset ultrafast \
    -pix_fmt yuv420p -c:a aac -movflags +faststart "$fixture_dir/clip.mp4"
rtk adb -s "$serial" push "$fixture_dir/clip.mp4" /data/local/tmp/ambient-output-audit.mp4
rtk proxy ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PAPK_ABI="$abi" -PAUDIT_APP_SUFFIX=.outputaudit --no-build-cache
rtk adb -s "$serial" install -r "app/build/outputs/apk/debug/app-$abi-debug.apk"
rtk adb -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
result="$(rtk proxy adb -s "$serial" shell am instrument -w -e outputSwitch true \
    -e clip file:/data/local/tmp/ambient-output-audit.mp4 \
    com.ambient.tv.outputaudit.test/com.ambient.tv.ui.player.PlaybackAuditInstrumentation)"
printf '%s\n' "$result"
[[ "$result" == *"OUTPUT SWITCH AUDIT PASSED"* && "$result" != *"FAIL:"* ]]

[[ "$result" == *"PASS VLC native seek zero / supersession / paused handoffs / filter reopen / moving picture"* ]]
