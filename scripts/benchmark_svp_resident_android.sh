#!/usr/bin/env bash
# Standalone resident Vulkan measurement, not an installed app.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
abi="${1:-arm64-v8a}"
dest="${2:?Usage: benchmark_svp_resident_android.sh ABI OUTPUT_DIRECTORY DEVICE_SERIAL}"
serial="${3:-${ANDROID_SERIAL:-}}"
[[ -n "$serial" ]] || { echo "Select a physical Android device serial" >&2; exit 2; }
case "$abi" in
    arm64-v8a|armeabi-v7a|x86|x86_64) ;;
    *) echo "Unsupported ABI: $abi" >&2; exit 2;;
esac
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}/ndk/30.0.16248370}"
adb=(adb -s "$serial")
"${adb[@]}" get-state >/dev/null
[[ "$serial" != emulator-* && "$("${adb[@]}" shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]] || { echo "Emulators are not physical measurements" >&2; exit 2; }
device_abis="$("${adb[@]}" shell getprop ro.product.cpu.abilist | tr -d '\r')"
case ",$device_abis," in *",$abi,"*) ;; *) echo "Device ABI mismatch" >&2; exit 2;; esac
mkdir -p "$dest"
dest="$(cd "$dest" && pwd)"
tmp="$(mktemp -d)"
remote="/data/local/tmp/ambient-svp-bench-$(basename "$tmp" | tr -cd '[:alnum:]')"
created=0
cleanup() {
    if [[ "$created" == 1 ]]; then "${adb[@]}" shell rm -rf "$remote" >/dev/null 2>&1 || true; fi
    rm -rf "$tmp"
}
trap cleanup EXIT
export CARGO_TARGET_DIR="$tmp/target"
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"
fingerprint="$(python3 "$ROOT/scripts/svp_dependency.py" --fingerprint)"
(cd "$ROOT/native/ambientsvp"; cargo ndk -t "$abi" -P 29 test --release --locked --lib --no-run --message-format=json) >"$tmp/build.json"
binary="$(python3 - "$tmp/build.json" <<'PY'
import json,sys
found=[]
for line in open(sys.argv[1]):
    try: x=json.loads(line)
    except ValueError: continue
    if x.get("reason")=="compiler-artifact" and x.get("profile",{}).get("test") and x.get("executable") and x.get("target",{}).get("name")=="ambientsvp":
        found.append(x["executable"])
assert len(found)==1,found
print(found[0])
PY
)"
[[ -s "$binary" ]]
"${adb[@]}" shell mkdir "$remote"
created=1
"${adb[@]}" push "$binary" "$remote/test"
"${adb[@]}" shell chmod 700 "$remote/test"
report="$dest/svp-resident-$abi-$(date -u +%Y%m%dT%H%M%SZ).log"
{
    printf 'source_fingerprint=%s\nabi=%s\n' "$fingerprint" "$abi"
    for prop in ro.product.manufacturer ro.product.model ro.build.version.release ro.build.fingerprint; do
        printf '%s=' "$prop"; "${adb[@]}" shell getprop "$prop"
    done
    for workload in '640 360 12' '1280 720 12' '1920 1080 12'; do
        read -r w h runs <<<"$workload"
        "${adb[@]}" shell "AMBIENT_SVP_REQUIRE_HARDWARE=1 AMBIENT_SVP_BENCH_WIDTH=$w AMBIENT_SVP_BENCH_HEIGHT=$h AMBIENT_SVP_BENCH_RUNS=$runs $remote/test --ignored --exact resident_vulkan::gpu_tests::held_cache_and_submission_benchmark --nocapture"
    done
    [[ "$(python3 "$ROOT/scripts/svp_dependency.py" --fingerprint)" == "$fingerprint" ]]
} | tee "$report"
grep -q 'SVP_PARITY exact' "$report"
echo "Report: $report"
