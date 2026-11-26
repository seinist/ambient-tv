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
# No background gc/maintenance in the throwaway clone: it would still write while it is removed.
export GIT_CONFIG_COUNT=2 GIT_CONFIG_KEY_0=gc.auto GIT_CONFIG_VALUE_0=0 GIT_CONFIG_KEY_1=maintenance.auto GIT_CONFIG_VALUE_1=false

remove_dir() {
    local dir="$1" attempt
    for attempt in 1 2 3 4 5; do
        rm -rf "$dir" 2>/dev/null && return 0
        sleep 1
    done
    rm -rf "$dir"
}

patch_hash="$( (cd "$PATCHES" && cat ./*.patch 2>/dev/null; cat "$ROOT/scripts/instrument_libretro_trace.py" "$ROOT/scripts/native_libretro_vulkan/librashader.h" "$ROOT/scripts/native_libretro_vulkan/vulkanslangpipeline.h" "$ROOT/scripts/native_libretro_vulkan/vulkanslangpipeline.cpp" "$ROOT/scripts/native_libretro_vulkan/vulkanslanggeometry.h" "$ROOT/scripts/native_rewind/rewindbuffer.h" "$ROOT/scripts/native_rewind/rewindbuffer.cpp" "$ROOT/scripts/native_rewind/rewindtimeline.h" "$ROOT/scripts/native_rewind/rewindcapture.h") | git hash-object --stdin)"
marker="$COMMIT $patch_hash recursive"
if [[ -f "$DEST/.ambient-libretrodroid" && "$(cat "$DEST/.ambient-libretrodroid")" == "$marker" ]]; then
    log "LibretroDroid $COMMIT already prepared"
    exit 0
fi

log "Fetching LibretroDroid $COMMIT"
remove_dir "$WORK"
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
# About shows the rcheevos pin; keep it true to what is built.
rcheevos_actual="$(git -C "$WORK" rev-parse HEAD:libretrodroid/src/main/cpp/rcheevos)"
[[ "$rcheevos_actual" == "$(external_version LIBRETRODROID_RCHEEVOS_COMMIT)" ]] \
    || fail "rcheevos is at $rcheevos_actual; update LIBRETRODROID_RCHEEVOS_COMMIT/_VERSION in $EXTERNAL_VERSIONS"

remove_dir "$DEST"
mkdir -p "$(dirname "$DEST")"
cp -a "$WORK/libretrodroid" "$DEST"
remove_dir "$WORK"
find "$DEST" -name .git -prune -exec rm -rf {} +

for patch in "$PATCHES"/*.patch; do
    [[ -f "$patch" ]] || continue
    log "Applying $(basename "$patch")"
    # Ceiling dir: inside the app repo, git apply would otherwise silently skip every path.
    (cd "$DEST" && GIT_CEILING_DIRECTORIES="$(dirname "$DEST")" git apply --whitespace=nowarn "$patch") \
        || fail "Patch $(basename "$patch") does not apply"
done
# Match core-call boundaries after all source patches, independent of neighboring hunks.
python3 "$ROOT/scripts/instrument_libretro_trace.py" "$DEST/src/main/cpp/libretrodroid.cpp" \
    || fail "Native core tracing layout changed"
# The Vulkan Slang helper is authored in this repository, separate from the pinned
# upstream frontend sources. Copy AFTER applying the pinned patches so CMake sees the
# exact audited header and C++ implementation with matching ABI and GPL licensing.
cp "$ROOT/scripts/native_libretro_vulkan/librashader.h" "$DEST/src/main/cpp/vulkan/librashader.h"
cp "$ROOT/scripts/native_libretro_vulkan/vulkanslangpipeline.h" "$DEST/src/main/cpp/vulkan/vulkanslangpipeline.h"
cp "$ROOT/scripts/native_libretro_vulkan/vulkanslangpipeline.cpp" "$DEST/src/main/cpp/vulkan/vulkanslangpipeline.cpp"
cp "$ROOT/scripts/native_libretro_vulkan/vulkanslanggeometry.h" "$DEST/src/main/cpp/vulkan/vulkanslanggeometry.h"
# Keep the host-tested compressed history implementation identical to Android's.
# This replaces the upstream full-state ring without another runtime dependency.
cp "$ROOT/scripts/native_rewind/rewindbuffer.h" "$DEST/src/main/cpp/rewindbuffer.h"
cp "$ROOT/scripts/native_rewind/rewindbuffer.cpp" "$DEST/src/main/cpp/rewindbuffer.cpp"
cp "$ROOT/scripts/native_rewind/rewindtimeline.h" "$DEST/src/main/cpp/rewindtimeline.h"
cp "$ROOT/scripts/native_rewind/rewindcapture.h" "$DEST/src/main/cpp/rewindcapture.h"

grep -q "RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:" "$DEST/src/main/cpp/environment.cpp" \
    || fail "Core options patch missing after apply"
echo "$marker" > "$DEST/.ambient-libretrodroid"
log "LibretroDroid ready in third_party/libretrodroid"
