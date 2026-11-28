#!/usr/bin/env bash
# Export a raw profile from an already-run projectM audio audit and merge it
# with the exact compiler's llvm-profdata. Usage:
#   bash scripts/collect_projectm_pgo.sh <adb-serial> <audit-package> <abi> <output-dir>
set -euo pipefail
[[ $# == 4 ]] || { echo "usage: $0 <adb-serial> <audit-package> <abi> <output-dir>" >&2; exit 2; }
serial="$1"; package="$2"; abi="$3"; out="$4"
case "$abi" in arm64-v8a|armeabi-v7a|x86|x86_64) ;; *) echo "Unsupported ABI $abi" >&2; exit 2 ;; esac
[[ "$package" =~ ^[a-zA-Z0-9._]+\.projectmaudit$ ]] || { echo "Expected projectM audit package" >&2; exit 2; }
ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
[[ -d "$ndk" ]] || { echo "Set ANDROID_NDK_HOME to the NDK used to build the instrumented library" >&2; exit 2; }
case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) host=linux-x86_64 ;;
  Darwin-x86_64|Darwin-arm64) host=darwin-x86_64 ;;
  *) echo "Unsupported host" >&2; exit 2 ;;
esac
profdata="$ndk/toolchains/llvm/prebuilt/$host/bin/llvm-profdata"
[[ -x "$profdata" ]]
mkdir -p "$out"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
adb -s "$serial" exec-out run-as "$package" cat cache/projectm-audit.profraw > "$tmp/projectm.profraw"
[[ -s "$tmp/projectm.profraw" ]]
"$profdata" merge --failure-mode=any "$tmp/projectm.profraw" -o "$tmp/$abi.profdata"
"$profdata" show "$tmp/$abi.profdata"
cp "$tmp/$abi.profdata" "$out/$abi.profdata"
echo "Saved $out/$abi.profdata; use PROJECTM_PGO_DIR=$out and matching compiler/source/configuration."
