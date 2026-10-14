#!/usr/bin/env bash
# Isolated Back/focus audit of production panels, Settings and sidebar.
set -euo pipefail
serial="${1:-emulator-5554}"
case "$serial" in emulator-[0-9]*) ;; *) echo "An emulator serial is required" >&2; exit 2 ;; esac
cd "$(dirname "$0")/.."
abi="$(rtk proxy adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$abi" in x86|x86_64|arm64-v8a|armeabi-v7a) ;; *) echo "Unsupported ABI: $abi" >&2; exit 2 ;; esac
audit_dir="$(mktemp -d "${XDG_CACHE_HOME:-$HOME/.cache}/amber-back-audit.XXXXXX")"
printf "gradle.beforeProject { p -> if (p.name == 'app') p.layout.buildDirectory.set(new File('%s', p.name)) }\n" "$audit_dir" > "$audit_dir/isolate.gradle"
rtk proxy ./gradlew -I "$audit_dir/isolate.gradle" --project-cache-dir "$audit_dir/.gradle" :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest \
    --tests '*PlayerPanelNavigationTest' --tests '*SidebarBackPolicyTest' \
    --tests '*SidebarNavigationPolicyTest' --tests '*PlayerBackPolicyTest' \
    --tests '*SettingsNavigationTest.back*' --tests '*SettingsNavigationTest.deepLink*' \
    --tests '*SettingsNavigationTest.singleSystem*' --tests '*SettingsNavigationTest.returnToSearch*' \
    --tests '*SettingsNavigationTest.left*' --tests '*DlnaBrowserFocusTest' --tests '*PlayerReturnFocusPolicyTest' \
    -PAPK_ABI="$abi" -PAUDIT_APP_SUFFIX=.backaudit --no-build-cache
rtk adb -s "$serial" install -r "$audit_dir/app/outputs/apk/debug/app-$abi-debug.apk"
rtk adb -s "$serial" install -r "$audit_dir/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
result="$(rtk proxy adb -s "$serial" shell am instrument -w -e backNavigation true \
    com.ambient.tv.backaudit.test/com.ambient.tv.ui.player.PlaybackAuditInstrumentation)"
printf '%s\n' "$result"
[[ "$result" == *"BACK NAVIGATION AUDIT PASSED"* && "$result" != *"FAIL:"* ]]
result="$(rtk proxy adb -s "$serial" shell am instrument -w -e scrollFocus true \
    com.ambient.tv.backaudit.test/com.ambient.tv.ui.player.PlaybackAuditInstrumentation)"
printf '%s\n' "$result"
[[ "$result" == *"SCROLL FOCUS AUDIT PASSED"* && "$result" != *"FAIL:"* ]]
