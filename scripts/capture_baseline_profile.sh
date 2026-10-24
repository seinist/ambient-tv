#!/usr/bin/env bash
# Record which app code really runs while navigating, and merge it into the baseline profile
# (app/src/main/baseline-prof.txt). That profile is compiled ahead of time at install, so menus
# do not stutter while ART is still interpreting/JIT-compiling them.
#
# Needs a TV/device on Android 13+ (API 33) and the release native inputs, like a release build.
# Uses the profileCapture build type: release code, not obfuscated (rules must name the real
# classes; R8 maps them for release), not debuggable. It installs beside the real app.
#
#   scripts/capture_baseline_profile.sh SERIAL            # you navigate the app, then press Enter
#   scripts/capture_baseline_profile.sh SERIAL audit      # synthetic Home/library tour (UiPerformanceAudit)
#
# Afterwards review and commit app/src/main/baseline-prof.txt.
set -euo pipefail
serial="${1:?Usage: $0 ADB_SERIAL [manual|audit]}"
mode="${2:-manual}"
package="com.ambient.tv.profilecapture"
cd "$(dirname "$0")/.."
adb_() { adb -s "$serial" "$@"; }

sdk="$(adb_ shell getprop ro.build.version.sdk | tr -d '\r')"
if (( sdk < 33 )); then
    echo "Android 13 (API 33) or newer is needed to read app profiles without root (device: API $sdk)." >&2
    exit 2
fi
abi="$(adb_ shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$abi" in x86|x86_64|arm64-v8a|armeabi-v7a) ;; *) echo "Unsupported ABI: $abi" >&2; exit 2 ;; esac

tasks=(:app:assembleProfileCapture)
[[ "$mode" == "audit" ]] && tasks+=(:app:assembleProfileCaptureAndroidTest)
./gradlew "${tasks[@]}" -PAPK_ABI="$abi" -PtestBuildType=profileCapture --no-daemon --console=plain
apk="$(ls app/build/outputs/apk/profileCapture/*"$abi"*.apk 2>/dev/null | head -n 1 || true)"
[[ -n "$apk" ]] || apk="$(ls app/build/outputs/apk/profileCapture/*.apk | head -n 1)"
adb_ install -r "$apk"

# Start from an empty profile so only what this session runs is recorded.
adb_ shell am force-stop "$package"
adb_ shell cmd package compile --reset "$package" >/dev/null
adb_ shell input keyevent KEYCODE_WAKEUP

if [[ "$mode" == "audit" ]]; then
    adb_ install -r "$(ls app/build/outputs/apk/androidTest/profileCapture/*.apk | head -n 1)"
    adb_ shell am instrument -w -e uiPerformance true -e browsePerformance true \
        "$package.test/com.ambient.tv.ui.player.PlaybackAuditInstrumentation"
else
    adb_ shell monkey -p "$package" -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1 ||
        adb_ shell monkey -p "$package" -c android.intent.category.LAUNCHER 1 >/dev/null
    echo
    echo "Ambient TV (profile capture) is running on the device. Set it up if needed, then use it"
    echo "for a few minutes the way you normally do: Home rows, details, libraries, live TV and"
    echo "guide, search, settings, the player OSD. Press Enter here when done."
    read -r
fi

work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
# Write the running app's profile to disk, then dump it as readable rules.
adb_ shell cmd package snapshot-profile "$package" >/dev/null 2>&1 || true
adb_ shell pm dump-profiles --dump-classes-and-methods "$package"
adb_ pull "/data/misc/profman/$package-primary.prof.txt" "$work/captured.prof.txt" >/dev/null
python3 scripts/baseline_profile.py "$work/captured.prof.txt" app/src/main/baseline-prof.txt
echo "Review app/src/main/baseline-prof.txt, then commit it. The profileCapture app can be removed:"
echo "  adb -s $serial uninstall $package"
