#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/third_party/smarttube"
MSC="$DEST/MediaServiceCore"
SHARED="$DEST/SharedModules"
PATCHER="$ROOT/scripts/patch_smarttube_compat.py"
EXTERNAL_VERSIONS="$ROOT/gradle/external-versions.properties"

external_version() {
    local key="$1"
    sed -n "s/^${key}=//p" "$EXTERNAL_VERSIONS" | head -n 1
}

[[ -f "$EXTERNAL_VERSIONS" ]] || { echo "Missing $EXTERNAL_VERSIONS" >&2; exit 1; }
DEFAULT_MSC_REF="$(external_version SMARTTUBE_MEDIA_SERVICE_REF)"
DEFAULT_SHARED_REF="$(external_version SMARTTUBE_SHARED_MODULES_REF)"
SMARTTUBE_VERSION="$(external_version SMARTTUBE_VERSION)"
SMARTTUBE_REF="$(external_version SMARTTUBE_REF)"
SMARTTUBE_BASELINE="${SMARTTUBE_VERSION}@${SMARTTUBE_REF}"
MSC_OVERRIDE="${SMARTTUBE_MEDIA_SERVICE_REF:-}"
SHARED_OVERRIDE="${SMARTTUBE_SHARED_MODULES_REF:-}"
UPDATE="${REFRESH_YOUTUBE_ENGINE:-0}"
PATCHSET="live-v106"

log(){ printf '\n==> %s\n' "$*"; }
fail(){ printf '\nERROR: %s\n' "$*" >&2; exit 1; }

if [[ -n "$MSC_OVERRIDE" || -n "$SHARED_OVERRIDE" ]]; then
    [[ -n "$MSC_OVERRIDE" && -n "$SHARED_OVERRIDE" ]] || fail "Set SMARTTUBE_MEDIA_SERVICE_REF and SMARTTUBE_SHARED_MODULES_REF together"
    MSC_REF="$MSC_OVERRIDE"
    SHARED_REF="$SHARED_OVERRIDE"
    ENGINE_BASELINE="custom-paired-refs"
elif [[ "$UPDATE" == "1" ]]; then
    # Explicit escape hatch for developers testing upstream heads. These are intentionally not
    # claimed to be a compatible/reproducible pair; production builds should use the pinned pair
    # or pass two known-compatible refs explicitly.
    MSC_REF=""
    SHARED_REF=""
    ENGINE_BASELINE="unpaired-upstream-heads"
else
    MSC_REF="$DEFAULT_MSC_REF"
    SHARED_REF="$DEFAULT_SHARED_REF"
    ENGINE_BASELINE="$SMARTTUBE_BASELINE"
fi

command -v git >/dev/null 2>&1 || fail "git is required"
command -v python3 >/dev/null 2>&1 || fail "python3 is required"
[[ -f "$PATCHER" ]] || fail "Missing SmartTube live patcher: $PATCHER"
mkdir -p "$DEST"

prepare_repo() {
    local url="$1" dir="$2" requested_ref="$3"
    local was_cloned=0 target=""

    if [[ ! -d "$dir/.git" ]]; then
        log "Cloning $(basename "$dir") from upstream"
        git clone "$url" "$dir"
        was_cloned=1
    fi

    if [[ -n "$requested_ref" ]]; then
        log "Selecting requested SmartTube ref $requested_ref for $(basename "$dir")"
        git -C "$dir" fetch --tags origin "$requested_ref" 2>/dev/null || git -C "$dir" fetch --tags origin
        target="$requested_ref"
    elif [[ "$UPDATE" == "1" && "$was_cloned" != "1" ]]; then
        log "Refreshing $(basename "$dir") to upstream default branch"
        git -C "$dir" fetch --prune origin
        git -C "$dir" remote set-head origin -a >/dev/null 2>&1 || true
        target="$(git -C "$dir" symbolic-ref -q --short refs/remotes/origin/HEAD 2>/dev/null || true)"
        [[ -n "$target" ]] || fail "Cannot resolve origin/HEAD for $(basename "$dir"); set SMARTTUBE_*_REF explicitly"
    else
        # REFRESH_YOUTUBE_ENGINE=1 explicitly opted into the checkout's current upstream
        # revision. The normal production path supplies the pinned compatible pair above.
        target="$(git -C "$dir" rev-parse HEAD)"
    fi

    # Drop the previous run's live patches first: they would block a checkout to a new pin.
    git -C "$dir" reset --hard HEAD
    git -C "$dir" clean -fdx
    git -C "$dir" checkout --detach "$target"
}

prepare_repo https://github.com/yuliskov/MediaServiceCore.git "$MSC" "$MSC_REF"
prepare_repo https://github.com/yuliskov/SharedModules.git "$SHARED" "$SHARED_REF"

log "Applying Ambient TV compatibility patch to live SmartTube sources"
python3 "$PATCHER" --root "$DEST"
python3 "$PATCHER" --check --root "$DEST"

for wrapper in youtubeapi sharedutils mediaserviceinterfaces commons-io j2v8; do
    [[ -f "$ROOT/smarttube_wrappers/$wrapper/build.gradle.kts" ]] || fail "Missing host SmartTube wrapper: $wrapper"
done

MSC_COMMIT="$(git -C "$MSC" rev-parse HEAD)"
SHARED_COMMIT="$(git -C "$SHARED" rev-parse HEAD)"
cat > "$DEST/.engine-version" <<VERSIONS
SmartTubeBaseline=$ENGINE_BASELINE
MediaServiceCore=$MSC_COMMIT
SharedModules=$SHARED_COMMIT
COMPAT_PATCHSET=$PATCHSET
BUILD_INTEGRATION=host-wrappers-live-patch
JVM_TARGET=17
CRONET=143.7445.0
VERSIONS

log "SmartTube upstream sources live-patched for host wrappers"
cat "$DEST/.engine-version"
