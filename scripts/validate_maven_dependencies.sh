#!/usr/bin/env bash
# Validate a staged catalog without building or deploying an APK.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
export SKIP_MPV_NATIVE=1
bash -n build.sh
python3 -m unittest discover -s scripts/tests -p 'test_maven_updates.py'
bash scripts/fetch_smarttube_engine.sh
bash scripts/fetch_libretrodroid.sh
python3 scripts/patch_smarttube_compat.py --check --root third_party/smarttube
./gradlew verifySmartTubeBuildGraph :app:compileDebugAndroidTestKotlin \
  :app:testDebugUnitTest :youtubeapi:testDebugUnitTest :sharedutils:testDebugUnitTest \
  :mediaserviceinterfaces:testDebugUnitTest :j2v8:testDebugUnitTest :libretrodroid:testDebugUnitTest \
  :app:checkDebugDuplicateClasses :app:checkReleaseDuplicateClasses :app:minifyReleaseWithR8 \
  -PAPK_ABI=arm64 -PADB_DEPLOY=false --no-configuration-cache --continue --stacktrace
