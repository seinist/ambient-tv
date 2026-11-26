#!/usr/bin/env bash
# Fetch the pinned Moonlight Android release together with the GameStream native dependencies that
# release pins itself (moonlight-common-c, its modified ENet and nanors) and apply Ambient TV's
# reviewed patches. Only the protocol layer is staged for the :moonlight_native module; upstream's
# launcher UI is never compiled.
#
# Idempotent: a tree prepared from the same pins, patches and staging rules is kept. A tree prepared
# from other pins (for example after ./build.sh libs-update moonlight) is replaced automatically,
# because this script owns third_party/moonlight-android. A directory it did not create stops the
# script instead of being deleted.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROPERTIES="$ROOT/gradle/external-versions.properties"
read_pin() { sed -n "s/^$1=//p" "$PROPERTIES" | head -n 1; }
log() { printf '\n==> %s\n' "$*"; }
die() { printf 'Moonlight source preparation: %s\n' "$*" >&2; exit 1; }

UPSTREAM="$(read_pin MOONLIGHT_ANDROID_REPO)"
REF="$(read_pin MOONLIGHT_ANDROID_REF)"
TAG="$(read_pin MOONLIGHT_ANDROID_TAG)"
SUBMODULE="app/src/main/jni/moonlight-core/moonlight-common-c"
DEST="$ROOT/third_party/moonlight-android"
WORK="$ROOT/third_party/.moonlight-download"
MARKER_NAME=".ambient-moonlight-pins"
# Bump when the staged file set changes, so existing trees are prepared again.
STAGING_VERSION=4

[[ "$UPSTREAM" == "https://github.com/moonlight-stream/moonlight-android.git" ]] || die "unexpected upstream"
[[ "$REF" =~ ^[[:xdigit:]]{40}$ ]] || die "full pinned commit required"
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+(\.[0-9]+)?$ ]] || die "invalid release tag"
command -v git >/dev/null || die "git not found"
# No background gc/maintenance in the throwaway clone: it would still write while it is moved.
export GIT_CONFIG_COUNT=2 GIT_CONFIG_KEY_0=gc.auto GIT_CONFIG_VALUE_0=0 \
    GIT_CONFIG_KEY_1=maintenance.auto GIT_CONFIG_VALUE_1=false GIT_TERMINAL_PROMPT=0

PATCH_DIR="$ROOT/scripts/patches/moonlight"
shopt -s nullglob
PATCHES=("$PATCH_DIR"/*.patch)
shopt -u nullglob
[[ ${#PATCHES[@]} -gt 0 ]] || die "reviewed Moonlight patches missing in $PATCH_DIR"
PATCH_SHA="$( { cat "${PATCHES[@]}" "$ROOT/scripts/moonlight/native-artifacts.git-blobs" "$ROOT/scripts/moonlight/licenses/"*.txt; printf 'staging=%s\n' "$STAGING_VERSION"; } | git hash-object --stdin)"

if [[ -f "$DEST/$MARKER_NAME" &&
      "$(head -n 1 "$DEST/$MARKER_NAME")" == "android=$REF" &&
      "$(sed -n 's/^patch=//p' "$DEST/$MARKER_NAME")" == "$PATCH_SHA" &&
      -f "$DEST/$SUBMODULE/src/Connection.c" &&
      -f "$DEST/$SUBMODULE/enet/include/enet/enet.h" &&
      -f "$DEST/app/protocol-only-src/com/limelight/LimeLog.java" &&
      -f "$DEST/app/protocol-only-src/com/limelight/binding/video/MediaCodecHelper.java" &&
      -s "$DEST/app/protocol-only-assets/moonlight-licenses/moonlight-android-LICENSE.txt" ]]; then
    printf 'Moonlight %s (%s) already prepared\n' "$TAG" "$REF"
    exit 0
fi

remove_dir() {
    local dir="$1" attempt
    for attempt in 1 2 3 4 5; do
        rm -rf "$dir" 2>/dev/null && return 0
        sleep 1
    done
    rm -rf "$dir"
}

if [[ -e "$DEST" ]]; then
    # Only a tree this script prepared is replaced; anything else needs a person to look at it.
    [[ -f "$DEST/$MARKER_NAME" ]] || die "$DEST exists but was not prepared by this script; review and remove it"
    log "Replacing Moonlight sources prepared from other pins or patches"
fi
# The scratch directory belongs to this script; a leftover from an interrupted run is removed.
remove_dir "$WORK"
mkdir -p "$ROOT/third_party"
trap 'remove_dir "$WORK"' EXIT

log "Fetching Moonlight Android $TAG ($REF)"
git init -q "$WORK"
git -C "$WORK" remote add origin "$UPSTREAM"
git -C "$WORK" fetch -q --depth 1 origin "$REF"
git -C "$WORK" checkout -q --detach FETCH_HEAD
[[ "$(git -C "$WORK" rev-parse HEAD)" == "$REF" ]] || die "fetched commit differs from pin"

git -C "$WORK" fetch -q --depth 1 origin "refs/tags/$TAG:refs/tags/$TAG"
[[ "$(git -C "$WORK" rev-parse "$TAG^{commit}")" == "$REF" ]] || die "release tag $TAG does not match commit $REF"
GITLINK="$(git -C "$WORK" ls-tree HEAD "$SUBMODULE" | awk '$1 == "160000" && $2 == "commit" { print $3 }')"
[[ "$GITLINK" =~ ^[[:xdigit:]]{40}$ ]] || die "common-c gitlink missing"

# Exactly the native core, modified ENet and nanors the official release pinned.
# Never replace the ENet gitlink with a random system or package-manager version.
git -C "$WORK" submodule update --init --recursive --depth 1 -- "$SUBMODULE"
[[ "$(git -C "$WORK/$SUBMODULE" rev-parse HEAD)" == "$GITLINK" ]] || die "common-c commit mismatch"
[[ -f "$WORK/$SUBMODULE/enet/include/enet/enet.h" ]] || die "modified ENet missing"
[[ -f "$WORK/$SUBMODULE/nanors/rs.c" ]] || die "nanors missing"
[[ -f "$WORK/app/src/main/jni/moonlight-core/Android.mk" ]] || die "native build contract missing"
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    [[ -s "$WORK/app/src/main/jni/moonlight-core/opus/$abi/libopus.a" ]] || die "upstream Opus for $abi missing"
    [[ -s "$WORK/app/src/main/jni/moonlight-core/openssl/$abi/libcrypto.a" ]] || die "upstream OpenSSL for $abi missing"
done

# The upstream Android dependency update identifies the precise build and sources.
# New binary artifacts require an explicit provenance/license review.
while read -r expected relative; do
    [[ "$expected" =~ ^[[:xdigit:]]{40}$ ]] || die "invalid dependency blob pin"
    [[ "$(git hash-object "$WORK/app/src/main/jni/moonlight-core/$relative")" == "$expected" ]] ||
        die "Unreviewed native dependency artifact: $relative"
done < "$ROOT/scripts/moonlight/native-artifacts.git-blobs"

# Reviewed, fail-closed patches. A patch that no longer applies means upstream changed the code it
# protects: stop instead of building an unreviewed client.
for patch in "${PATCHES[@]}"; do
    git -C "$WORK" apply --check "$patch" 2>/dev/null || die "upstream changed; patch needs review: $(basename "$patch")"
    git -C "$WORK" apply "$patch"
done
NVHTTP="$WORK/app/src/main/java/com/limelight/nvstream/http/NvHTTP.java"
grep -q 'this.uniqueId = uniqueId;' "$NVHTTP" || die "client identity patch did not apply"
grep -q 'Pinned host certificate changed' "$NVHTTP" || die "strict certificate pin patch did not apply"
grep -q 'A previously paired host must never be silently downgraded' "$NVHTTP" || die "HTTPS anti-downgrade patch did not apply"
grep -q 'readTimeout(120, TimeUnit.SECONDS)' "$NVHTTP" || die "bounded PIN wait patch did not apply"
grep -q 'max-page-size=16384' "$WORK/app/src/main/jni/moonlight-core/Android.mk" || die "16-KiB ELF alignment patch did not apply"
grep -q 'nativeSlotHeld.compareAndSet(true, false)' "$WORK/app/src/main/java/com/limelight/nvstream/NvConnection.java" ||
    die "safe native stream stop patch did not apply"
grep -q 'worker.join(50);' "$WORK/app/src/main/java/com/limelight/nvstream/NvConnection.java" ||
    die "serialized native stream stop patch did not apply"
grep -q 'setAmbientStartPolicy' "$WORK/app/src/main/java/com/limelight/nvstream/NvConnection.java" || die "resume-only patch did not apply"
grep -q 'cancelPendingRequests()' "$NVHTTP" || die "cancellable HTTP patch did not apply"
grep -q 'Moonlight response exceeds limit' "$NVHTTP" || die "bounded HTTP patch did not apply"
grep -q 'context.connListener.displayMessage(AMBIENT_OTHER_APP_RUNNING);' "$WORK/app/src/main/java/com/limelight/nvstream/NvConnection.java" ||
    die "no-implicit-remote-quit patch did not apply"

# Stage only the protocol layer: nvstream, LimeLog and the decoder helper (vendor low-latency
# options, AV1/HEVC decoder whitelists). Not Moonlight's Android launcher, preferences or UI.
PROTOCOL="$WORK/app/protocol-only-src/com/limelight"
mkdir -p "$PROTOCOL/binding/video"
cp -a "$WORK/app/src/main/java/com/limelight/nvstream" "$PROTOCOL/nvstream"
# Ambient TV uses Android NsdManager for on-demand discovery. The upstream JmDNS implementation is
# launcher-specific and needs javax.jmdns; it is not part of the streaming transport or auth.
rm -rf "$PROTOCOL/nvstream/mdns"
cp "$WORK/app/src/main/java/com/limelight/LimeLog.java" "$PROTOCOL/LimeLog.java"
HELPER="$WORK/app/src/main/java/com/limelight/binding/video/MediaCodecHelper.java"
[[ -f "$HELPER" ]] || die "MediaCodecHelper missing upstream"
# The helper only imports PreferenceConfiguration (the launcher's settings class) without using it.
# Drop exactly that import; any real use is an upstream change that needs review.
[[ "$(grep -c 'PreferenceConfiguration' "$HELPER")" == "1" ]] ||
    die "MediaCodecHelper now uses PreferenceConfiguration; review before staging"
grep -v '^import com\.limelight\.preferences\.PreferenceConfiguration;$' "$HELPER" > "$PROTOCOL/binding/video/MediaCodecHelper.java"
if grep -E '^import com\.limelight\.' "$PROTOCOL/binding/video/MediaCodecHelper.java" | grep -v 'com\.limelight\.LimeLog;' >/dev/null; then
    die "MediaCodecHelper depends on further launcher classes; review before staging"
fi

# Retain upstream license texts and exact source/patch provenance inside the native APK.
LICENSES="$WORK/app/protocol-only-assets/moonlight-licenses"
mkdir -p "$LICENSES"
cp "$WORK/LICENSE.txt" "$LICENSES/moonlight-android-LICENSE.txt"
cp "$WORK/$SUBMODULE/LICENSE.txt" "$LICENSES/moonlight-common-c-LICENSE.txt"
cp "$ROOT/scripts/moonlight/licenses/"*.txt "$LICENSES/"
cp "$ROOT/scripts/moonlight/native-artifacts.git-blobs" "$LICENSES/native-artifacts.git-blobs"
for component in enet nanors; do
    while IFS= read -r -d '' license; do
        relative="${license#"$WORK/$SUBMODULE/$component/"}"
        mkdir -p "$LICENSES/$component/$(dirname "$relative")"
        cp "$license" "$LICENSES/$component/$relative"
    done < <(find "$WORK/$SUBMODULE/$component" -type f \( -iname 'license*' -o -iname 'copying*' -o -iname 'notice*' \) -print0)
done
ENET_SHA="$(git -C "$WORK/$SUBMODULE/enet" rev-parse HEAD)"
NANORS_SHA="$(git -C "$WORK/$SUBMODULE/nanors" rev-parse HEAD)"
{
    printf 'Moonlight Android: %s (%s)\n' "$TAG" "$REF"
    printf 'moonlight-common-c: %s\nModified ENet: %s\nNanoRS: %s\n' "$GITLINK" "$ENET_SHA" "$NANORS_SHA"
    printf 'Corresponding integration source: https://github.com/seinist/ambient-tv\n'
    printf 'Upstream source: %s\n' "$UPSTREAM"
    printf 'Dependency update: https://github.com/moonlight-stream/moonlight-android/commit/31b700304a9eb2827516f52c33d9da0845fc1ece\n'
    printf 'Android build scripts (NDK 29.0.14206865): https://github.com/cgutman/moonlight-mobile-deps/tree/419349aa689e8749a9fb91a22dc45531ddd3df45\n'
    printf 'Opus 1.6.1 corresponding source: https://github.com/xiph/opus/tree/22244de5a79bd1d6d623c32e72bf1954b56235be\n'
    printf 'OpenSSL 4.0.2 corresponding source: https://github.com/openssl/openssl/tree/f089acdf4bc7ba94a79f4bf6eb7362c3e7d14aa9\n'
    printf 'Ambient TV modifications (reviewed through 2026-11-26):\n'
    for patch in "${PATCHES[@]}"; do basename "$patch"; done
    printf 'See docs/MOONLIGHT_INTEGRATION.md and scripts/patches/moonlight.\n'
} > "$LICENSES/SOURCE-NOTICE.txt"
printf 'android=%s\ntag=%s\ncommon-c=%s\nenet=%s\npatch=%s\n' "$REF" "$TAG" "$GITLINK" "$ENET_SHA" "$PATCH_SHA" > "$WORK/$MARKER_NAME"

# Swap in the prepared tree only after every check passed.
if [[ -e "$DEST" ]]; then
    remove_dir "$DEST"
fi
mv "$WORK" "$DEST"
trap - EXIT
printf 'Moonlight %s prepared in %s (common-c %s, ENet %s)\n' "$TAG" "$DEST" "$GITLINK" "$ENET_SHA"
