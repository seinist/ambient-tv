#!/usr/bin/env bash
set -euo pipefail
serial="${1:-emulator-5554}"
case "$serial" in emulator-[0-9]*) ;; *) echo "An emulator serial is required" >&2; exit 2 ;; esac
cd "$(dirname "$0")/.."
abi="$(rtk proxy adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$abi" in x86|x86_64|arm64-v8a|armeabi-v7a) ;; *) exit 2 ;; esac
audit_dir="$(mktemp -d "${XDG_CACHE_HOME:-$HOME/.cache}/amber-channel-profile-audit.XXXXXX")"
printf "gradle.beforeProject { p -> if (p.name == 'app') p.layout.buildDirectory.set(new File('%s', p.name)) }\n" "$audit_dir" > "$audit_dir/isolate.gradle"
echo "Channel profile audit artifacts: $audit_dir"
rtk proxy ./gradlew -I "$audit_dir/isolate.gradle" --project-cache-dir "$audit_dir/.gradle" :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest \
    --tests '*LiveRendererSelectionTest' --tests '*AutomaticPlaybackProfileTest' --tests '*AudioSessionTitleDelayTest' --tests '*TitleVideoSettingsTest' --tests '*ResolutionVideoProfileTest' --tests '*RendererCapabilityPolicyTest' --tests '*LocalAudioFallbackPolicyTest' \
    -PAPK_ABI="$abi" -PAUDIT_APP_SUFFIX=.channelprofileaudit --offline --console=plain
# These packages contain only this audit's generated fixtures. Removing the previous
# installation avoids requiring space for two copies of the native debug libraries.
rtk proxy adb -s "$serial" uninstall com.ambient.tv.channelprofileaudit.test >/dev/null 2>&1 || true
rtk proxy adb -s "$serial" uninstall com.ambient.tv.channelprofileaudit >/dev/null 2>&1 || true
rtk adb -s "$serial" install "$audit_dir/app/outputs/apk/debug/app-$abi-debug.apk"
rtk adb -s "$serial" install -r "$audit_dir/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
result="$(rtk proxy adb -s "$serial" shell am instrument -w -e channelProfiles true \
    com.ambient.tv.channelprofileaudit.test/com.ambient.tv.ui.player.PlaybackAuditInstrumentation)"
printf '%s\n' "$result"
[[ "$result" == *"CHANNEL PROFILE AUDIT PASSED"* && "$result" != *"FAIL:"* ]]
