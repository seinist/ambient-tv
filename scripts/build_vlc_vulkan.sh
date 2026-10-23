#!/usr/bin/env bash
# Vulkan-enabled VLC 4 / JNI build. Isolated from the stable Maven LibVLC artifact.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Include pins, build flags and both patch sets in the per-ABI cache identity.
if [[ "${1:-}" == "--hash" ]]; then
    (cd "$ROOT"; sha256sum gradle/external-versions.properties scripts/build_vlc_vulkan.sh native/vlc-aar/patches/*.patch native/vlc-aar/jni-patches/*.patch) | sha256sum | cut -d ' ' -f1
    exit 0
fi
SOURCE_HASH="$(bash "$0" --hash)"
JNI="${VLC_BUILD_ROOT:-$ROOT/.native/libvlcjni}"
export ANDROID_NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/30.0.16248370}"
export MAKEFLAGS="-j${NATIVE_JOBS:-8}"
export PKG_CONFIG_IGNORE_CONFLICTS=1
JNI_REF=a8d53a9151d7e4a9a5dfd0a5eb1cd92669afdc21
VLC_REF=c84ef1eda4f14f8b57a5b266b51c843cf51ed28b
if [[ ! -d "$JNI/.git" ]]; then
    git clone --no-checkout https://code.videolan.org/videolan/libvlcjni.git "$JNI"
    git -C "$JNI" checkout --detach "$JNI_REF"
fi
if [[ ! -d "$JNI/vlc/.git" ]]; then
    git clone --no-checkout https://code.videolan.org/videolan/vlc.git "$JNI/vlc"
    git -C "$JNI/vlc" checkout --detach "$VLC_REF"
fi
[[ "$(git -C "$JNI" rev-parse HEAD)" == "$JNI_REF" ]] || { echo "Expected libvlcjni $JNI_REF" >&2; exit 1; }
[[ "$(git -C "$JNI/vlc" rev-parse HEAD)" == "$VLC_REF" ]] || { echo "Expected VLC $VLC_REF" >&2; exit 1; }
# Keep JNI return types in sync with this revision's Java wrapper.
for patch in "$ROOT"/native/vlc-aar/jni-patches/*.patch; do
    if git -C "$JNI" apply --check "$patch" 2>/dev/null; then
        git -C "$JNI" apply "$patch"
    else
        git -C "$JNI" apply --reverse --check "$patch"
    fi
done
bash "$ROOT/scripts/prepare_svp_sources.sh" vlc "$JNI/vlc"
for patch in "$JNI"/libvlc/patches/*.patch; do
    if git -C "$JNI/vlc" apply --check "$patch" 2>/dev/null; then
        git -C "$JNI/vlc" apply "$patch"
    else
        git -C "$JNI/vlc" apply --reverse --check "$patch"
    fi
done
# ffmpeg-9.0.2.patch supplies compatibility fixes for the release/9.0 snapshot
# and puts the v308, v408 and v410 decoders back into that contrib FFmpeg.
# A tree that already carries an older copy of one of these patches matches
# neither direction. Restore the paths that copy owns, then apply the current file.
apply_vlc_patch() {
    local patch=$1 line path
    if git -C "$JNI/vlc" apply --check "$patch" 2>/dev/null; then
        git -C "$JNI/vlc" apply "$patch"
        return
    fi
    if git -C "$JNI/vlc" apply --reverse --check "$patch" 2>/dev/null; then
        return
    fi
    while IFS= read -r line; do
        path=${line#+++ b/}
        [[ "$path" == "/dev/null" ]] && continue
        if git -C "$JNI/vlc" ls-files --error-unmatch -- "$path" >/dev/null 2>&1; then
            git -C "$JNI/vlc" checkout -- "$path"
        elif [[ -e "$JNI/vlc/$path" ]]; then
            rm -f "$JNI/vlc/$path"
        fi
    done < <(grep -E '^\+\+\+ b/' "$patch")
    # The first FFmpeg 9 patch guarded these two files. The current patch leaves
    # them untouched, so an older apply would keep the three fourccs compiled out.
    if [[ "$(basename "$patch")" == ffmpeg-9.0.2.patch ]]; then
        git -C "$JNI/vlc" checkout -- \
            modules/codec/avcodec/fourcc.c \
            modules/codec/avcodec/avcommon_compat.h
    fi
    git -C "$JNI/vlc" apply "$patch"
}
for patch in "$ROOT"/native/vlc-aar/patches/*.patch; do
    apply_vlc_patch "$patch"
done
# Use the same reviewed branch snapshot as MPV. The patch selects VLC's native
# Git archive + githash verification path instead of a release tarball.
ffmpeg_commit="$(sed -n 's/^FFMPEG_COMMIT=//p' "$ROOT/gradle/external-versions.properties")"
[[ "$ffmpeg_commit" =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid FFmpeg commit' >&2; exit 1; }
sed -i "s/^FFMPEG_HASH=.*/FFMPEG_HASH=$ffmpeg_commit/" "$JNI/vlc/contrib/src/ffmpeg/rules.mak"
# The app minimum is API 29. Vulkan's system loader is available from API 24.
sed -i 's/ANDROID_API=21/ANDROID_API=29/' "$JNI"/buildsystem/{compile-libvlc,build-libvlc,build-libvlcjni}.sh
perl -i -pe 's/\[ "\$REL" != 29 \](?: && \[ "\$REL" != 30 \])*/[ "\$REL" != 29 ] && [ "\$REL" != 30 ]/' "$JNI"/buildsystem/{compile-libvlc,build-libvlc,build-libvlcjni}.sh
# Android ships the loader but no pkg-config description. Supply it to contrib + VLC.
if ! grep -qi 'pkgconfig vulkan' "$JNI/buildsystem/build-libvlc.sh"; then
    sed -i '/avlc_gen_pc_file $VLC_CONTRIB_OUT_DIR\/lib\/pkgconfig GLESv2 2/a\avlc_gen_pc_file $VLC_CONTRIB_OUT_DIR/lib/pkgconfig vulkan 1.4.0' "$JNI/buildsystem/build-libvlc.sh"
fi
sed -i 's/PC_LIBS="-lvulkan" avlc_gen_pc_file \(.*\) Vulkan 1.4.0/avlc_gen_pc_file \1 vulkan 1.4.0/' "$JNI/buildsystem/build-libvlc.sh"
# Fontconfig is used for font selection, not translated UI. Avoid a second libintl
# runtime in the static Android link (the VLC Android UI owns translations).
sed -i 's/FONTCONFIG_CONF := --enable-libxml2/FONTCONFIG_CONF := --disable-nls --enable-libxml2/' "$JNI/vlc/contrib/src/fontconfig/rules.mak"
# Android has no V4L capture or pthread cancellation; keep DVB/teletext decoding.
sed -i 's/--disable-dvb --disable-bktr/--disable-v4l --disable-dvb --disable-bktr/' "$JNI/vlc/contrib/src/zvbi/rules.mak"
# API 29 provides both large-file calls. Older FLAC CMake probes mis-detect them
# with NDK 30's 32-bit FILE_OFFSET_BITS redirects and then redefine stdio symbols.
sed -i 's/^FLAC_CONF = \\/FLAC_CONF = -DHAVE_FSEEKO=1 -DHAVE_FTELLO=1 \\/' "$JNI/vlc/contrib/src/flac/rules.mak"
# Existing contrib trees can contain gettext locale wrappers; clean Android
# contrib builds do not need libintl. Link it only when this ABI supplies it.
if ! grep -q 'wildcard.*libintl.a' "$JNI/libvlc/jni/libvlc.mk"; then
    sed -i 's/-lintl //g; s/    -ldl -lm -llog /    $(if $(wildcard $(VLC_CONTRIB)\/lib\/libintl.a),-lintl) -ldl -lm -llog /' "$JNI/libvlc/jni/libvlc.mk"
fi
# Keep the visualizer used by the app (upstream's minimal Android build omits it).
sed -i '/^[[:space:]]*visual$/d' "$JNI/buildsystem/build-libvlc.sh"
# Upstream's probe used `which` as a standalone command under set -e.
perl -0777 -i -pe 's/which autopoint >\/dev\/null\nif \[ ! \$\? -eq 0 \];then/if ! command -v autopoint >\/dev\/null; then/' "$JNI/buildsystem/build-libvlc.sh"
perl -i -pe 's/find \$1 -name \x27\$2\x27/find "\$1" -name "\$2"/' "$JNI/buildsystem/build-libvlc.sh"
export EXTRA_PARAMS="--enable-vulkan --enable-libplacebo"
export PATH="$JNI/vlc/extras/tools/build/bin:$PATH"
# Some distributions install gettext without autopoint. Only its generated shell
# scripts/data are needed; avoid building an unrelated host gettext runtime.
if ! command -v autopoint >/dev/null; then
    (cd "$JNI/vlc/extras/tools"; ./bootstrap; make gettext)
    (cd "$JNI/vlc/extras/tools/gettext"; ./configure --prefix="$JNI/vlc/extras/tools/build" --disable-java --disable-csharp)
    make -C "$JNI/vlc/extras/tools/gettext/gettext-tools/misc" install-exec-am install-data-am
fi
for abi in ${VLC_VULKAN_ABIS:-arm64-v8a armeabi-v7a x86 x86_64}; do
    env -u DEBUG "$JNI/buildsystem/compile-libvlc.sh" -a "$abi" --release
    mkdir -p "$ROOT/app/libs"
    bash "$ROOT/scripts/prepare_svp_sources.sh" --hash vlc > "$ROOT/app/libs/.svp-vlc-$abi"
    touch "$ROOT/app/libs/.projectm-pcm-vlc-$abi"
    printf '%s\n' "$SOURCE_HASH" > "$ROOT/app/libs/.vlc-source-$abi"
done
# Build the Java wrapper from this exact JNI revision, never mix VLC 3 Java with VLC 4 JNI.
env -u ANDROID_SDK_ROOT ANDROID_HOME="${ANDROID_NDK%/ndk/*}" "$ROOT/gradlew" -p "$ROOT/native/vlc-aar" assembleRelease -PVLC_SOURCE="$JNI" --console=plain
mkdir -p "$ROOT/app/libs"
cp "$ROOT/native/vlc-aar/build/outputs/aar/libvlc-vulkan-release.aar" "$ROOT/app/libs/libvlc-vulkan.aar"
echo "Installed Vulkan-enabled LibVLC AAR (Java + JNI from $JNI_REF, VLC $VLC_REF)"
