#!/usr/bin/env bash
# Fetches the pinned LibretroDroid module (Argosy launcher fork) into third_party/libretrodroid
# and applies Ambient TV's patches. Idempotent: an up-to-date checkout with the same patches is kept.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/third_party/libretrodroid"
WORK="$ROOT/third_party/.libretrodroid-src"
PATCHES="$ROOT/scripts/patches/libretrodroid"
EXTERNAL_VERSIONS="$ROOT/gradle/external-versions.properties"

external_version() { sed -n "s/^$1=//p" "$EXTERNAL_VERSIONS" | head -n 1; }
log(){ printf '\n==> %s\n' "$*"; }
fail(){ printf '\nERROR: %s\n' "$*" >&2; exit 1; }

REPO="$(external_version LIBRETRODROID_REPO)"
COMMIT="$(external_version LIBRETRODROID_COMMIT)"
[[ -n "$REPO" && -n "$COMMIT" ]] || fail "LIBRETRODROID_REPO/COMMIT missing in $EXTERNAL_VERSIONS"
command -v git >/dev/null 2>&1 || fail "git is required"

patch_hash="$( (cd "$PATCHES" && cat ./*.patch 2>/dev/null) | git hash-object --stdin)"
marker="$COMMIT $patch_hash recursive"
if [[ -f "$DEST/.ambient-libretrodroid" && "$(cat "$DEST/.ambient-libretrodroid")" == "$marker" ]]; then
    log "LibretroDroid $COMMIT already prepared"
    exit 0
fi

log "Fetching LibretroDroid $COMMIT"
rm -rf "$WORK"
mkdir -p "$WORK"
git -C "$WORK" init -q
git -C "$WORK" remote add origin "$REPO"
git -C "$WORK" sparse-checkout set --no-cone /.gitmodules /libretrodroid/
git -C "$WORK" fetch -q --depth 1 origin "$COMMIT"
git -C "$WORK" checkout -q FETCH_HEAD
[[ "$(git -C "$WORK" rev-parse HEAD)" == "$COMMIT" ]] || fail "Fetched commit does not match the pin"
# Only the module's own submodules, at the commit's gitlinks.
git -C "$WORK" submodule update --init --recursive --depth 1 \
    libretrodroid/src/main/cpp/rcheevos libretrodroid/src/main/cpp/libadrenotools

rm -rf "$DEST"
mkdir -p "$(dirname "$DEST")"
cp -a "$WORK/libretrodroid" "$DEST"
rm -rf "$WORK"
find "$DEST" -name .git -prune -exec rm -rf {} +

for patch in "$PATCHES"/*.patch; do
    [[ -f "$patch" ]] || continue
    log "Applying $(basename "$patch")"
    # Ceiling dir: inside the app repo, git apply would otherwise silently skip every path.
    (cd "$DEST" && GIT_CEILING_DIRECTORIES="$(dirname "$DEST")" git apply --whitespace=nowarn "$patch") \
        || fail "Patch $(basename "$patch") does not apply"
done
grep -q "RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:" "$DEST/src/main/cpp/environment.cpp" \
    || fail "Core options patch missing after apply"
echo "$marker" > "$DEST/.ambient-libretrodroid"
log "LibretroDroid ready in third_party/libretrodroid"
