#!/usr/bin/env bash
# Synthetic production Home/library layouts in an isolated APK; no configured media servers.
set -euo pipefail
serial="${1:-emulator-5554}"
mode="${2:-ui}"
args=()
if [[ "$mode" == "browse" ]]; then args=(-e browsePerformance true); fi
cd "$(dirname "$0")/.."
abi="$(adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$abi" in x86|x86_64|arm64-v8a|armeabi-v7a) ;; *) echo "Unsupported ABI: $abi" >&2; exit 2 ;; esac
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PAPK_ABI="$abi" \
    -PAUDIT_APP_SUFFIX=.uiperfaudit --no-daemon --console=plain
adb -s "$serial" install -r "app/build/outputs/apk/debug/app-$abi-debug.apk"
adb -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$serial" shell input keyevent KEYCODE_WAKEUP
result="$(adb -s "$serial" shell am instrument -w -e uiPerformance true "${args[@]}" \
    com.ambient.tv.uiperfaudit.test/com.ambient.tv.ui.player.PlaybackAuditInstrumentation)"
printf '%s\n' "$result"
[[ "$result" == *"UI PERFORMANCE AUDIT PASSED"* && "$result" != *"FAIL:"* ]]
if [[ "$mode" == "browse" ]]; then
    [[ "$result" == *"PASS idle prefetch runs at background priority="* ]]
    [[ "$result" == *"PASS browse-prefetch-2 input cancels active request"* ]]
    [[ "$result" == *"PASS deferred 1280-item snapshot persisted after navigation"* ]]
    budget_result="$(adb -s "$serial" shell am instrument -w -e browseCache write-budget \
        com.ambient.tv.uiperfaudit.test/com.ambient.tv.ui.player.PlaybackAuditInstrumentation)"
    printf '%s\n' "$budget_result"
    [[ "$budget_result" == *"BROWSE CACHE AUDIT PASSED"* && "$budget_result" != *"FAIL:"* ]]
    adb -s "$serial" shell input keyevent KEYCODE_WAKEUP
    visual_result="$(adb -s "$serial" shell am instrument -w -e uiPerformance true \
        -e browsePerformance true -e browseMode ambient-visual \
        com.ambient.tv.uiperfaudit.test/com.ambient.tv.ui.player.PlaybackAuditInstrumentation)"
    printf '%s\n' "$visual_result"
    [[ "$visual_result" == *"PASS ambient pauses during navigation and resumes without a phase jump"* ]]
    [[ "$visual_result" == *"UI PERFORMANCE AUDIT PASSED"* && "$visual_result" != *"FAIL:"* ]]
    exit 0
fi
[[ "$result" == *"PASS Home mask records its child draw tree once"* ]]
for scenario in home-depth home-static library-grid library-landscape library-info library-cinematic; do
    [[ "$result" == *"PASS FrameMetrics captured for $scenario"* ]]
done
