#!/usr/bin/env bash
# Compare actual Monogram AAR/ELF sizes across compiler profiles. This is not
# an APK-size or device-throughput benchmark. Run monogram-size separately.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export MONOGRAM_ABIS="${MONOGRAM_ABIS:-arm64-v8a}"
for profile in z 2 3; do
    export MONOGRAM_OPT_LEVEL="$profile"
    started=$SECONDS
    bash "$root/scripts/build_monogram_native.sh" >&2
    python3 - "$root/app/libs/monogram-mtproto.aar" "$profile" "$((SECONDS-started))" <<'PY'
import os, sys, zipfile
path, profile, seconds = sys.argv[1:]
with zipfile.ZipFile(path) as aar:
    for entry in aar.infolist():
        if entry.filename.startswith("jni/") and entry.filename.endswith("/libmonogram_mtproto.so"):
            print(f"profile={profile} abi={entry.filename.split('/')[1]} raw_elf_bytes={entry.file_size} aar_bytes={os.path.getsize(path)} build_seconds={seconds}")
PY
done
# Restore the normal portable default after the comparison.
MONOGRAM_OPT_LEVEL=2 bash "$root/scripts/build_monogram_native.sh" >&2
