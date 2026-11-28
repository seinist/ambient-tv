#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
AAR=native/vlc-aar/build/experimental/libvlc-vulkan-ahb-experimental.aar
RECEIPT=native/vlc-aar/build/experimental/native-link-receipt.txt
AUDIT=native/vlc-aar/seek-audit
ABI="${VLC_SEEK_AUDIT_ABI:-arm64-v8a}"
[[ "$ABI" == arm64-v8a || "$ABI" == x86_64 ]]
mode="${1:-}"
[[ -z "$mode" || "$mode" == --prepare-only || "$mode" == --run-prepared ]]
# Default is a fresh build for the current checkout; CI supplies its just-built
# AAR and exact receipt. No installed production APK is touched.
if [[ -z "$mode" ]]; then
    VLC_EXPERIMENTAL_AHB=1 VLC_VULKAN_ABIS="$ABI" VLC_NATIVE_LINK_RECEIPT="$RECEIPT" bash scripts/build_vlc_vulkan.sh
fi
test -s "$AAR" && test -s "$RECEIPT"
grep -Fx "ambient_head=$(git rev-parse HEAD)" "$RECEIPT"
grep -Fx "source_hash=$(VLC_EXPERIMENTAL_AHB=1 bash scripts/build_vlc_vulkan.sh --hash)" "$RECEIPT"
grep -Fx "aar_sha256=$(sha256sum "$AAR" | cut -d ' ' -f1)" "$RECEIPT"
APK="$AUDIT/build/outputs/apk/debug/seek-audit-debug.apk"
if [[ "$mode" != --run-prepared ]]; then
    ./gradlew -p "$AUDIT" -PAUDIT_ABI="$ABI" assembleDebug
    mkdir -p "$AUDIT/build"
    python3 "$AUDIT/generate_fixture.py" "$AUDIT/build/seek-fixture.mp4"
fi
test -s "$APK" && test -s "$AUDIT/build/seek-fixture.mp4"
# Keep the AAR's ELF bytes intact in the APK (AGP may otherwise strip them).
python3 - "$AAR" "$APK" "$ABI" "$RECEIPT" <<'PY'
import hashlib, sys, zipfile
from pathlib import Path
expected = {}
active = False
for line in Path(sys.argv[4]).read_text().splitlines():
    if line.startswith("abi="):
        active = line == "abi=" + sys.argv[3]
    elif active and "  " in line:
        digest, name = line.split("  ", 1)
        expected[name] = digest
with zipfile.ZipFile(sys.argv[1]) as a, zipfile.ZipFile(sys.argv[2]) as p:
    for name in ("libvlc.so","libvlcjni.so"):
        linked = a.read("jni/"+sys.argv[3]+"/"+name)
        assert hashlib.sha256(linked).hexdigest() == expected[name], name + " receipt"
        assert linked == p.read("lib/"+sys.argv[3]+"/"+name), name + " APK"
PY
if [[ "$mode" != --run-prepared ]]; then
    (cd "$AUDIT/build"; sha256sum outputs/apk/debug/seek-audit-debug.apk seek-fixture.mp4 > seek-audit.sha256)
fi
(cd "$AUDIT/build"; sha256sum --strict -c seek-audit.sha256)
[[ "$mode" == --prepare-only ]] && exit 0
adb get-state
[[ ",$(adb shell getprop ro.product.cpu.abilist | tr -d '\r')," == *",$ABI,"* ]]
PACKAGE=com.ambient.vlcseekaudit
adb install -r "$APK"
adb shell run-as "$PACKAGE" mkdir -p files
adb shell -T "run-as $PACKAGE sh -c 'cat > files/seek-fixture.mp4'" < "$AUDIT/build/seek-fixture.mp4"
adb logcat -c
# Surface native diagnostics immediately when an assertion fails, not only in the artifact.
trap 'status=$?; if (( status != 0 )); then adb logcat -d -t 1500 | grep -iE "vlc|AMBIENT|AndroidRuntime" || true; fi' EXIT
# Android's pretty instrumentation output prints the stream bundle but omits
# the final result code when a stream is present. Request raw output so both
# the explicit test marker and Activity.RESULT_OK remain mandatory.
run_audit() {
    local marker="$1" result
    shift
    result="$(adb shell am instrument -w -r "$@" "$PACKAGE/.SeekAudit" | tr -d '\r')"
    printf '%s\n' "$result"
    if ! grep -Fxq "INSTRUMENTATION_RESULT: stream=$marker" <<< "$result" ||
       ! grep -Fxq 'INSTRUMENTATION_CODE: -1' <<< "$result"; then
        echo "Instrumentation did not report successful completion: $marker" >&2
        return 1
    fi
}
run_audit 'VLC DISPLAY FAILURE RECOVERY PASSED' -e displayFailure true
# Prove the native display-start failure path actually executed.
adb logcat -d | grep -F 'AMBIENT_VOUT_START_FAILED' > /dev/null
run_audit 'VLC AFM3 REAL SEEK E2E PASSED'
