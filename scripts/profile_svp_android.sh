#!/usr/bin/env bash
# Build an instrumented standalone SVP benchmark, collect representative
# profiles on an explicitly selected Android device, and merge by ABI.
# Never installs an APK or replaces any packaged runtime.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
abi="${1:-arm64-v8a}"
dest="${2:?Usage: profile_svp_android.sh ABI OUTPUT_DIRECTORY [DEVICE_SERIAL]}"
serial="${3:-${ANDROID_SERIAL:-}}"
case "$abi" in
    arm64-v8a) target=aarch64-linux-android;;
    armeabi-v7a) target=armv7-linux-androideabi;;
    x86) target=i686-linux-android;;
    x86_64) target=x86_64-linux-android;;
    *) echo "Unsupported ABI: $abi" >&2; exit 2;;
esac
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}/ndk/30.0.16248370}"
RUSTC_BIN="$(rustup which --toolchain stable rustc)"
export PATH="$(dirname "$RUSTC_BIN"):$HOME/.cargo/bin:$PATH"
[[ -z "${CARGO_ENCODED_RUSTFLAGS:-}" ]] || { echo "Profiling requires unencoded RUSTFLAGS" >&2; exit 2; }
adb=(adb)
[[ -z "$serial" ]] || adb+=(-s "$serial")
"${adb[@]}" get-state >/dev/null
device_abis="$("${adb[@]}" shell getprop ro.product.cpu.abilist | tr -d '\r')"
case ",$device_abis," in *",$abi,"*) ;; *) echo "Device does not support $abi: $device_abis" >&2; exit 2;; esac
source_hash="$(python3 "$ROOT/scripts/svp_dependency.py" --fingerprint)"
printf '%s\n' "$source_hash"
mkdir -p "$dest"
dest="$(cd "$dest" && pwd)"
tmp="$(mktemp -d)"
remote="/data/local/tmp/ambient-svp-pgo-$(basename "$tmp" | tr -cd '[:alnum:]')"
remote_created=0
cleanup() {
    if [[ "$remote_created" == 1 ]]; then "${adb[@]}" shell rm -rf "$remote" >/dev/null 2>&1 || true; fi
    rm -rf "$tmp"
}
trap cleanup EXIT
rustup component add --toolchain stable llvm-tools-preview
host="$(rustc -vV | sed -n 's/^host: //p')"
profdata="$(rustc --print sysroot)/lib/rustlib/$host/bin/llvm-profdata"
[[ -x "$profdata" ]]
# Separate build directory and flags: instrumented binaries cannot end up
# in regular Android build cache or jniLibs.
export CARGO_TARGET_DIR="$tmp/target"
source "$ROOT/scripts/svp_pgo_metadata.sh"
base_flags="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"
export RUSTFLAGS="$base_flags -C profile-generate"
(
    cd "$ROOT/native/ambientsvp"
    cargo ndk -t "$abi" -P 29 build --release --locked --bin benchmark
)
binary="$CARGO_TARGET_DIR/$target/release/benchmark"
[[ -s "$binary" ]]
"${adb[@]}" shell mkdir "$remote"
remote_created=1
"${adb[@]}" push "$binary" "$remote/benchmark"
"${adb[@]}" shell chmod 700 "$remote/benchmark"
# Synthetic training set deliberately includes CPU and hardware Vulkan
# attempts, different resolutions, cadences and analysis presets.
# Profile generation adds overhead, so these runs are not benchmarks.
run=0
for backend in 1 2; do
    for workload in '320 180 72 24 60 1' '640 360 48 24 60 2' '1280 720 36 25 50 1' '1920 1080 36 24 60 1'; do
        read -r w h frames source target_fps preset <<<"$workload"
        run=$((run+1))
        "${adb[@]}" shell "LLVM_PROFILE_FILE=$remote/run-$run-%p.profraw $remote/benchmark $w $h $frames $backend $source 1 --target=$target_fps --preset=$preset --no-checksum"
    done
done
mkdir "$tmp/profiles"
"${adb[@]}" pull "$remote/." "$tmp/profiles/"
mapfile -d '' profiles < <(find "$tmp/profiles" -name '*.profraw' -size +0c -print0)
(( ${#profiles[@]} >= run )) || { echo "Missing profile output(s)" >&2; exit 1; }
"$profdata" merge -o "$tmp/$abi.profdata" "${profiles[@]}"
[[ -s "$tmp/$abi.profdata" ]]
[[ "$(python3 "$ROOT/scripts/svp_dependency.py" --fingerprint)" == "$source_hash" ]] || { echo "SVP sources changed during training; discarding profile" >&2; exit 1; }
svp_pgo_metadata "$source_hash" "$abi" "$tmp/$abi.profdata" "$base_flags" > "$tmp/$abi.meta"
mv "$tmp/$abi.profdata" "$dest/$abi.profdata"
mv "$tmp/$abi.meta" "$dest/$abi.meta"
printf '%s\n' "Synthetic device-training profile: $dest/$abi.profdata" "Build the non-instrumented runtime with SVP_PGO_PROFILE_DIR=$dest and measure it separately."
