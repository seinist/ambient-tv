#!/usr/bin/env bash
# Pinned TLS, active-item status, transcode seek, stale command rejection and reconnect.
set -euo pipefail
serial="${1:-emulator-5554}"
case "$serial" in emulator-[0-9]*) ;; *) echo "An unlocked emulator is required" >&2; exit 2 ;; esac
cd "$(dirname "$0")/.."
abi="$(rtk proxy adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$abi" in x86|x86_64|arm64-v8a|armeabi-v7a) ;; *) echo "Unsupported ABI: $abi" >&2; exit 2 ;; esac
rtk proxy ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PAPK_ABI="$abi" \
    -PAUDIT_APP_SUFFIX=.companionaudit --no-daemon --console=plain
rtk proxy adb -s "$serial" install -r "app/build/outputs/apk/debug/app-$abi-debug.apk"
rtk proxy adb -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
result="$(rtk proxy adb -s "$serial" shell am instrument -w -e companion true \
    com.ambient.tv.companionaudit.test/com.ambient.tv.ui.player.PlaybackAuditInstrumentation)"
printf '%s\n' "$result"
[[ "$result" == *"COMPANION SECURITY AUDIT PASSED"* && "$result" != *"FAIL:"* ]]
[[ "$result" == *"PASS TV rejects a late seek after a title switch"* ]]
[[ "$result" == *"PASS foreground return reconnects the paired TV and refreshes status"* ]]
[[ "$result" == *"PASS stop clears the phone playback state"* ]]

[[ "$result" == *"PASS quality selection invokes the current source adapter"* ]]
[[ "$result" == *"PASS TV rejects an unknown option instead of selecting another track"* ]]
[[ "$result" == *"PASS a stale UI from another TV cannot control the selected receiver"* ]]
[[ "$result" == *"PASS TV rejects a stale queue edit revision"* ]]
[[ "$result" == *"PASS end of item advances the remaining explicit queue exactly once"* ]]
[[ "$result" == *"PASS queue checkpoint survives receiver store recreation"* ]]
[[ "$result" == *"PASS an older checkpoint cannot overwrite the current queue"* ]]
[[ "$result" == *"PASS interrupted queue write retains the last complete checkpoint"* ]]
[[ "$result" == *"PASS removed queue rows stay removed after recreation"* ]]
