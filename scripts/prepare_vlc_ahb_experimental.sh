#!/usr/bin/env bash
# Explicitly stage the experimental renderer into a dedicated Android VLC checkout.
# Never apply this to the normal production source tree.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/native/vlc-aar/experimental"
VLC="${1:?Usage: prepare_vlc_ahb_experimental.sh <isolated VLC checkout>}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_NDK:-$HOME/Android/Sdk/ndk/30.0.16248370}}}"
[[ "$(git -C "$VLC" rev-parse HEAD)" == c84ef1eda4f14f8b57a5b266b51c843cf51ed28b ]] ||
    { echo "Unexpected VLC revision" >&2; exit 1; }
[[ -f "$VLC/.ambient-ahb-experimental" ]] ||
    { echo "Refusing to patch unmarked production checkout" >&2; exit 1; }
case "$(uname -s)" in
    Linux) host=linux-x86_64 ;;
    Darwin) host=darwin-x86_64 ;;
    *) echo "Unsupported build host" >&2; exit 1 ;;
esac
GLSLC="$NDK/shader-tools/$host/glslc"
if [[ ! -x "$GLSLC" ]]; then GLSLC="$(command -v glslc || true)"; fi
[[ -n "$GLSLC" && -x "$GLSLC" ]] || { echo "glslc unavailable" >&2; exit 1; }
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
for stage in vert frag; do
    "$GLSLC" --target-env=vulkan1.1 "$SRC/sample.$stage" -o "$TMP/sample.$stage.spv"
done
python3 - "$TMP" <<'PY'
import pathlib, struct, sys
root = pathlib.Path(sys.argv[1])
with (root / "ambient_sample_spv.h").open("w") as out:
    out.write("#pragma once\n#include <stdint.h>\n")
    for stage in ("vert", "frag"):
        raw = (root / f"sample.{stage}.spv").read_bytes()
        assert len(raw) >= 20 and len(raw) % 4 == 0
        words = struct.unpack(f"<{len(raw)//4}I", raw)
        assert words[0] == 0x07230203
        out.write(f"static const uint32_t ambient_sample_{stage}[] = {{\n")
        for i in range(0, len(words), 8):
            out.write("    " + ", ".join(f"0x{v:08x}u" for v in words[i:i+8]) + ",\n")
        out.write("};\n")
PY
# Check the entire ordered series, not individual patches. Later patches may
# change context introduced by earlier ones (e.g. seek-request-id/result).
# Use a private index populated from the worktree, so reverse verification
# never modifies the checkout or its real index. All patches in this series
# modify existing tracked VLC files.
patches=("$SRC"/vlc-patches/*.patch)
snapshot_index() {
    GIT_INDEX_FILE="$TMP/patch-index" git -C "$VLC" read-tree --reset HEAD || exit 1
    GIT_INDEX_FILE="$TMP/patch-index" git -C "$VLC" add -u || exit 1
}
snapshot_index
already_applied=true
for ((i=${#patches[@]}-1; i>=0; i--)); do
    if ! GIT_INDEX_FILE="$TMP/patch-index" git -C "$VLC" apply --cached --reverse "${patches[i]}" 2>/dev/null; then
        already_applied=false
        break
    fi
done
if [[ "$already_applied" == true ]]; then
    echo "Experimental VLC patch series already applied"
else
    # Reject partial/conflicting series before modifying any tracked file.
    # Reset only the private index after the speculative reverse pass.
    snapshot_index
    for patch in "${patches[@]}"; do
        echo "Checking experimental VLC patch: $(basename "$patch")"
        GIT_INDEX_FILE="$TMP/patch-index" git -C "$VLC" apply --cached "$patch" || exit 1
    done
    for patch in "${patches[@]}"; do
        git -C "$VLC" apply "$patch" || exit 1
    done
fi
for file in dovi_fel_hvce.h dovi_fel_dual.h ambient_metadata_wire.h; do
    cp "$SRC/$file" "$VLC/modules/demux/mp4/$file"
done
dest="$VLC/modules/video_output/libplacebo"
for file in ahb_import.h conversion_cache.h conversion_key.h display_bridge.h display_owner.h display_owner.cpp \
    frame_lease.h placebo_bridge.h present_retirement.h terminal_init_retirement.h queue_transfer.h queue_retirement.h rgb_target.h sample_pass.h slot_pool.h \
    submission.h sync_fd.h unique_fd.h vlc_picture_bridge.h; do
    cp "$SRC/$file" "$dest/$file"
done
cp "$TMP/ambient_sample_spv.h" "$dest/ambient_sample_spv.h"
cp "$SRC/hdr10plus_payload.h" "$VLC/modules/codec/omxil/hdr10plus_payload.h"
cp "$SRC/dovi_rpu_au.h" "$VLC/modules/codec/omxil/dovi_rpu_au.h"
for file in hdr10plus_spatial.h hdr10plus_spatial_geometry.h dovi_fel_gpu.h dovi_fel_metadata.h dovi_fel_nlq.h dovi_fel_render.h; do
    cp "$SRC/$file" "$dest/$file"
done
for file in ambient_dovi_parser.h dovi_fel_api.h dovi_fel_geometry.h dovi_vlc_bridge.h hdr10plus_payload.h hdr10plus_vlc_bridge.h; do
    cp "$SRC/$file" "$VLC/modules/codec/omxil/$file"
    cp "$SRC/$file" "$dest/$file"
done
# The reviewed production FFmpeg 9 patch is already applied in the linked
# build. Keep the additional private RPU interface isolated to this tree.
cp "$SRC/ffmpeg-patches/ambient-dovi-parser.patch" "$VLC/contrib/src/ffmpeg/ambient-dovi-parser.patch"
python3 - "$VLC/contrib/src/ffmpeg/rules.mak" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
s = p.read_text()
anchor = "\t$(APPLY) $(SRC)/ffmpeg/0010-restore-v308-v408-v410-decoders.patch\n"
line = "\t$(APPLY) $(SRC)/ffmpeg/ambient-dovi-parser.patch\n"
if line not in s:
    if s.count(anchor) != 1:
        raise SystemExit("Expected reviewed FFmpeg 9 production patch before experimental staging")
    s = s.replace(anchor, anchor + line)
# VLC contrib disables almost all bitstream filters. Keep the EL splitter
# available only in the isolated experimental native build.
bsf_anchor = "--enable-bsf=vp9_superframe"
bsf_flag = "--enable-bsf=dovi_split"
if bsf_flag not in s:
    if s.count(bsf_anchor) != 1:
        raise SystemExit("Expected pinned FFmpeg bitstream filter configuration")
    s = s.replace(bsf_anchor, bsf_anchor + " " + bsf_flag)
p.write_text(s)
PY
# The pinned libplacebo shader generator has a missing ')' for mixed MMR
# orders reaching order 3. Apply only inside the isolated experimental tree.
cp "$SRC/libplacebo-patches/ambient-mmr-order3.patch" "$VLC/contrib/src/libplacebo/ambient-mmr-order3.patch"
cp "$SRC/libplacebo-patches/ambient-wsi-retirement.patch" "$VLC/contrib/src/libplacebo/ambient-wsi-retirement.patch"
python3 - "$VLC/contrib/src/libplacebo/rules.mak" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
s = p.read_text()
anchor = "\t$(APPLY) $(SRC)/libplacebo/0001-vulkan-utils_gen-fix-for-python-3.14.patch\n"
line = "\t$(APPLY) $(SRC)/libplacebo/ambient-mmr-order3.patch\n"
if line not in s:
    if s.count(anchor) != 1:
        raise SystemExit("Expected pinned libplacebo contrib patch")
    s = s.replace(anchor, anchor + line)
wsi = "\t$(APPLY) $(SRC)/libplacebo/ambient-wsi-retirement.patch\n"
if wsi not in s:
    if s.count(line) != 1:
        raise SystemExit("Expected MMR patch before WSI retirement patch")
    s = s.replace(line, line + wsi)
p.write_text(s)
PY
echo "Staged experimental VLC AHB display integration (runtime flag remains off by default)"
