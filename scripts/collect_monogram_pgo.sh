#!/usr/bin/env bash
# Collect a device-generated profile from a debuggable Ambient TV training APK.
# Usage: collect_monogram_pgo.sh <application-id> <output.profdata>
set -euo pipefail
app="${1:?application id required}"
output="${2:?output profdata required}"
[[ "$app" =~ ^[A-Za-z0-9._]+$ ]] || exit 2
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
adb exec-out run-as "$app" cat files/monogram-pgo.profraw > "$scratch/device.profraw"
[[ -s "$scratch/device.profraw" ]] || { echo "No profile; exercise at least 16 native media windows first" >&2; exit 1; }
rustup component add llvm-tools-preview
sysroot="$(rustc --print sysroot)"
host="$(rustc -vV | sed -n 's/^host: //p')"
profdata="$sysroot/lib/rustlib/$host/bin/llvm-profdata"
"$profdata" merge -o "$output" "$scratch/device.profraw"
"$profdata" show --counts "$output" | tail -n 10
rustc -vV > "$output.toolchain"
echo "Use the identical compiler/ABI/source configuration for the profile-use build."
