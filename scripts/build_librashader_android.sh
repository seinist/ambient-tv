#!/usr/bin/env bash
# Vulkan-only librashader C API, pinned and built separately for each Android ABI.
# MPL-2.0 OR GPL-3.0-only; native artifacts are included in ABI-specific app APKs.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
prop() { sed -n "s/^$1=//p" "$ROOT/gradle/external-versions.properties" | head -1; }
REPO="$(prop LIBRASHADER_REPO)"
PIN="$(prop LIBRASHADER_COMMIT)"
[[ "$REPO" == "https://github.com/SnowflakePowered/librashader" && "$PIN" =~ ^[0-9a-f]{40}$ ]] ||
    { echo "Invalid librashader source pin" >&2; exit 1; }
NDK="${ANDROID_NDK_HOME:-}"
[[ -n "$NDK" ]] || NDK="${ANDROID_NDK_ROOT:-}"
[[ -n "$NDK" ]] || NDK="${ANDROID_HOME:-}/ndk/30.0.16248370"
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
[[ -x "$BIN/llvm-strip" ]] || { echo "NDK 30 required" >&2; exit 1; }
command -v cargo >/dev/null && command -v rustup >/dev/null ||
    { echo "Rust and rustup required" >&2; exit 1; }
WORK="$ROOT/third_party/.librashader-src"
CARGO_CACHE="$ROOT/third_party/.librashader-target"
OUTPUT="$ROOT/app/src/main/jniLibs"
mkdir -p "$ROOT/third_party" "$CARGO_CACHE"
if [[ ! -f "$WORK/.ambient-pin" || "$(cat "$WORK/.ambient-pin")" != "$PIN" ]]; then
    rm -rf "$WORK"
    mkdir -p "$WORK"
    git -C "$WORK" init -q
    git -C "$WORK" remote add origin "$REPO"
    git -C "$WORK" fetch -q --depth 1 origin "$PIN"
    git -C "$WORK" checkout -q FETCH_HEAD
    [[ "$(git -C "$WORK" rev-parse HEAD)" == "$PIN" ]] || exit 1
    printf '%s' "$PIN" > "$WORK/.ambient-pin"
fi
export CARGO_TARGET_DIR="$CARGO_CACHE"
export CARGO_PROFILE_RELEASE_LTO=true
export CARGO_PROFILE_RELEASE_CODEGEN_UNITS=1
export CARGO_PROFILE_RELEASE_PANIC=abort
export CARGO_PROFILE_RELEASE_OPT_LEVEL=z
export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"
export PATH="$BIN:$PATH"
for abi in ${LIBRASHADER_ABIS:-arm64-v8a armeabi-v7a x86 x86_64}; do
    case "$abi" in
      arm64-v8a) target=aarch64-linux-android; clang=aarch64-linux-android29-clang ;;
      armeabi-v7a) target=armv7-linux-androideabi; clang=armv7a-linux-androideabi29-clang ;;
      x86) target=i686-linux-android; clang=i686-linux-android29-clang ;;
      x86_64) target=x86_64-linux-android; clang=x86_64-linux-android29-clang ;;
      *) echo "Unknown ABI $abi" >&2; exit 1 ;;
    esac
    dest="$OUTPUT/$abi/liblibrashader_capi.so"
    stamp="$OUTPUT/$abi/.librashader-vulkan-pin"
    expected="$PIN runtime-vulkan api5 abi2 lto 16k"
    if [[ -f "$dest" && -f "$stamp" && "$(cat "$stamp")" == "$expected" ]]; then
        echo "Pinned librashader $abi already built"
        continue
    fi
    rustup target add "$target"
    case "$target" in
      aarch64-linux-android) export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$BIN/$clang" ;;
      armv7-linux-androideabi) export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$BIN/$clang" ;;
      i686-linux-android) export CARGO_TARGET_I686_LINUX_ANDROID_LINKER="$BIN/$clang" ;;
      x86_64-linux-android) export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$BIN/$clang" ;;
    esac
    cargo build --manifest-path "$WORK/Cargo.toml" -p librashader-capi \
        --locked --release --target "$target" --no-default-features --features runtime-vulkan
    mkdir -p "$OUTPUT/$abi"
    cp "$CARGO_CACHE/$target/release/liblibrashader_capi.so" "$dest"
    "$BIN/llvm-strip" --strip-unneeded "$dest"
    python3 "$ROOT/scripts/check_elf_page_alignment.py" "$BIN/llvm-readelf" "$dest"
    printf '%s' "$expected" > "$stamp"
    printf '%s: %s bytes\n' "$abi" "$(stat -c%s "$dest")"
done
