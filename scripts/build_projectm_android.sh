#!/usr/bin/env bash
set -euo pipefail

# Builds libprojectM + Ambient TV JNI bridge for Android ABIs (ARM + x86) and
# optionally fetches a MilkDrop preset pack + texture pack into app assets.
#
# Environment:
#   PROJECTM_REF=v4.1.7
#   PROJECTM_BUILD_ROOT=.native/projectm
#   PROJECTM_PRESET_PACK=cream|classic|original|none   (default: cream)
#   PROJECTM_FETCH_ASSETS=1                           (default: 1)
#   ANDROID_API=29
#   PROJECTM_OPT_LEVEL=2|3 (default: 3)
#   PROJECTM_LTO=ON|OFF (default: ON, Clang ThinLTO)
#   PROJECTM_ARM64_TUNE=generic|cortex-a57 (default: generic, ISA unchanged)
#   PROJECTM_SHADER_CACHE=ON|OFF (default: ON, bounded driver-keyed cache)
#   PROJECTM_DIRECT_CLASSIC_COMPOSITE=ON|OFF (default: OFF pending visual equivalence tests)
#   PROJECTM_PGO_DIR=<directory with <abi>.profdata for libprojectM> (optional)
#   PROJECTM_ABIS=<comma-separated ABIs> (default: all four; partial builds do not publish marker)
#
# Usage:
#   ./scripts/build_projectm_android.sh
#   ./scripts/build_projectm_android.sh --clean

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXTERNAL_VERSIONS="$ROOT_DIR/gradle/external-versions.properties"
external_version() { local key="$1"; sed -n "s/^${key}=//p" "$EXTERNAL_VERSIONS" | head -n 1; }
[[ -f "$EXTERNAL_VERSIONS" ]] || { echo "Missing $EXTERNAL_VERSIONS" >&2; exit 1; }
PROJECTM_REF="${PROJECTM_REF:-$(external_version PROJECTM_REF)}"
PROJECTM_BUILD_ROOT="${PROJECTM_BUILD_ROOT:-$ROOT_DIR/.native/projectm}"
PROJECTM_SRC="$PROJECTM_BUILD_ROOT/source"
PROJECTM_REPO="https://github.com/projectM-visualizer/projectm.git"
PROJECTM_PRESET_PACK="${PROJECTM_PRESET_PACK:-cream}"
PROJECTM_FETCH_ASSETS="${PROJECTM_FETCH_ASSETS:-1}"
ANDROID_API="${ANDROID_API:-29}"
PROJECTM_OPT_LEVEL="${PROJECTM_OPT_LEVEL:-3}"
PROJECTM_LTO="${PROJECTM_LTO:-ON}"
PROJECTM_ARM64_TUNE="${PROJECTM_ARM64_TUNE:-generic}"
PROJECTM_SHADER_CACHE="${PROJECTM_SHADER_CACHE:-ON}"
PROJECTM_DIRECT_CLASSIC_COMPOSITE="${PROJECTM_DIRECT_CLASSIC_COMPOSITE:-OFF}"
PROJECTM_PGO_DIR="${PROJECTM_PGO_DIR:-}"
PROJECTM_ABIS="${PROJECTM_ABIS:-arm64-v8a,armeabi-v7a,x86,x86_64}"
IFS=, read -r -a selected_abis <<< "$PROJECTM_ABIS"
[[ -n "$PROJECTM_ABIS" && "${#selected_abis[@]}" -ge 1 ]] || { echo "No projectM ABIs selected" >&2; exit 2; }
for abi in "${selected_abis[@]}"; do
  case "$abi" in arm64-v8a|armeabi-v7a|x86|x86_64) ;; *) echo "Unsupported ABI $abi" >&2; exit 2 ;; esac
done
if [[ -n "$PROJECTM_PGO_DIR" ]]; then
  PROJECTM_PGO_DIR="$(cd "$PROJECTM_PGO_DIR" && pwd)"
  for abi in "${selected_abis[@]}"; do
    [[ -s "$PROJECTM_PGO_DIR/$abi.profdata" ]] || { echo "Missing PGO profile for $abi" >&2; exit 2; }
  done
fi
[[ "$PROJECTM_DIRECT_CLASSIC_COMPOSITE" == ON || "$PROJECTM_DIRECT_CLASSIC_COMPOSITE" == OFF ]] || { echo "Invalid PROJECTM_DIRECT_CLASSIC_COMPOSITE" >&2; exit 2; }
[[ "$PROJECTM_SHADER_CACHE" == ON || "$PROJECTM_SHADER_CACHE" == OFF ]] || { echo "Invalid PROJECTM_SHADER_CACHE" >&2; exit 2; }
[[ "$PROJECTM_OPT_LEVEL" =~ ^[23]$ ]] || { echo "PROJECTM_OPT_LEVEL must be 2 or 3" >&2; exit 2; }
[[ "$PROJECTM_LTO" == ON || "$PROJECTM_LTO" == OFF ]] || { echo "PROJECTM_LTO must be ON or OFF" >&2; exit 2; }
[[ "$PROJECTM_ARM64_TUNE" == generic || "$PROJECTM_ARM64_TUNE" == cortex-a57 ]] || { echo "Invalid PROJECTM_ARM64_TUNE" >&2; exit 2; }
CLEAN=0
PROJECTM_PATCH_DIR="$ROOT_DIR/native/ambientprojectm/patches"

projectm_fingerprint() {
  { grep '^PROJECTM_' "$EXTERNAL_VERSIONS"; printf '%s\n' "$PROJECTM_REF" "$PROJECTM_PRESET_PACK" "$PROJECTM_FETCH_ASSETS" "$ANDROID_API" "$PROJECTM_OPT_LEVEL" "$PROJECTM_LTO" "$PROJECTM_ARM64_TUNE" "$PROJECTM_SHADER_CACHE" "$PROJECTM_DIRECT_CLASSIC_COMPOSITE" "$PROJECTM_ABIS"; if [[ -n "$PROJECTM_PGO_DIR" ]]; then for abi in "${selected_abis[@]}"; do git hash-object "$PROJECTM_PGO_DIR/$abi.profdata"; done; fi; git hash-object "$ROOT_DIR/scripts/build_projectm_android.sh" "$ROOT_DIR/native/ambientprojectm/ambientprojectm.cpp" "$ROOT_DIR/native/ambientprojectm/native_audio_tap.h" "$PROJECTM_PATCH_DIR"/*.patch; } | git hash-object --stdin
}

for arg in "$@"; do
  case "$arg" in
    --clean) CLEAN=1 ;;
    --fingerprint) projectm_fingerprint; exit 0 ;;
    -h|--help) sed -n '1,30p' "$0"; exit 0 ;;
    *) echo "Unknown argument: $arg" >&2; exit 2 ;;
  esac
done

for tool in git cmake find cp sed; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Missing required tool: $tool" >&2; exit 1; }
done

find_ndk() {
  if [[ -n "${ANDROID_NDK_HOME:-}" && -d "$ANDROID_NDK_HOME" ]]; then printf '%s\n' "$ANDROID_NDK_HOME"; return; fi
  if [[ -n "${ANDROID_NDK_ROOT:-}" && -d "$ANDROID_NDK_ROOT" ]]; then printf '%s\n' "$ANDROID_NDK_ROOT"; return; fi
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -n "$sdk" && -d "$sdk/ndk" ]]; then find "$sdk/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1; return; fi
}

NDK_DIR="$(find_ndk)"
[[ -n "$NDK_DIR" && -d "$NDK_DIR" ]] || { echo "Android NDK not found. Set ANDROID_NDK_HOME/ANDROID_NDK_ROOT or install NDK under ANDROID_HOME." >&2; exit 1; }

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) HOST_TAG="linux-x86_64" ;;
  Darwin-x86_64) HOST_TAG="darwin-x86_64" ;;
  Darwin-arm64) HOST_TAG="darwin-arm64" ;;
  *) echo "Unsupported host architecture: $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG"

mkdir -p "$PROJECTM_BUILD_ROOT"
if [[ ! -d "$PROJECTM_SRC/.git" ]]; then git clone --filter=blob:none "$PROJECTM_REPO" "$PROJECTM_SRC"; fi
git -C "$PROJECTM_SRC" fetch --tags --prune origin
git -C "$PROJECTM_SRC" checkout --force "$PROJECTM_REF"
# The pinned tag must still point at the pinned commit (overrides of PROJECTM_REF skip this).
PROJECTM_COMMIT="$(external_version PROJECTM_COMMIT)"
if [[ "$PROJECTM_REF" == "$(external_version PROJECTM_REF)" && -n "$PROJECTM_COMMIT" ]]; then
  actual_commit="$(git -C "$PROJECTM_SRC" rev-parse HEAD)"
  if [[ "$actual_commit" != "$PROJECTM_COMMIT" ]]; then
    echo "projectM $PROJECTM_REF resolves to $actual_commit, expected $PROJECTM_COMMIT" >&2
    exit 4
  fi
fi
git -C "$PROJECTM_SRC" submodule sync --recursive
git -C "$PROJECTM_SRC" submodule update --init --recursive
# This is a dedicated managed source checkout. Always start from the pinned revision.
for patch in "$PROJECTM_PATCH_DIR"/*.patch; do
  git -C "$PROJECTM_SRC" apply --check "$patch"
  git -C "$PROJECTM_SRC" apply "$patch"
done

if [[ "$CLEAN" == "1" ]]; then rm -rf "$PROJECTM_BUILD_ROOT/build" "$PROJECTM_BUILD_ROOT/install"; fi

build_abi() {
  local abi="$1" target="$2"
  local build="$PROJECTM_BUILD_ROOT/build/$abi"
  local prefix="$PROJECTM_BUILD_ROOT/install/$abi"
  local dest="$ROOT_DIR/app/src/main/jniLibs/$abi"
  local cc="$TOOLCHAIN/bin/${target}${ANDROID_API}-clang"
  local cxx="$TOOLCHAIN/bin/${target}${ANDROID_API}-clang++"
  local -a optimize=(-O"$PROJECTM_OPT_LEVEL") bridge_lto=()
  if [[ "$abi" == arm64-v8a && "$PROJECTM_ARM64_TUNE" != generic ]]; then optimize+=("-mtune=$PROJECTM_ARM64_TUNE"); fi
  if [[ "$PROJECTM_LTO" == ON ]]; then bridge_lto=(-flto=thin); fi
  local shader_cache_flag=""
  if [[ "$PROJECTM_SHADER_CACHE" == ON ]]; then shader_cache_flag="-DAMBIENT_PROJECTM_PROGRAM_CACHE=1"; fi
  if [[ "$PROJECTM_DIRECT_CLASSIC_COMPOSITE" == ON ]]; then shader_cache_flag+=" -DAMBIENT_PROJECTM_DIRECT_CLASSIC_COMPOSITE=1"; fi
  local profile_flag=""
  if [[ -n "$PROJECTM_PGO_DIR" ]]; then
    # CMake flags are shell-tokenized. Escape a profile path containing spaces.
    profile_flag="-fprofile-instr-use=\"$PROJECTM_PGO_DIR/$abi.profdata\" -Werror=profile-instr-out-of-date -Werror=profile-instr-unprofiled"
  fi

  cmake -S "$PROJECTM_SRC" -B "$build" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="android-$ANDROID_API" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INTERPROCEDURAL_OPTIMIZATION_RELEASE="$PROJECTM_LTO" \
    -DCMAKE_C_FLAGS_RELEASE="${optimize[*]} -DNDEBUG $profile_flag" \
    -DCMAKE_CXX_FLAGS_RELEASE="${optimize[*]} -DNDEBUG $shader_cache_flag $profile_flag" \
    -DCMAKE_INSTALL_PREFIX="$prefix" \
    -DBUILD_TESTING=OFF \
    -DENABLE_PLAYLIST=OFF \
    -DENABLE_SDL_UI=OFF \
    -DENABLE_GLES=ON
  cmake --build "$build" --parallel
  cmake --install "$build"

  mkdir -p "$dest"
  while IFS= read -r so; do cp -L "$so" "$dest/$(basename "$so")"; done < <(find -L "$prefix" -type f -name '*.so' | sort)

  local projectm_so
  projectm_so="$(find "$dest" -maxdepth 1 -type f \( -name 'libprojectM-4.so' -o -name 'libprojectM.so' \) | head -n 1)"
  [[ -n "$projectm_so" && -f "$projectm_so" ]] || { echo "libprojectM shared library not found for $abi" >&2; exit 1; }
  if [[ "$(basename "$projectm_so")" != "libprojectM-4.so" ]]; then cp "$projectm_so" "$dest/libprojectM-4.so"; fi

  [[ -x "$cxx" ]] || { echo "NDK C++ compiler missing: $cxx" >&2; exit 1; }
  "$cxx" -shared -fPIC "${optimize[@]}" "${bridge_lto[@]}" -std=c++17 -static-libstdc++ \
    -I"$prefix/include" \
    "$ROOT_DIR/native/ambientprojectm/ambientprojectm.cpp" \
    -L"$dest" -lprojectM-4 -llog -landroid -lGLESv3 -lEGL \
    -Wl,-z,defs -Wl,-soname,libambientprojectm.so \
    -o "$dest/libambientprojectm.so"

  [[ -s "$dest/libprojectM-4.so" && -s "$dest/libambientprojectm.so" ]] || { echo "projectM install verification failed for $abi" >&2; exit 1; }
  echo "projectM ready for $abi"
}

# Invalidate the published marker before any partial or failed rebuild.
rm -f "$ROOT_DIR/app/src/main/jniLibs/.projectm-version"
for abi in "${selected_abis[@]}"; do
  case "$abi" in
    arm64-v8a) build_abi "$abi" aarch64-linux-android ;;
    armeabi-v7a) build_abi "$abi" armv7a-linux-androideabi ;;
    x86) build_abi "$abi" i686-linux-android ;;
    x86_64) build_abi "$abi" x86_64-linux-android ;;
  esac
done

# A shallow checkout, but of the pinned commit (GitHub serves single commits by hash).
checkout_pinned_asset_repo() {
  local repo="$1" dir="$2" key="$3" commit
  commit="$(external_version "$key")"
  [[ "$commit" =~ ^[0-9a-f]{40}$ ]] || { echo "Missing or invalid $key in $EXTERNAL_VERSIONS" >&2; exit 4; }
  [[ -d "$dir/.git" ]] || git clone --depth 1 "$repo" "$dir"
  if [[ "$(git -C "$dir" rev-parse HEAD)" != "$commit" ]]; then
    git -C "$dir" fetch --depth 1 origin "$commit"
    git -C "$dir" checkout --detach --quiet "$commit"
  fi
  [[ "$(git -C "$dir" rev-parse HEAD)" == "$commit" ]] || { echo "Expected $commit in $dir" >&2; exit 4; }
}

fetch_assets() {
  [[ "$PROJECTM_FETCH_ASSETS" == "1" ]] || return 0
  [[ "$PROJECTM_PRESET_PACK" != "none" ]] || return 0
  local asset_root="$ROOT_DIR/app/src/main/assets/projectm"
  local work="$PROJECTM_BUILD_ROOT/assets"
  local preset_repo preset_subdir
  case "$PROJECTM_PRESET_PACK" in
    cream) preset_repo="https://github.com/projectM-visualizer/presets-cream-of-the-crop.git"; preset_subdir="cream" ;;
    classic) preset_repo="https://github.com/projectM-visualizer/presets-projectm-classic.git"; preset_subdir="classic" ;;
    original) preset_repo="https://github.com/projectM-visualizer/presets-milkdrop-original.git"; preset_subdir="original" ;;
    *) echo "Unknown PROJECTM_PRESET_PACK=$PROJECTM_PRESET_PACK" >&2; exit 2 ;;
  esac
  local preset_src="$work/presets-$PROJECTM_PRESET_PACK"
  local textures_src="$work/textures"
  checkout_pinned_asset_repo "$preset_repo" "$preset_src" "PROJECTM_PRESETS_${preset_subdir^^}_COMMIT"
  checkout_pinned_asset_repo https://github.com/projectM-visualizer/presets-milkdrop-texture-pack.git "$textures_src" PROJECTM_TEXTURES_COMMIT

  rm -rf "$asset_root/presets" "$asset_root/textures"
  mkdir -p "$asset_root/presets/$preset_subdir" "$asset_root/textures"
  while IFS= read -r preset; do
    rel="${preset#$preset_src/}"
    mkdir -p "$asset_root/presets/$preset_subdir/$(dirname "$rel")"
    cp "$preset" "$asset_root/presets/$preset_subdir/$rel"
  done < <(find "$preset_src" -type f -name '*.milk' | sort)
  while IFS= read -r tex; do
    rel="${tex#$textures_src/}"
    mkdir -p "$asset_root/textures/$(dirname "$rel")"
    cp "$tex" "$asset_root/textures/$rel"
  done < <(find "$textures_src" -type f \( -iname '*.png' -o -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.tga' -o -iname '*.bmp' \) | sort)
  (cd "$asset_root/presets" && find . -type f -name '*.milk' -printf '%P\n' | sort > "$asset_root/index.txt")
  printf '%s\n' "$PROJECTM_PRESET_PACK" > "$asset_root/pack.txt"
  echo "projectM assets ready: $(wc -l < "$asset_root/index.txt") presets"
}

fetch_assets
if [[ "$PROJECTM_ABIS" == "arm64-v8a,armeabi-v7a,x86,x86_64" ]]; then
  printf 'projectm_ref=%s\npreset_pack=%s\npcm_tap_api=1\nfingerprint=%s\n' "$PROJECTM_REF" "$PROJECTM_PRESET_PACK" "$(projectm_fingerprint)" > "$ROOT_DIR/app/src/main/jniLibs/.projectm-version"
fi

echo "projectM native runtime and presets are ready."
