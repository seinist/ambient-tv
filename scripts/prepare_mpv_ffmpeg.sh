#!/usr/bin/env bash
# Apply project decoder patches to the pinned FFmpeg and mpv sources, idempotently.
# Patches are a series in glob order and later ones may touch the same file as earlier ones,
# so a previous run is undone newest-first before the whole series is applied again.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="${1:?MPV buildscripts directory required}"

apply_series() {
    local dir="$1" prefix="$2" i
    local patches=("$ROOT"/native/ambientmpv/patches/"$prefix"-*.patch)
    for ((i = ${#patches[@]} - 1; i >= 0; i--)); do
        if git -C "$dir" apply --reverse --check "${patches[i]}" 2>/dev/null; then
            git -C "$dir" apply --reverse "${patches[i]}"
        fi
    done
    # A changed patch (any of the series, not only the first) cannot undo the older version of itself
    # that a previous build applied. Check the whole series in order; if it does not apply cleanly,
    # only the files of this series can differ: restore them from the pinned commit (self-healing).
    if ! git -C "$dir" apply --check "${patches[@]}" 2>/dev/null; then
        local files f
        mapfile -t files < <(sed -n 's#^+++ b/##p' "${patches[@]}" | sort -u)
        echo "$prefix: an older patch series is applied; restoring ${files[*]} from $(git -C "$dir" rev-parse --short HEAD)" >&2
        for f in "${files[@]}"; do
            if git -C "$dir" cat-file -e "HEAD:$f" 2>/dev/null; then
                git -C "$dir" checkout HEAD -- "$f"
            else
                rm -f "$dir/$f" # Added by an older patch version.
            fi
        done
    fi
    for i in "${patches[@]}"; do
        git -C "$dir" apply "$i"
    done
}

apply_series "$BUILD/deps/ffmpeg" ffmpeg
apply_series "$BUILD/deps/mpv" mpv
