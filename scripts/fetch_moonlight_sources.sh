#!/usr/bin/env bash
# Fetch exact upstream Moonlight Android tag + its own nested GameStream native dependencies.
# Source preparation only; this script does NOT build a playable Moonlight client.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROPERTIES="$ROOT/gradle/external-versions.properties"
read_pin() { sed -n "s/^$1=//p" "$PROPERTIES" | head -n 1; }
die() { printf 'Moonlight source preparation: %s\n' "$*" >&2; exit 1; }

UPSTREAM="$(read_pin MOONLIGHT_ANDROID_REPO)"
REF="$(read_pin MOONLIGHT_ANDROID_REF)"
TAG="$(read_pin MOONLIGHT_ANDROID_TAG)"
SUBMODULE="app/src/main/jni/moonlight-core/moonlight-common-c"
DEST="$ROOT/third_party/moonlight-android"
WORK="$ROOT/third_party/.moonlight-download"

[[ "$UPSTREAM" == "https://github.com/moonlight-stream/moonlight-android.git" ]] || die "unexpected upstream"
[[ "$REF" =~ ^[[:xdigit:]]{40}$ ]] || die "full pinned commit required"
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+(\.[0-9]+)?$ ]] || die "invalid release tag"
command -v git >/dev/null || die "git not found"

PATCH="$ROOT/scripts/patches/moonlight/0001-use-per-install-client-identity.patch"
[[ -f "$PATCH" ]] || die "reviewed client identity patch missing"
PATCH_SHA="$(git hash-object "$PATCH")"
if [[ -f "$DEST/.ambient-moonlight-pins" &&
      "$(head -n 1 "$DEST/.ambient-moonlight-pins")" == "android=$REF" &&
      "$(sed -n 's/^patch=//p' "$DEST/.ambient-moonlight-pins")" == "$PATCH_SHA" &&
      -f "$DEST/$SUBMODULE/src/Connection.c" &&
      -f "$DEST/$SUBMODULE/enet/include/enet/enet.h" ]]; then
  printf 'Already prepared upstream Moonlight %s\n' "$REF"
  exit 0
fi
[[ ! -e "$DEST" ]] || die "existing $DEST requires explicit review/removal"
[[ ! -e "$WORK" ]] || die "existing $WORK requires explicit review/removal"
mkdir -p "$ROOT/third_party"
trap 'rm -rf "$WORK"' EXIT

git init -q "$WORK"
git -C "$WORK" config gc.auto 0
git -C "$WORK" remote add origin "$UPSTREAM"
git -C "$WORK" fetch -q --depth 1 origin "$REF"
git -C "$WORK" checkout -q --detach FETCH_HEAD
[[ "$(git -C "$WORK" rev-parse HEAD)" == "$REF" ]] || die "fetched commit differs from pin"

git -C "$WORK" fetch -q --depth 1 origin "refs/tags/$TAG:refs/tags/$TAG"
[[ "$(git -C "$WORK" rev-parse "$TAG^{commit}")" == "$REF" ]] || die "release tag does not match commit"
GITLINK="$(git -C "$WORK" ls-tree HEAD "$SUBMODULE" | awk '$1 == "160000" && $2 == "commit" { print $3 }')"
[[ "$GITLINK" =~ ^[[:xdigit:]]{40}$ ]] || die "common-c gitlink missing"

# Uses exactly the native core, modified ENet and nanors the official release pinned.
# Never replace the ENet gitlink with a random system or package-manager version.
git -C "$WORK" submodule update --init --recursive --depth 1 -- "$SUBMODULE"
[[ "$(git -C "$WORK/$SUBMODULE" rev-parse HEAD)" == "$GITLINK" ]] || die "common-c commit mismatch"
[[ -f "$WORK/$SUBMODULE/enet/include/enet/enet.h" ]] || die "modified ENet missing"
[[ -f "$WORK/$SUBMODULE/nanors/rs.c" ]] || die "nanors missing"
[[ -f "$WORK/app/src/main/jni/moonlight-core/Android.mk" ]] || die "native build contract missing"

# Apply the single reviewed, fail-closed Ambient TV identity patch to the pinned source.
# It does not change TLS, pairing challenge validation or the modified ENet transport.
git -C "$WORK" apply --check "$PATCH" || die "upstream changed; client identity patch needs review"
git -C "$WORK" apply "$PATCH"
grep -q 'this.uniqueId = uniqueId;' "$WORK/app/src/main/java/com/limelight/nvstream/http/NvHTTP.java" ||
    die "client identity patch did not apply"

mv "$WORK" "$DEST"
trap - EXIT
ENET_SHA="$(git -C "$DEST/$SUBMODULE/enet" rev-parse HEAD)"
printf 'android=%s\ncommon-c=%s\nenet=%s\npatch=%s\n' "$REF" "$GITLINK" "$ENET_SHA" "$PATCH_SHA" > "$DEST/.ambient-moonlight-pins"
printf 'Pinned Moonlight sources prepared in %s\n' "$DEST"
printf 'Pairing, JNI bridge and streaming player are still NOT integrated.\n'
