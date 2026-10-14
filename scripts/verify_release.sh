#!/usr/bin/env bash
# Reproducible checks only: does not install an APK or update upstream pins.
set -euo pipefail
AUDIT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$AUDIT_ROOT"
python3 scripts/audit_source.py
for source in MediaServiceCore/youtubeapi SharedModules/sharedutils SharedModules/j2v8; do
    if [[ ! -d "third_party/smarttube/$source" ]]; then
        echo "Missing SmartTube sources: $source" >&2
        echo "Prepare the pinned sources with: bash scripts/fetch_smarttube_engine.sh" >&2
        exit 1
    fi
done
python3 scripts/patch_smarttube_compat.py --check --root third_party/smarttube
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    for library in libmpv.so libambientmpv.so libprojectM-4.so libambientprojectm.so libspotify_receiver_core.so; do
        if [[ ! -f "app/src/main/jniLibs/$abi/$library" ]]; then
            echo "Missing release runtime: $abi/$library" >&2
            echo "Prepare native engines through the normal build: ADB_DEPLOY=false bash build.sh release" >&2
            exit 1
        fi
    done
done
bash gradlew verifySmartTubeBuildGraph \
    :app:compileDebugKotlin :app:testDebugUnitTest :app:lintRelease :app:assembleRelease \
    --no-daemon -PADB_DEPLOY=false "$@"
