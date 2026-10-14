#!/usr/bin/env bash
# Isolated TV D-pad audit: lazy/settings lists, grids, columns, modal actions and handheld opt-out.
set -euo pipefail
serial="${1:-emulator-5554}"
case "$serial" in emulator-[0-9]*) ;; *) echo "An emulator serial is required" >&2; exit 2 ;; esac
cd "$(dirname "$0")/.."
abi="$(rtk proxy adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$abi" in x86|x86_64|arm64-v8a|armeabi-v7a) ;; *) echo "Unsupported ABI: $abi" >&2; exit 2 ;; esac
rtk proxy ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PAPK_ABI="$abi" \
    -PAUDIT_APP_SUFFIX=.focusaudit --no-daemon --console=plain
rtk proxy adb -s "$serial" install -r "app/build/outputs/apk/debug/app-$abi-debug.apk"
rtk proxy adb -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
result="$(rtk proxy adb -s "$serial" shell am instrument -w -e scrollFocus true \
    com.ambient.tv.focusaudit.test/com.ambient.tv.ui.player.PlaybackAuditInstrumentation)"
printf '%s\n' "$result"
[[ "$result" == *"SCROLL FOCUS AUDIT PASSED"* && "$result" != *"FAIL:"* ]]
