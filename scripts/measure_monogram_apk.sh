#!/usr/bin/env bash
# Build matched baseline and Monogram opt-in release APKs, then measure exact delta.
# Full native engines/Android SDK must be available as for a normal ./build.sh release.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
requested="${1:-arm64}"
case "$requested" in
    all)
        for mode in arm64 armv7 x86 x86_64; do
            bash "$0" "$mode"
        done
        exit 0
        ;;
    arm64|arm64-v8a) mode=arm64; abi=arm64-v8a ;;
    armv7|armeabi-v7a) mode=armv7; abi=armeabi-v7a ;;
    x86) mode=x86; abi=x86 ;;
    x86_64) mode=x86_64; abi=x86_64 ;;
    *) echo "Use: ./build.sh monogram-size [arm64|armv7|x86|x86_64|all]" >&2; exit 2 ;;
esac
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
cd "$root"
mkdir -p dist

copy_current_apk() {
    local dest="$1"
    python3 - "$root/app/build/outputs/apk/release/output-metadata.json" "$abi" "$dest" <<'PY'
import json
import shutil
import sys
import zipfile
from pathlib import Path

metadata = Path(sys.argv[1])
abi, dest = sys.argv[2], Path(sys.argv[3])
outputs = json.loads(metadata.read_text(encoding="utf-8"))["elements"]
matches = [e for e in outputs if abi in [
    f.get("value", f.get("identifier")) for f in e.get("filters", [])
    if f.get("filterType") == "ABI"
]]
if not matches and len(outputs) == 1 and not outputs[0].get("filters"):
    candidate = metadata.parent / outputs[0]["outputFile"]
    with zipfile.ZipFile(candidate) as apk:
        abis = {name.split("/")[1] for name in apk.namelist()
                if name.startswith("lib/") and name.endswith(".so")}
    if abis == {abi}:
        matches = outputs
if len(matches) != 1:
    raise SystemExit(f"Expected one ABI={abi} APK in {metadata}; got {len(matches)}")
source = metadata.parent / matches[0]["outputFile"]
if not source.is_file():
    raise SystemExit(f"Missing release APK: {source}")
shutil.copy2(source, dest)
print(f"Captured {abi} release APK: {source.name} ({dest.stat().st_size} bytes)")
PY
}

build_variant() {
    local enabled="$1"
    if [[ "${MONOGRAM_SIZE_GRADLE_ONLY:-0}" == 1 ]]; then
        # Both release APKs use the same local dependencies, R8 and ABI.
        # CI prepares the native AAR and the shared engine dependencies once.
        CI=1 MONOGRAM_NATIVE="$enabled" ./gradlew :app:packageRelease -PAPK_ABI="$abi" -PMONOGRAM_NATIVE="$enabled"
    else
        env CI=1 MONOGRAM_NATIVE="$enabled" bash "$root/build.sh" "$mode"
    fi
}

# CI=1 prevents the local versionCode auto-increment between the two variants.
# Both builds use identical source, ABI, version and release/R8 configuration.
echo "Building normal release baseline for $abi (Monogram disabled)..."
build_variant 0
copy_current_apk "$scratch/baseline.apk"

echo "Building optional Monogram release for $abi..."
build_variant 1
copy_current_apk "$scratch/monogram.apk"

python3 "$root/scripts/monogram_size_audit.py" \
    --abi "$abi" --aar "$root/app/libs/monogram-mtproto.aar" \
    --base "$scratch/baseline.apk" --monogram "$scratch/monogram.apk" \
    --max-native-mb 12 --max-apk-growth-mb 15 \
    --json "$root/dist/monogram-size-$abi.json" \
    --markdown "$root/dist/monogram-size-$abi.md"
echo "Size reports: dist/monogram-size-$abi.json and .md"
