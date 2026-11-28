#!/usr/bin/env bash
# Vulkan-enabled VLC 4 / JNI build. Isolated from the stable Maven LibVLC artifact.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Normal builds include all native modules. Explicit experimental CI builds
# remain isolated unless the caller deliberately requests installation.
EXPERIMENTAL_AHB="${VLC_EXPERIMENTAL_AHB:-1}"
[[ "$EXPERIMENTAL_AHB" == 0 || "$EXPERIMENTAL_AHB" == 1 ]] || { echo "Invalid VLC_EXPERIMENTAL_AHB" >&2; exit 1; }
if [[ -v VLC_EXPERIMENTAL_AHB && "$EXPERIMENTAL_AHB" == 1 ]]; then
    INSTALL_APP="${VLC_INSTALL_APP:-0}"
else
    INSTALL_APP="${VLC_INSTALL_APP:-1}"
fi
[[ "$INSTALL_APP" == 0 || "$INSTALL_APP" == 1 ]] || { echo "Invalid VLC_INSTALL_APP" >&2; exit 1; }
# Include pins, build flags and both patch sets in the per-ABI cache identity.
if [[ "${1:-}" == "--hash" ]]; then
    (cd "$ROOT"; sha256sum gradle/external-versions.properties scripts/build_vlc_vulkan.sh native/vlc-aar/patches/*.patch native/vlc-aar/jni-patches/*.patch
        bash scripts/prepare_svp_sources.sh --hash vlc
        sha256sum native/vlc-aar/build.gradle.kts native/vlc-aar/settings.gradle.kts native/vlc-aar/consumer-rules.pro gradle/libs.versions.toml gradle/wrapper/gradle-wrapper.properties
        if [[ "$EXPERIMENTAL_AHB" == 1 ]]; then
            sha256sum scripts/prepare_vlc_ahb_experimental.sh
            # Native staging consumes headers, C/C++ sources, shaders and patches.
            # Host-only tests/reference documents must not invalidate an AAR.
            find native/vlc-aar/experimental -type f \
                \( -name '*.h' -o -name '*.c' -o -name '*.cpp' -o -name '*.vert' -o -name '*.frag' -o -name '*.patch' \) \
                ! -name 'test_*' ! -name 'export_*' ! -name 'extract_profile7_audit.c' \
                -print0 | sort -z | xargs -0 sha256sum
        fi
    ) | sha256sum | cut -d ' ' -f1
    exit 0
fi
# Build-tool identity is separate from the source fingerprint so emulator
# jobs can validate source provenance without installing a compiler.
if [[ "${1:-}" == "--toolchain-hash" ]]; then
    ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-$HOME/Android/Sdk/ndk/30.0.16248370}}"
    (
        uname -srm
        [[ ! -r /etc/os-release ]] || cat /etc/os-release
        [[ ! -r "$ndk/source.properties" ]] || cat "$ndk/source.properties"
        for tool in cc c++ cmake ninja meson rustc cargo java python3 pkg-config make autoconf automake glslc; do
            printf '%s\n' "$tool"
            if command -v "$tool" >/dev/null; then "$tool" --version 2>&1 || true; else echo missing; fi
        done
        find "$ndk/toolchains/llvm/prebuilt" -path '*/bin/clang' -type f -print0 2>/dev/null |
            sort -z | xargs -0 -r sha256sum | sed 's@  .*@  ndk-clang@' || true
        printf 'abi=%s\nexperimental=%s\n' "${VLC_VULKAN_ABIS:-arm64-v8a armeabi-v7a x86 x86_64}" "$EXPERIMENTAL_AHB"
    ) | sha256sum | cut -d ' ' -f1
    exit 0
fi
SOURCE_HASH="$(VLC_EXPERIMENTAL_AHB="$EXPERIMENTAL_AHB" bash "$0" --hash)"
TOOLCHAIN_HASH="$(VLC_EXPERIMENTAL_AHB="$EXPERIMENTAL_AHB" bash "$0" --toolchain-hash)"
# A native-link receipt is opt-in and is never an AAR success marker.
if [[ -n "${VLC_NATIVE_LINK_RECEIPT:-}" ]]; then
    [[ "$EXPERIMENTAL_AHB" == 1 ]] || { echo "Native-link receipt requires experimental build" >&2; exit 1; }
    rm -f -- "$VLC_NATIVE_LINK_RECEIPT"
fi
if [[ "$EXPERIMENTAL_AHB" == 1 ]]; then
    JNI="${VLC_BUILD_ROOT:-$ROOT/.native/libvlcjni-ahb-experimental-${SOURCE_HASH:0:16}}"
    # Never reuse a legacy build tree; installation is separately controlled.
    if [[ -d "$JNI/vlc/.git" && ! -f "$JNI/vlc/.ambient-ahb-experimental" ]]; then
        echo "Refusing to use unmarked VLC tree for AHB experiment" >&2
        exit 1
    fi
else
    JNI="${VLC_BUILD_ROOT:-$ROOT/.native/libvlcjni-${SOURCE_HASH:0:16}}"
    [[ ! -f "$JNI/vlc/.ambient-ahb-experimental" ]] ||
        { echo "Refusing experimental tree for production VLC" >&2; exit 1; }
fi
if [[ -f "$JNI/.ambient-source-hash" && "$(<"$JNI/.ambient-source-hash")" != "$SOURCE_HASH" ]]; then
    echo "VLC build root contains a different source series; use a fresh VLC_BUILD_ROOT" >&2
    exit 1
fi
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
    if [[ "$EXPERIMENTAL_AHB" == 1 ]]; then touch "$JNI/vlc/.ambient-ahb-experimental"; fi
fi
[[ "$(git -C "$JNI" rev-parse HEAD)" == "$JNI_REF" ]] || { echo "Expected libvlcjni $JNI_REF" >&2; exit 1; }
[[ "$(git -C "$JNI/vlc" rev-parse HEAD)" == "$VLC_REF" ]] || { echo "Expected VLC $VLC_REF" >&2; exit 1; }
# A source-keyed stage is immutable. Verify the complete tracked diff and
# generated source inputs before reusing it; never undo another patch.
stage_signature() {
    (
        git -C "$JNI" diff --binary --ignore-submodules=all HEAD
        git -C "$JNI/vlc" diff --binary HEAD
        cd "$JNI/vlc"
        git ls-files --others --exclude-standard -z -- \
            modules/video_output/libplacebo modules/codec/omxil modules/demux/mp4 \
            modules/video_filter contrib/src/ffmpeg contrib/src/libplacebo |
            sort -z | xargs -0 -r sha256sum
    ) | sha256sum | cut -d ' ' -f1
}
if [[ -f "$JNI/.ambient-stage-signature" ]]; then
    [[ -f "$JNI/.ambient-source-hash" && "$(<"$JNI/.ambient-stage-signature")" == "$(stage_signature)" ]] ||
        { echo "VLC staged sources changed; use a fresh VLC_BUILD_ROOT" >&2; exit 1; }
else
    git -C "$JNI" diff --quiet --ignore-submodules=all HEAD &&
        git -C "$JNI/vlc" diff --quiet HEAD ||
        { echo "Unverified VLC build tree; use a fresh VLC_BUILD_ROOT" >&2; exit 1; }
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
# Never reset a single file to recover a conflicting patch: multiple patches
# can own the same file. A source-keyed checkout is rebuilt as a whole;
# an explicitly supplied incompatible checkout fails without losing changes.
apply_vlc_patch() {
    local patch=$1
    if git -C "$JNI/vlc" apply --check "$patch" 2>/dev/null; then
        git -C "$JNI/vlc" apply "$patch"
    elif ! git -C "$JNI/vlc" apply --reverse --check "$patch" 2>/dev/null; then
        echo "VLC patch series conflict: $patch; use a fresh VLC_BUILD_ROOT" >&2
        return 1
    fi
}
for patch in "$ROOT"/native/vlc-aar/patches/*.patch; do
    apply_vlc_patch "$patch"
done
if [[ "$EXPERIMENTAL_AHB" == 1 ]]; then
    bash "$ROOT/scripts/prepare_vlc_ahb_experimental.sh" "$JNI/vlc"
    for patch in "$ROOT"/native/vlc-aar/experimental/jni-patches/*.patch; do
        if git -C "$JNI" apply --check "$patch" 2>/dev/null; then
            git -C "$JNI" apply "$patch"
        else
            git -C "$JNI" apply --reverse --check "$patch"
        fi
    done
fi
# Use the same reviewed n9.0.2 commit as MPV. The patch selects VLC's native
# Git archive + githash verification path so the source is commit-verified.
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
# The linked seek audit uses VLC's real headless window/display modules.
# Upstream builds their archives but excludes both from the static Android link.
if [[ "$EXPERIMENTAL_AHB" == 1 ]]; then
    sed -i '/^[[:space:]]*\.dummy[[:space:]]*$/d' "$JNI/buildsystem/build-libvlc.sh"
fi
# Upstream's probe used `which` as a standalone command under set -e.
perl -0777 -i -pe 's/which autopoint >\/dev\/null\nif \[ ! \$\? -eq 0 \];then/if ! command -v autopoint >\/dev\/null; then/' "$JNI/buildsystem/build-libvlc.sh"
perl -i -pe 's/find \$1 -name \x27\$2\x27/find "\$1" -name "\$2"/' "$JNI/buildsystem/build-libvlc.sh"
    stage_signature > "$JNI/.ambient-stage-signature"
    printf '%s\n' "$SOURCE_HASH" > "$JNI/.ambient-source-hash"
fi
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
done
# Record native linkage only after every requested ABI has succeeded.
if [[ -n "${VLC_NATIVE_LINK_RECEIPT:-}" ]]; then
    receipt_tmp="$(mktemp)"
    {
        printf 'source_hash=%s\ntoolchain_hash=%s\n' "$SOURCE_HASH" "$TOOLCHAIN_HASH"
        printf 'ambient_head=%s\n' "$(git -C "$ROOT" rev-parse HEAD)"
        printf 'jni_ref=%s\nvlc_ref=%s\n' "$JNI_REF" "$VLC_REF"
        for abi in ${VLC_VULKAN_ABIS:-arm64-v8a armeabi-v7a x86 x86_64}; do
            (
                cd "$JNI/libvlc/jni/libs/$abi"
                test -s libvlc.so && test -s libvlcjni.so || exit 1
                printf 'abi=%s\n' "$abi"
                sha256sum libvlc.so libvlcjni.so
            ) || { rm -f "$receipt_tmp"; exit 1; }
        done
    } > "$receipt_tmp"
    mkdir -p "$(dirname "$VLC_NATIVE_LINK_RECEIPT")"
    mv "$receipt_tmp" "$VLC_NATIVE_LINK_RECEIPT"
fi
# Build the Java wrapper from this exact JNI revision, never mix VLC 3 Java with VLC 4 JNI.
# A transient repository rate limit must not discard a completed native build.
# Retry only explicit HTTP 429/503 responses, never compiler/configuration failures.
package_log="$(mktemp)"
package_status=0
for attempt in 1 2 3 4; do
    if env -u ANDROID_SDK_ROOT ANDROID_HOME="${ANDROID_NDK%/ndk/*}" "$ROOT/gradlew" -p "$ROOT/native/vlc-aar" assembleRelease -PVLC_SOURCE="$JNI" --console=plain 2>&1 | tee "$package_log"; then
        package_status=0
        break
    else
        package_status=${PIPESTATUS[0]}
        [[ "$package_status" != 0 ]] || package_status=1
    fi
    if [[ "$attempt" == 4 ]] || ! grep -Eq 'Received status code (429|503)( |:|$)' "$package_log"; then
        break
    fi
    delay=$((attempt * 30))
    echo "Temporary Maven rate limit/service failure; retrying AAR packaging in ${delay}s (attempt $((attempt+1))/4)" >&2
    sleep "$delay"
done
rm -f "$package_log"
if [[ "$package_status" != 0 ]]; then
    exit "$package_status"
fi
aar="$ROOT/native/vlc-aar/build/outputs/aar/libvlc-vulkan-release.aar"
if [[ "$EXPERIMENTAL_AHB" == 1 ]]; then
    # Embed an artifact capability marker. Old or Maven AARs must never
    # advertise native options that are not compiled into their libraries.
    python3 - "$aar" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "a", compression=zipfile.ZIP_DEFLATED) as z:
    name = "assets/ambient-vlc-extended-v1"
    if name not in z.namelist():
        z.writestr(name, "AHB DOVI FEL HDR10PLUS\n")
PY
    mkdir -p "$ROOT/native/vlc-aar/build/experimental"
    cp "$aar" "$ROOT/native/vlc-aar/build/experimental/libvlc-vulkan-ahb-experimental.aar"
fi
if [[ "$INSTALL_APP" == 1 ]]; then
    mkdir -p "$ROOT/app/libs"
    # Publish successful package before updating cache receipts.
    cp "$aar" "$ROOT/app/libs/libvlc-vulkan.aar.tmp"
    mv "$ROOT/app/libs/libvlc-vulkan.aar.tmp" "$ROOT/app/libs/libvlc-vulkan.aar"
    for abi in ${VLC_VULKAN_ABIS:-arm64-v8a armeabi-v7a x86 x86_64}; do
        bash "$ROOT/scripts/prepare_svp_sources.sh" --hash vlc > "$ROOT/app/libs/.svp-vlc-$abi"
        touch "$ROOT/app/libs/.projectm-pcm-vlc-$abi"
        printf '%s\n' "$SOURCE_HASH" > "$ROOT/app/libs/.vlc-source-$abi"
        printf '%s\n' "$TOOLCHAIN_HASH" > "$ROOT/app/libs/.vlc-toolchain-$abi"
    done
    echo "Installed Vulkan-enabled LibVLC AAR (extended=$EXPERIMENTAL_AHB)"
else
    echo "Built isolated experimental AHB AAR; app/libs unchanged"
fi
