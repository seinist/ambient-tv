#!/usr/bin/env bash
# Isolated app; production MPV, scaler panel, D-pad selection, moving video and restart state.
set -euo pipefail
serial="${1:-emulator-5554}"
output="${2:-gpu}"
cd "$(dirname "$0")/.."
abi="$(rtk proxy adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$abi" in x86|x86_64|arm64-v8a|armeabi-v7a) ;; *) echo "Unsupported ABI: $abi" >&2; exit 2 ;; esac
case "$output" in gpu|vulkan) ;; *) echo "Output must be gpu or vulkan" >&2; exit 2 ;; esac
artifacts="$(mktemp -d /tmp/ambient-mpv-upscalers.XXXXXX)"
echo "Audit evidence: $artifacts"
rtk proxy ffmpeg -hide_banner -loglevel error -f lavfi -i testsrc2=size=320x180:rate=24 \
    -f lavfi -i sine=frequency=440:sample_rate=48000 -t 300 -c:v libx264 -preset ultrafast \
    -pix_fmt yuv420p -color_primaries bt709 -color_trc bt709 -colorspace bt709 \
    -c:a aac -movflags +faststart "$artifacts/clip.mp4"
rtk adb -s "$serial" push "$artifacts/clip.mp4" /data/local/tmp/ambient-upscaler-clip.mp4
# Test the packaged native libraries without rebuilding them or advancing the release counter.
rtk proxy env SKIP_MPV_NATIVE=1 ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
    -PAPK_ABI="$abi" -PAUDIT_APP_SUFFIX=.upscaleraudit -PversionCode=1 -PversionName=upscaler-audit
rtk adb -s "$serial" install -r -d "app/build/outputs/apk/debug/app-$abi-debug.apk"
rtk adb -s "$serial" install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
rtk proxy adb -s "$serial" shell am instrument -w -e mpvUpscalers true \
    -e clip file:/data/local/tmp/ambient-upscaler-clip.mp4 -e output "$output" \
    com.ambient.tv.upscaleraudit.test/com.ambient.tv.ui.player.PlaybackAuditInstrumentation \
    > "$artifacts/result.txt" 2>&1
rtk proxy adb -s "$serial" logcat -d -s AmbientMPV > "$artifacts/native.log"
rtk proxy adb -s "$serial" exec-out run-as com.ambient.tv.upscaleraudit tar cf - files > "$artifacts/screenshots.tar"
cat "$artifacts/result.txt"
rtk rg -q 'MPV UPSCALER AUDIT PASSED' "$artifacts/result.txt"
! rtk rg -q 'FAIL:' "$artifacts/result.txt"
