#!/usr/bin/env bash
# Build a shared CPU + Vulkan interpolation engine, with no player/Python dependency.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Refuse mixed revisions even when existing .so files happen to be present.
SOURCE_HASH="$(python3 "$ROOT/scripts/svp_dependency.py" --fingerprint)"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}/ndk/30.0.16248370}"
RUSTC_BIN="$(rustup which --toolchain stable rustc)"
export PATH="$(dirname "$RUSTC_BIN"):$HOME/.cargo/bin:$PATH"
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"
TOOLCHAIN_KEY="$(rustc --version)|$(<"$ANDROID_NDK_HOME/source.properties")|$RUSTFLAGS|${CARGO_ENCODED_RUSTFLAGS:-}"
cd "$ROOT/native/ambientsvp"

# cargo-ndk copies hashed upstream cdylibs (libsvpflow_wasm-<meta>.so,
# libsvpflow1_vs-<meta>.so). Those crates are already statically linked into
# libambientsvp.so; leave only the final engine in jniLibs.
prune_svp_dep_libs() {
    local dest="$1"
    rm -f "$dest"/libsvpflow*.so
}

build_abis=()
build_keys=()
for abi in ${SVP_ABIS:-arm64-v8a armeabi-v7a x86 x86_64}; do
    case "$abi" in arm64-v8a|armeabi-v7a|x86|x86_64) ;; *) echo "Unsupported SVP ABI: $abi" >&2; exit 2;; esac
    dest="$ROOT/app/src/main/jniLibs/$abi"
    key="$(printf '%s\n' "$SOURCE_HASH" "$TOOLCHAIN_KEY" "$abi" | sha256sum | cut -d ' ' -f1)"
    # Always drop leftover dep cdylibs, including on cache hits from older builds.
    prune_svp_dep_libs "$dest"
    if [[ "${REBUILD_SVP:-0}" != 1 && -s "$dest/libambientsvp.so" && -f "$dest/.svp-engine" && "$(<"$dest/.svp-engine")" == "$key" ]]; then
        echo "SVP $abi: managed pin and build inputs unchanged; reusing runtime"
        continue
    fi
    build_abis+=("$abi")
    build_keys+=("$key")
done
if (( ${#build_abis[@]} == 0 )); then
    [[ -s "$ROOT/app/src/main/assets/licenses/open-svpflow/dependencies.tsv" ]] || bash "$ROOT/scripts/vendor_svp_licenses.sh"
    exit 0
fi
# API changes must pass the portable streaming/gating regressions before any
# Android runtime is replaced. Vulkan/device playback still needs its own audit.
cargo test --release --locked --no-default-features --lib --tests
for abi in "${build_abis[@]}"; do
    cargo ndk --manifest-path "$ROOT/native/ambientsvp/Cargo.toml" -t "$abi" -P 29 \
        -o "$ROOT/app/src/main/jniLibs" build --release --locked --lib
    prune_svp_dep_libs "$ROOT/app/src/main/jniLibs/$abi"
done
bash "$ROOT/scripts/vendor_svp_licenses.sh"
# Never mark a build ready before the whole requested set and notices succeed.
for index in "${!build_abis[@]}"; do
    printf '%s\n' "${build_keys[$index]}" > "$ROOT/app/src/main/jniLibs/${build_abis[$index]}/.svp-engine"
done
