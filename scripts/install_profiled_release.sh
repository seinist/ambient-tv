#!/usr/bin/env bash
# Install a signed TV release together with its baseline profile on Android 9-11.
# On newer Android versions, install the embedded profile and request compilation explicitly.
# https://developer.android.com/topic/performance/baselineprofiles/manually-create-measure
set -euo pipefail
if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "Usage: $0 APK ADB_SERIAL [PACKAGE=com.ambient.tv]" >&2
  exit 2
fi
apk=$1
serial=$2
package=${3:-com.ambient.tv}
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
sdk=$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')
if (( sdk >= 28 && sdk <= 30 )); then
  dm="$work/$(basename "${apk%.apk}").dm"
  python3 - "$apk" "$dm" <<'PYTHON'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as apk, zipfile.ZipFile(sys.argv[2], "w", zipfile.ZIP_DEFLATED) as dm:
    for name in ("baseline.prof", "baseline.profm"):
        dm.writestr(name.replace("baseline", "primary"), apk.read("assets/dexopt/" + name))
PYTHON
  adb -s "$serial" install-multiple -r "$apk" "$dm"
else
  adb -s "$serial" install -r "$apk"
  adb -s "$serial" shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE \
    -n "$package/androidx.profileinstaller.ProfileInstallReceiver"
  adb -s "$serial" shell cmd package compile -f -m speed-profile "$package"
fi
