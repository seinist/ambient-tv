#!/usr/bin/env bash
# Isolated low-resolution filter and D-pad audit; not a Shield performance benchmark.
set -euo pipefail
serial="${1:-emulator-5554}"
case "$serial" in emulator-[0-9]*) ;; *) echo 'An emulator serial is required' >&2; exit 2 ;; esac
cd "$(dirname "$0")/.."
abi="$(rtk proxy adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$abi" in x86|x86_64|arm64-v8a|armeabi-v7a) ;; *) echo "Unsupported ABI: $abi" >&2; exit 2 ;; esac
rtk proxy ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PAPK_ABI="$abi" -PAUDIT_APP_SUFFIX=.deintaudit --no-build-cache
rtk adb -s "$serial" install -r "app/build/outputs/apk/debug/app-$abi-debug.apk"
rtk adb -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
result="$(rtk proxy adb -s "$serial" shell am instrument -w -e deinterlace true \
    com.ambient.tv.deintaudit.test/com.ambient.tv.ui.player.PlaybackAuditInstrumentation)"
printf '%s\n' "$result"
[[ "$result" == *'DEINTERLACE AUDIT PASSED'* && "$result" != *'FAIL:'* ]]
