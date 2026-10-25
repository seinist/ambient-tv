#!/usr/bin/env bash
# Add Vulkan output + Vulkan Video to an already prepared mpv-android checkout.
# Run build_mpv_android.sh once to obtain the ordinary dependencies first.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="${MPV_BUILD_ROOT:-$ROOT/.native/mpv-android}/buildscripts"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/30.0.16248370}"
JOBS="${NATIVE_JOBS:-8}"
ABIS="${MPV_VULKAN_ABIS:-arm64 armv7l x86 x86_64}"
bash "$ROOT/scripts/prepare_svp_sources.sh" mpv "$BUILD/deps/mpv"
bash "$ROOT/scripts/enable_mpv_audio_encoder.sh" "$BUILD"
bash "$ROOT/scripts/prepare_mpv_ffmpeg.sh" "$BUILD"
GLSLANG="$ROOT/.native/glslang"
if [[ ! -d "$GLSLANG/.git" ]]; then
    git clone --depth 1 --branch 15.1.0 https://github.com/KhronosGroup/glslang.git "$GLSLANG"
fi
[[ "$(git -C "$GLSLANG" rev-parse HEAD)" == 1062752a891c95b2bfeed9e356562d88f9df84ac ]] || { echo 'Unexpected glslang revision' >&2; exit 1; }
HEADERS="$BUILD/deps/libplacebo/3rdparty/Vulkan-Headers"
git -C "$BUILD/deps/libplacebo" submodule update --init 3rdparty/Vulkan-Headers
# Idempotent build-only changes. API 29 matches the app minimum and supplies libvulkan.
sed -i 's/local apilvl=23/local apilvl=29/' "$BUILD/buildall.sh"
sed -i 's/--disable-vulkan/--enable-vulkan/' "$BUILD/scripts/ffmpeg.sh"
sed -i 's/-Dvulkan=disabled/-Dvulkan=enabled -Dglslang=enabled/' "$BUILD/scripts/libplacebo.sh"
# Meson compiler objects retain the original API level; use distinct build directories.
sed -i 's/build=_build\$ndk_suffix/build=_vulkan$ndk_suffix/' "$BUILD/scripts/libplacebo.sh" "$BUILD/scripts/mpv.sh"
sed -i 's/-Dmanpage-build=disabled.*/-Dmanpage-build=disabled -Dvulkan=enabled/' "$BUILD/scripts/mpv.sh"
sed -i 's|-Dvulkan=enabled.*-Ddemos=false|-Dvulkan=enabled -Dglslang=enabled -Dvulkan-sdk="$prefix_dir" -Dc_args="-I$prefix_dir/include" -Dcpp_args="-I$prefix_dir/include" -Dc_link_args="-L$prefix_dir/lib" -Dcpp_link_args="-L$prefix_dir/lib" -Ddemos=false|' "$BUILD/scripts/libplacebo.sh"
sed -i 's/meson setup \$build /meson setup $build --reconfigure /; s/--reconfigure --reconfigure/--reconfigure/' \
    "$BUILD/scripts/libplacebo.sh" "$BUILD/scripts/mpv.sh"
# Meson find_library(static: true) does not search cpp_link_args. Add the explicit
# SDK directory for glslang just as upstream already does for SPIRV.
sed -i "s/find_library('glslang', required: required, static: static)/find_library('glslang', required: required, static: static, dirs: vulkan_lib_dirs)/" "$BUILD/deps/libplacebo/src/glsl/meson.build"
for arch in $ABIS; do
    case "$arch" in
        arm64) abi=arm64-v8a; triple=aarch64-linux-android ;;
        armv7l) abi=armeabi-v7a; triple=armv7a-linux-androideabi ;;
        x86) abi=x86; triple=i686-linux-android ;;
        x86_64) abi=x86_64; triple=x86_64-linux-android ;;
        *) echo "Unknown architecture: $arch" >&2; exit 1 ;;
    esac
    prefix="$BUILD/prefix/$arch"
    [[ -f "$prefix/lib/libmpv.so" ]] || { echo "Build ordinary MPV dependencies for $arch first" >&2; exit 1; }
    cmake -S "$GLSLANG" -B "$GLSLANG/build-$abi" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$prefix" \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DENABLE_OPT=OFF \
        -DENABLE_GLSLANG_BINARIES=OFF -DENABLE_SPIRV=ON -DGLSLANG_TESTS=OFF
    cmake --build "$GLSLANG/build-$abi" --parallel "$JOBS"
    cmake --install "$GLSLANG/build-$abi"
    cp -R "$HEADERS/include/vulkan" "$HEADERS/include/vk_video" "$prefix/include/"
    # Android provides the loader. Never ship a desktop Vulkan loader in the APK.
    printf 'prefix=/usr/local\nName: Vulkan\nDescription: Android Vulkan loader\nVersion: 1.4.0\nLibs: -lvulkan\nCflags: -I${prefix}/include\n' > "$prefix/lib/pkgconfig/vulkan.pc"
    (cd "$BUILD"; cores="$JOBS" ./buildall.sh --arch "$arch" -n ffmpeg)
    (cd "$BUILD"; cores="$JOBS" ./buildall.sh --arch "$arch" -n libplacebo)
    (cd "$BUILD"; cores="$JOBS" ./buildall.sh --arch "$arch" -n mpv)
    dest="$ROOT/app/src/main/jniLibs/$abi"
    mkdir -p "$dest"
    # Install only after the whole architecture built successfully.
    while IFS= read -r library; do cp -L "$library" "$dest/$(basename "$library")"; done < <(find -L "$prefix/lib" -maxdepth 1 -type f -name '*.so')
    # Meson's default /usr/local prefix is nested inside DESTDIR. Prefer this
    # freshly installed player over an older copy left in prefix/lib.
    if [[ -f "$prefix/usr/local/lib/libmpv.so" ]]; then
        cp -L "$prefix/usr/local/lib/libmpv.so" "$dest/libmpv.so"
    fi
    "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/${triple}29-clang" \
        -shared -fPIC -O2 -I"$prefix/include" "$ROOT/native/ambientmpv/ambientmpv.c" \
        -L"$prefix/lib" -lmpv -lavcodec -lavutil -ldl -llog -Wl,-z,defs,-z,max-page-size=16384 \
        -Wl,-soname,libambientmpv.so -o "$dest/libambientmpv.so"
    bash "$ROOT/scripts/prepare_svp_sources.sh" --hash mpv > "$dest/.svp-mpv"
    sha256sum "$ROOT/native/ambientmpv/ambientmpv.c" | cut -d ' ' -f1 > "$dest/.ambientmpv-src"
    touch "$dest/.projectm-pcm"
done
if [[ "$ABIS" == 'arm64 armv7l x86 x86_64' ]]; then
    marker="$ROOT/app/src/main/jniLibs/.mpv-version"
    sed -i '/^vulkan_pipeline=/d' "$marker"
    printf 'vulkan_pipeline=1\n' >> "$marker"
fi
