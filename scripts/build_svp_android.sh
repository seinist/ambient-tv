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
build_flags=()
# Profile data is optional and ABI-specific. Never silently use a profile
# from another architecture or reuse a binary built from another profile.
PGO_DIR="${SVP_PGO_PROFILE_DIR:-}"
if [[ -n "$PGO_DIR" ]]; then
    PGO_DIR="$(cd "$PGO_DIR" && pwd)"
    [[ "$PGO_DIR" != *[[:space:]]* ]] || { echo "SVP PGO path must not contain whitespace" >&2; exit 2; }
fi
for abi in ${SVP_ABIS:-arm64-v8a armeabi-v7a x86 x86_64}; do
    case "$abi" in arm64-v8a|armeabi-v7a|x86|x86_64) ;; *) echo "Unsupported SVP ABI: $abi" >&2; exit 2;; esac
    dest="$ROOT/app/src/main/jniLibs/$abi"
    abi_flags="$RUSTFLAGS"
    profile_hash="none"
    if [[ -n "$PGO_DIR" ]]; then
        profile="$PGO_DIR/$abi.profdata"
        [[ -s "$profile" ]] || { echo "Missing SVP PGO profile: $profile" >&2; exit 2; }
        profile_hash="$(sha256sum "$profile" | cut -d ' ' -f1)"
        meta="$PGO_DIR/$abi.meta"
        [[ -s "$meta" ]] || { echo "Missing SVP PGO provenance: $meta (generate with profile_svp_android.sh)" >&2; exit 2; }
        expected_meta="$(printf '%s\n' "source=$SOURCE_HASH" "rustc=$(rustc --version)" "abi=$abi" "sha256=$profile_hash")"
        [[ "$(<"$meta")" == "$expected_meta" ]] || { echo "SVP PGO profile source, toolchain, ABI or digest mismatch: $profile" >&2; exit 2; }
        # Cargo's encoded flags take precedence over RUSTFLAGS; fail rather
        # than claim a PGO build in a configuration that would ignore it.
        [[ -z "${CARGO_ENCODED_RUSTFLAGS:-}" ]] || { echo "SVP PGO requires unencoded RUSTFLAGS" >&2; exit 2; }
        abi_flags+=" -C profile-use=$profile"
    fi
    key="$(printf '%s\n' "$SOURCE_HASH" "$TOOLCHAIN_KEY" "$abi" "$profile_hash" "$abi_flags" | sha256sum | cut -d ' ' -f1)"
    # Always drop leftover dep cdylibs, including on cache hits from older builds.
    prune_svp_dep_libs "$dest"
    if [[ "${REBUILD_SVP:-0}" != 1 && -s "$dest/libambientsvp.so" && -f "$dest/.svp-engine" && "$(<"$dest/.svp-engine")" == "$key" ]]; then
        echo "SVP $abi: managed pin and build inputs unchanged; reusing runtime"
        continue
    fi
    build_abis+=("$abi")
    build_keys+=("$key")
    build_flags+=("$abi_flags")
done
if (( ${#build_abis[@]} == 0 )); then
    [[ -s "$ROOT/app/src/main/assets/licenses/open-svpflow/dependencies.tsv" ]] || bash "$ROOT/scripts/vendor_svp_licenses.sh"
    exit 0
fi
# API changes must pass the portable streaming/gating regressions before any
# Android runtime is replaced. Vulkan/device playback still needs its own audit.
# Upstream metric.rs only imports _mm_unpacklo_epi64 with ssse3/avx2, which the
# Android x86_64 target enables but the default host target does not.
host_flags="$RUSTFLAGS"
case "$(uname -m)" in x86_64|i?86) host_flags+=" -C target-feature=+ssse3";; esac
RUSTFLAGS="$host_flags" \
    cargo test --release --locked --no-default-features --lib --tests
for index in "${!build_abis[@]}"; do
    abi="${build_abis[$index]}"
    RUSTFLAGS="${build_flags[$index]}" cargo ndk --manifest-path "$ROOT/native/ambientsvp/Cargo.toml" -t "$abi" -P 29 \
        -o "$ROOT/app/src/main/jniLibs" build --release --locked --lib
    prune_svp_dep_libs "$ROOT/app/src/main/jniLibs/$abi"
done
bash "$ROOT/scripts/vendor_svp_licenses.sh"
# Never mark a build ready before the whole requested set and notices succeed.
for index in "${!build_abis[@]}"; do
    printf '%s\n' "${build_keys[$index]}" > "$ROOT/app/src/main/jniLibs/${build_abis[$index]}/.svp-engine"
done
