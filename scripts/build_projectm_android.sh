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
CLEAN=0

for arg in "$@"; do
  case "$arg" in
    --clean) CLEAN=1 ;;
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
git -C "$PROJECTM_SRC" checkout "$PROJECTM_REF"
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

if [[ "$CLEAN" == "1" ]]; then rm -rf "$PROJECTM_BUILD_ROOT/build" "$PROJECTM_BUILD_ROOT/install"; fi

build_abi() {
  local abi="$1" target="$2"
  local build="$PROJECTM_BUILD_ROOT/build/$abi"
  local prefix="$PROJECTM_BUILD_ROOT/install/$abi"
  local dest="$ROOT_DIR/app/src/main/jniLibs/$abi"
  local cc="$TOOLCHAIN/bin/${target}${ANDROID_API}-clang"
  local cxx="$TOOLCHAIN/bin/${target}${ANDROID_API}-clang++"

  cmake -S "$PROJECTM_SRC" -B "$build" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="android-$ANDROID_API" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$prefix" \
    -DBUILD_TESTING=OFF \
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
  "$cxx" -shared -fPIC -O2 -std=c++17 -static-libstdc++ \
    -I"$prefix/include" \
    "$ROOT_DIR/native/ambientprojectm/ambientprojectm.cpp" \
    -L"$dest" -lprojectM-4 -llog -landroid -lGLESv3 -lEGL \
    -Wl,-z,defs -Wl,-soname,libambientprojectm.so \
    -o "$dest/libambientprojectm.so"

  [[ -s "$dest/libprojectM-4.so" && -s "$dest/libambientprojectm.so" ]] || { echo "projectM install verification failed for $abi" >&2; exit 1; }
  echo "projectM ready for $abi"
}

build_abi arm64-v8a aarch64-linux-android
build_abi armeabi-v7a armv7a-linux-androideabi
build_abi x86 i686-linux-android
build_abi x86_64 x86_64-linux-android

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
printf 'projectm_ref=%s\npreset_pack=%s\npcm_tap_api=1\n' "$PROJECTM_REF" "$PROJECTM_PRESET_PACK" > "$ROOT_DIR/app/src/main/jniLibs/.projectm-version"

echo "projectM native runtime and presets are ready."
