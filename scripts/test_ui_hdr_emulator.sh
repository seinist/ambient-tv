#!/usr/bin/env bash
# Isolated SDR fallback / settings / dialog audit. Does not certify physical HDR output.
set -euo pipefail
serial="${1:-emulator-5554}"
case "$serial" in emulator-[0-9]*) ;; *) echo "An emulator serial is required" >&2; exit 2 ;; esac
cd "$(dirname "$0")/.."
abi="$(rtk proxy adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$abi" in x86|x86_64|arm64-v8a|armeabi-v7a) ;; *) echo "Unsupported ABI: $abi" >&2; exit 2 ;; esac
rtk proxy ./gradlew :app:testDebugUnitTest --tests '*UiHdr*Test' --tests '*SdrToHdr*Test' --tests '*SdrHdr*Test' --tests '*SettingsNavigationTest' \
    :app:assembleDebug :app:assembleDebugAndroidTest -PAPK_ABI="$abi" \
    -PAUDIT_APP_SUFFIX=.hdraudit --no-daemon --console=plain
rtk proxy adb -s "$serial" install -r "app/build/outputs/apk/debug/app-$abi-debug.apk"
rtk proxy adb -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
result="$(rtk proxy adb -s "$serial" shell am instrument -w -e uiHdr true \
    com.ambient.tv.hdraudit.test/com.ambient.tv.ui.player.PlaybackAuditInstrumentation)"
printf '%s\n' "$result"
[[ "$result" == *"UI HDR FALLBACK AUDIT PASSED"* && "$result" != *"FAIL:"* ]]
[[ "$result" == *"PASS HDR format changes rebuild buffers and rate; discontinuity preserves output; detached window restores colours before reuse"* ]]
[[ "$result" == *"PASS unsafe GPU close quarantines window and cancels callbacks; synchronous startup failures roll back without retry loops"* ]]
