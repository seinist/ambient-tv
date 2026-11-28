#!/usr/bin/env bash
# Host-only provenance regression, with no Android SDK or device required.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$ROOT/scripts/svp_pgo_metadata.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
export ANDROID_NDK_HOME="$tmp/ndk"
mkdir "$ANDROID_NDK_HOME"
printf '%s\n' 'Pkg.Revision = 30.0.16248370' > "$ANDROID_NDK_HOME/source.properties"
printf '%s\n' 'profile data' > "$tmp/profile.profdata"
rustc() { printf '%s\n' "rustc ${TEST_RUSTC:-1.90.0}" 'host: x86_64-unknown-linux-gnu' 'LLVM version: 20.1.0'; }
source_id=0123456789abcdef
base=' -C link-arg=-Wl,-z,max-page-size=16384'
meta() { svp_pgo_metadata "$source_id" arm64-v8a "$tmp/profile.profdata" "$base"; }
first="$(meta)"
[[ "$(meta)" == "$first" ]]
[[ "$first" == format=2$'\n'* ]]
[[ "$(svp_pgo_metadata "$source_id" x86_64 "$tmp/profile.profdata" "$base")" != "$first" ]]
[[ "$(svp_pgo_metadata "$source_id" arm64-v8a "$tmp/profile.profdata" "$base -C opt-level=2")" != "$first" ]]
[[ "$(TEST_RUSTC=1.91.0 meta)" != "$first" ]]
[[ "$(svp_pgo_metadata changed arm64-v8a "$tmp/profile.profdata" "$base")" != "$first" ]]
printf '%s\n' 'Pkg.Revision = 31.0.0' > "$ANDROID_NDK_HOME/source.properties"
[[ "$(meta)" != "$first" ]]
printf '%s\n' 'Pkg.Revision = 30.0.16248370' > "$ANDROID_NDK_HOME/source.properties"
[[ "$(meta)" == "$first" ]]
printf '%s\n' 'changed profile data' > "$tmp/profile.profdata"
[[ "$(meta)" != "$first" ]]
: > "$tmp/profile.profdata"
if meta >/dev/null; then echo 'Empty profile accepted' >&2; exit 1; fi
echo 'SVP PGO provenance regression passed'
