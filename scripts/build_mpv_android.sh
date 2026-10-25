#!/usr/bin/env bash
set -euo pipefail

# Builds libmpv and its shared dependencies for the Android ABIs supported by this
# app (arm64-v8a, armeabi-v7a, x86, x86_64), then builds the app-owned JNI shim
# (libambientmpv.so) and installs everything into app/src/main/jniLibs/<abi>/.
#
# Supported hosts: Linux and macOS. Upstream mpv-android explicitly does not
# support Windows/WSL for native builds.
#
# Environment overrides:
#   MPV_ANDROID_REF     git ref/commit of mpv-android (default from gradle/external-versions.properties)
#   FFMPEG_REF          FFmpeg ref override to build against (default from gradle/external-versions.properties)
#   MPV_BUILD_ROOT      work directory (default: .native/mpv-android)
#   ANDROID_API         NDK API level used for JNI shim (default: 29)
#   SKIP_MPV_DOWNLOAD=1 skip upstream SDK/source download after initial clone
#   MPV_SKIP_HOST_DEPS=1     skip upstream sudo yum/apt host package installation
#   MPV_UPSTREAM_HOST_DEPS=1 force upstream host package installation even if immutable is detected
#
# Usage:
#   ./scripts/build_mpv_android.sh
#   ./scripts/build_mpv_android.sh --clean

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXTERNAL_VERSIONS="$ROOT_DIR/gradle/external-versions.properties"
external_version() { local key="$1"; sed -n "s/^${key}=//p" "$EXTERNAL_VERSIONS" | head -n 1; }
[[ -f "$EXTERNAL_VERSIONS" ]] || { echo "Missing $EXTERNAL_VERSIONS" >&2; exit 1; }
MPV_BUILD_ROOT="${MPV_BUILD_ROOT:-${ROOT_DIR}/.native/mpv-android}"
MPV_ANDROID_REF="${MPV_ANDROID_REF:-$(external_version MPV_ANDROID_REF)}"
ANDROID_API="${ANDROID_API:-29}"
UPSTREAM_URL="https://github.com/mpv-android/mpv-android.git"
CLEAN=0

for arg in "$@"; do
  case "$arg" in
    --clean) CLEAN=1 ;;
    -h|--help)
      sed -n '1,32p' "$0"
      exit 0
      ;;
    *) echo "Unknown argument: $arg" >&2; exit 2 ;;
  esac
done

case "$(uname -s)" in
  Linux) ;;
  Darwin) ;;
  *) echo "Unsupported host. Use Linux or macOS (not Windows/WSL)." >&2; exit 1 ;;
esac

for tool in git bash find cp sed uname; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Missing required tool: $tool" >&2; exit 1; }
done

is_immutable_linux_host() {
  [[ "$(uname -s)" == "Linux" ]] || return 1

  # Fedora Atomic/Silverblue/Kinoite/Bazzite and other OSTree/bootc systems.
  [[ -e /run/ostree-booted ]] && return 0
  command -v rpm-ostree >/dev/null 2>&1 && return 0

  # Also catch Linux installations where /usr itself is mounted read-only.
  if command -v findmnt >/dev/null 2>&1; then
    local opts
    opts="$(findmnt -n -o OPTIONS -T /usr 2>/dev/null || true)"
    [[ ",$opts," == *,ro,* ]] && return 0
  fi

  return 1
}

check_linux_mpv_host_tools() {
  local missing=()
  local tool
  for tool in autoconf pkg-config ninja unzip wget meson gperf nasm javac; do
    command -v "$tool" >/dev/null 2>&1 || missing+=("$tool")
  done
  if ! command -v libtoolize >/dev/null 2>&1 && ! command -v glibtoolize >/dev/null 2>&1; then
    missing+=("libtoolize")
  fi

  if (( ${#missing[@]} > 0 )); then
    printf 'Missing MPV host build tools: %s\n' "${missing[*]}" >&2
    cat >&2 <<'EOT'
Install the mpv-android host dependencies using your OS-supported development environment, then rerun the build.
Fedora package set: autoconf pkgconf-pkg-config libtool ninja-build unzip wget meson gperf nasm java-17-openjdk-devel
Debian/Ubuntu package set: autoconf pkg-config libtool ninja-build unzip wget meson gperf nasm default-jdk
On Fedora Atomic/image-based desktops, install these inside Toolbox/Distrobox rather than trying to write to /usr on the host.
EOT
    exit 1
  fi
}

mkdir -p "$(dirname "$MPV_BUILD_ROOT")"
if [[ ! -d "$MPV_BUILD_ROOT/.git" ]]; then
  git clone "$UPSTREAM_URL" "$MPV_BUILD_ROOT"
fi

git -C "$MPV_BUILD_ROOT" fetch --tags --prune origin
git -C "$MPV_BUILD_ROOT" checkout "$MPV_ANDROID_REF"
if [[ "$MPV_ANDROID_REF" == "master" ]]; then
  git -C "$MPV_BUILD_ROOT" pull --ff-only origin master
fi

BUILDSCRIPTS="$MPV_BUILD_ROOT/buildscripts"
[[ -x "$BUILDSCRIPTS/buildall.sh" ]] || chmod +x "$BUILDSCRIPTS"/*.sh

if [[ "${SKIP_MPV_DOWNLOAD:-0}" != "1" ]]; then
  (
    cd "$BUILDSCRIPTS"

    skip_host_deps=0
    if [[ "$(uname -s)" == "Linux" ]]; then
      if [[ "${MPV_SKIP_HOST_DEPS:-0}" == "1" ]] || is_immutable_linux_host; then
        skip_host_deps=1
      fi
    fi

    if [[ "${MPV_UPSTREAM_HOST_DEPS:-0}" == "1" ]]; then
      skip_host_deps=0
    fi

    # Checksum-verified source tarballs first: download-deps.sh skips directories that exist.
    bash "$ROOT_DIR/scripts/pin_mpv_deps.sh" prefetch "$BUILDSCRIPTS"

    if [[ "$skip_host_deps" == "1" ]]; then
      check_linux_mpv_host_tools
      # Upstream download-sdk.sh runs `sudo yum install` or `sudo apt-get install`
      # when IN_CI=0. That fails on immutable/image-based Linux systems because
      # /usr is read-only. IN_CI=1 skips only that host-package installation.
      # Run download-deps.sh separately with IN_CI=0 so its normal source checkout
      # behavior is preserved (IN_CI=1 would select the CI-pinned FFmpeg branch).
      echo "Immutable/read-only Linux host detected; skipping upstream host package installation."
      IN_CI=1 ./include/download-sdk.sh
      IN_CI=0 ./include/download-deps.sh
    else
      # Preserve upstream behavior on mutable Linux and macOS hosts.
      ./download.sh
    fi
  )
fi

# download-deps.sh clones mpv, libplacebo, dav1d and libass from their default branch: move them
# (and freetype2's tag) to the reviewed commits before any patch is applied or anything is built.
bash "$ROOT_DIR/scripts/pin_mpv_deps.sh" pin "$BUILDSCRIPTS"

if [[ "$CLEAN" == "1" ]]; then
  rm -rf "$BUILDSCRIPTS/prefix/arm64" "$BUILDSCRIPTS/prefix/armv7l"     "$BUILDSCRIPTS/prefix/x86" "$BUILDSCRIPTS/prefix/x86_64"
fi

# Use the recorded, regression-safe release tag and verify its immutable commit.
FFMPEG_DIR="$BUILDSCRIPTS/deps/ffmpeg"
FFMPEG_REF="${FFMPEG_REF:-$(external_version FFMPEG_REF)}"
FFMPEG_COMMIT="$(external_version FFMPEG_COMMIT)"
[[ -d "$FFMPEG_DIR/.git" ]] || { echo "Missing FFmpeg checkout" >&2; exit 1; }
git -C "$FFMPEG_DIR" fetch origin "$FFMPEG_REF"
ffmpeg_target="$FFMPEG_COMMIT"
if [[ "$FFMPEG_REF" != "$(external_version FFMPEG_REF)" ]]; then
  ffmpeg_target="$(git -C "$FFMPEG_DIR" rev-parse FETCH_HEAD)"
fi
# Remove only our known patch before moving revisions. Unknown local edits are
# left in place so checkout fails safely if they conflict.
# Newest first: later patches of the series may change lines of earlier ones.
ffmpeg_patches=("$ROOT_DIR"/native/ambientmpv/patches/ffmpeg-*.patch)
for ((i = ${#ffmpeg_patches[@]} - 1; i >= 0; i--)); do
  if git -C "$FFMPEG_DIR" apply --reverse --check "${ffmpeg_patches[i]}" 2>/dev/null; then
    git -C "$FFMPEG_DIR" apply --reverse "${ffmpeg_patches[i]}"
  fi
done
git -C "$FFMPEG_DIR" checkout --detach "$ffmpeg_target"
FFMPEG_COMMIT="$(git -C "$FFMPEG_DIR" rev-parse HEAD)"

bash "$ROOT_DIR/scripts/enable_mpv_audio_encoder.sh" "$BUILDSCRIPTS"
bash "$ROOT_DIR/scripts/prepare_mpv_ffmpeg.sh" "$BUILDSCRIPTS"
bash "$ROOT_DIR/scripts/prepare_svp_sources.sh" mpv "$BUILDSCRIPTS/deps/mpv"

# Upstream naming: armv7l (default), arm64, x86, x86_64.
# The Vulkan pass patches these reusable upstream scripts in place. Restore the
# bootstrap configuration before rebuilding dependencies (including after --clean),
# when glslang/SPIRV may not yet exist in the architecture's prefix.
sed -i 's/--enable-vulkan/--disable-vulkan/' "$BUILDSCRIPTS/scripts/ffmpeg.sh"
sed -i 's/build=_vulkan\$ndk_suffix/build=_build$ndk_suffix/' \
  "$BUILDSCRIPTS/scripts/libplacebo.sh" "$BUILDSCRIPTS/scripts/mpv.sh"
sed -i 's/-Dvulkan=.*-Ddemos=false/-Dvulkan=disabled -Dglslang=disabled -Dshaderc=disabled -Ddemos=false/' \
  "$BUILDSCRIPTS/scripts/libplacebo.sh"
sed -i 's/-Dmanpage-build=disabled.*/-Dmanpage-build=disabled -Dvulkan=disabled/' \
  "$BUILDSCRIPTS/scripts/mpv.sh"
# Apply changed options to existing Meson directories as well as fresh ones.
sed -i 's/meson setup \$build /meson setup $build --reconfigure /; s/--reconfigure --reconfigure/--reconfigure/' \
  "$BUILDSCRIPTS/scripts/libplacebo.sh" "$BUILDSCRIPTS/scripts/mpv.sh"
(
  cd "$BUILDSCRIPTS"
  ./buildall.sh --arch arm64 mpv
  ./buildall.sh mpv
  ./buildall.sh --arch x86 mpv
  ./buildall.sh --arch x86_64 mpv
)

find_ndk() {
  local sdk_root="$BUILDSCRIPTS/sdk"
  local sdk="$sdk_root/android-sdk-linux"
  local candidate

  if [[ -n "${ANDROID_NDK_HOME:-}" && -d "$ANDROID_NDK_HOME" ]]; then
    printf '%s\n' "$ANDROID_NDK_HOME"
    return 0
  fi
  if [[ -n "${ANDROID_NDK_ROOT:-}" && -d "$ANDROID_NDK_ROOT" ]]; then
    printf '%s\n' "$ANDROID_NDK_ROOT"
    return 0
  fi

  # mpv-android creates sdk/android-ndk-<rev> either as a real directory or
  # as a symlink to android-sdk-linux/ndk/<version>. `find -type d` does not
  # match a symlink, which made the old detection miss a perfectly valid NDK.
  for candidate in "$sdk_root"/android-ndk-*; do
    if [[ -d "$candidate" ]]; then
      readlink -f "$candidate" 2>/dev/null || printf '%s\n' "$candidate"
      return 0
    fi
  done

  if [[ -d "$sdk/ndk" ]]; then
    candidate="$(find "$sdk/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
    if [[ -n "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  fi

  return 1
}

NDK_DIR="$(find_ndk || true)"
[[ -n "$NDK_DIR" && -d "$NDK_DIR" ]] || {
  echo "Android NDK not found after upstream SDK download. Looked under: $BUILDSCRIPTS/sdk" >&2
  find "$BUILDSCRIPTS/sdk" -maxdepth 4 -type d -o -type l 2>/dev/null | sed -n '1,120p' >&2 || true
  exit 1
}

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) HOST_TAG="linux-x86_64" ;;
  Darwin-x86_64) HOST_TAG="darwin-x86_64" ;;
  Darwin-arm64) HOST_TAG="darwin-arm64" ;;
  *) echo "Unsupported NDK host architecture: $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG"
[[ -d "$TOOLCHAIN" ]] || { echo "NDK LLVM toolchain not found: $TOOLCHAIN" >&2; exit 1; }

install_arch() {
  local upstream_arch="$1"
  local android_abi="$2"
  local cc_name="$3"
  local prefix="$BUILDSCRIPTS/prefix/$upstream_arch/usr/local"
  local dest="$ROOT_DIR/app/src/main/jniLibs/$android_abi"
  local cc="$TOOLCHAIN/bin/${cc_name}${ANDROID_API}-clang"

  [[ -f "$prefix/lib/libmpv.so" ]] || {
    echo "libmpv.so missing for $upstream_arch: $prefix/lib/libmpv.so" >&2
    exit 1
  }
  [[ -x "$cc" ]] || { echo "NDK compiler missing: $cc" >&2; exit 1; }
  [[ -f "$prefix/include/mpv/client.h" ]] || { echo "libmpv headers missing: $prefix/include/mpv/client.h" >&2; exit 1; }

  mkdir -p "$dest"
  # Replace previous MPV/FFmpeg payloads only — leave projectM/Spotify libs intact.
  rm -f "$dest"/libmpv.so "$dest"/libambientmpv.so     "$dest"/libavcodec.so "$dest"/libavdevice.so "$dest"/libavfilter.so     "$dest"/libavformat.so "$dest"/libavutil.so     "$dest"/libswresample.so "$dest"/libswscale.so "$dest"/libpostproc.so

  # Copy libmpv plus all shared dependencies generated in the same prefix.
  # Dereference symlinks so jniLibs contains real ELF files.
  while IFS= read -r so; do
    cp -L "$so" "$dest/$(basename "$so")"
  done < <(find -L "$prefix/lib" -maxdepth 1 -type f -name '*.so' | sort)

  "$cc" \
    -shared -fPIC -O2 \
    -I"$prefix/include" \
    "$ROOT_DIR/native/ambientmpv/ambientmpv.c" \
    -L"$prefix/lib" -lmpv -lavcodec -llog \
    -Wl,-z,defs -Wl,-soname,libambientmpv.so \
    -o "$dest/libambientmpv.so"

  [[ -f "$dest/libmpv.so" && -f "$dest/libambientmpv.so" ]] || {
    echo "Native install verification failed for $android_abi" >&2
    exit 1
  }

  echo "Installed $android_abi native runtime:"
  bash "$ROOT_DIR/scripts/prepare_svp_sources.sh" --hash mpv > "$dest/.svp-mpv"
  # build.sh rebuilds the shim when its source changes (see mpv_libs_ready).
  sha256sum "$ROOT_DIR/native/ambientmpv/ambientmpv.c" | cut -d ' ' -f1 > "$dest/.ambientmpv-src"
  bash "$ROOT_DIR/scripts/mpv_patch_hash.sh" > "$dest/.mpv-patches"
    touch "$dest/.projectm-pcm"
  find "$dest" -maxdepth 1 -type f -name '*.so' -printf '  %f\n' 2>/dev/null || ls -1 "$dest"/*.so
}

install_arch "arm64" "arm64-v8a" "aarch64-linux-android"
install_arch "armv7l" "armeabi-v7a" "armv7a-linux-androideabi"
install_arch "x86" "x86" "i686-linux-android"
install_arch "x86_64" "x86_64" "x86_64-linux-android"
printf 'mpv_android_ref=%s\nffmpeg_ref=%s\nffmpeg_commit=%s\n' "$MPV_ANDROID_REF" "$FFMPEG_REF" "$FFMPEG_COMMIT" > "$ROOT_DIR/app/src/main/jniLibs/.mpv-version"

# Rendering and decoding support must survive the next normal native rebuild.
if [[ "${MPV_ENABLE_VULKAN:-1}" == 1 ]]; then
  ANDROID_NDK_HOME="$NDK_DIR" MPV_BUILD_ROOT="$MPV_BUILD_ROOT" bash "$ROOT_DIR/scripts/build_mpv_vulkan.sh"
fi

echo
echo "MPV native runtimes are ready under app/src/main/jniLibs/."
echo "Android will automatically load arm64-v8a, armeabi-v7a, x86, or x86_64 for the running device."
echo "MPV native runtime installed. The app-side MPV renderer/controller is enabled when these libraries are present."
