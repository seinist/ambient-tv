#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
AAR=native/vlc-aar/build/experimental/libvlc-vulkan-ahb-experimental.aar
RECEIPT=native/vlc-aar/build/experimental/native-link-receipt.txt
AUDIT=native/vlc-aar/seek-audit
# Default is a fresh build for the current checkout; CI supplies its just-built
# AAR and exact receipt. No installed production APK is touched.
if [[ "${1:-}" != --prepare-only ]]; then
    VLC_EXPERIMENTAL_AHB=1 VLC_VULKAN_ABIS=arm64-v8a VLC_NATIVE_LINK_RECEIPT="$RECEIPT" bash scripts/build_vlc_vulkan.sh
fi
test -s "$AAR" && test -s "$RECEIPT"
grep -Fx "ambient_head=$(git rev-parse HEAD)" "$RECEIPT"
grep -Fx "source_hash=$(VLC_EXPERIMENTAL_AHB=1 bash scripts/build_vlc_vulkan.sh --hash)" "$RECEIPT"
./gradlew -p "$AUDIT" assembleDebug
APK="$AUDIT/build/outputs/apk/debug/seek-audit-debug.apk"
test -s "$APK"
mkdir -p "$AUDIT/build"
python3 "$AUDIT/generate_fixture.py" "$AUDIT/build/seek-fixture.mp4"
# Keep the AAR's ELF bytes intact in the APK (AGP may otherwise strip them).
python3 - "$AAR" "$APK" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as a, zipfile.ZipFile(sys.argv[2]) as p:
    for name in ("libvlc.so","libvlcjni.so"):
        assert a.read("jni/arm64-v8a/"+name)==p.read("lib/arm64-v8a/"+name), name
PY
[[ "${1:-}" == --prepare-only ]] && exit 0
adb get-state
[[ "$(adb shell getprop ro.product.cpu.abilist)" == *arm64-v8a* ]]
PACKAGE=com.ambient.vlcseekaudit
adb install -r "$APK"
adb shell run-as "$PACKAGE" mkdir -p files
adb shell -T "run-as $PACKAGE sh -c 'cat > files/seek-fixture.mp4'" < "$AUDIT/build/seek-fixture.mp4"
result="$(adb shell am instrument -w "$PACKAGE/.SeekAudit")"
printf '%s\n' "$result"
grep -Fq 'VLC AFM3 REAL SEEK E2E PASSED' <<< "$result"
grep -Fq 'INSTRUMENTATION_CODE: -1' <<< "$result"
