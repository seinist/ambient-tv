#!/usr/bin/env python3
"""Stage HDR10+ spatial mapping after the private MPV FEL patch.
Only SVP-generated, measured intermediate frames opt into this path.
"""
import pathlib
import shutil
import sys
source = pathlib.Path(sys.argv[1])
adapter = pathlib.Path(__file__).resolve().parents[1] / "native/ambientsvp/adapters"
shutil.copyfile(adapter / "svp_mpv_hdr_spatial.h", source / "video/out/svp_mpv_hdr_spatial.h")
out = source / "video/out/hdr10plus"
out.mkdir(exist_ok=True)
for path in sorted((adapter / "hdr10plus").glob("*.h")):
    shutil.copyfile(path, out / path.name)
def patch(path, edits, marker):
    p = source / path
    s = p.read_text()
    if marker in s:
        for _, new in edits:
            if new not in s:
                raise SystemExit("Incomplete SVP HDR spatial staging: " + path)
        return
    for old, new in edits:
        if s.count(old) != 1:
            raise SystemExit("SVP HDR spatial anchor mismatch: " + path + " " + old[:80])
        s = s.replace(old, new)
    p.write_text(s)
patch("video/mp_image.h", [
    ("    struct AVBufferRef *ambient_svp_pair; // SVP FEL private source pair",
     "    struct AVBufferRef *ambient_svp_pair; // SVP FEL private source pair\n    bool ambient_svp_hdr_generated; // SVP measured HDR10+"),
], "SVP measured HDR10+")
patch("video/mp_image.c", [
    ("    new->num_ff_side_data = 0;",
     "    new->num_ff_side_data = 0;\n    new->ambient_svp_hdr_generated = false; // SVP HDR dummy"),
    ("    assign_bufref(&dst->ambient_svp_pair, src->ambient_svp_pair);",
     "    assign_bufref(&dst->ambient_svp_pair, src->ambient_svp_pair);\n    dst->ambient_svp_hdr_generated = src->ambient_svp_hdr_generated;"),
], "SVP HDR dummy")
patch("video/out/vo_gpu_next.c", [
    ("    struct svp_fel_vo_cache *svp_fel; // SVP FEL renderer cache",
     "    struct svp_fel_vo_cache *svp_fel; // SVP FEL renderer cache\n    uint64_t svp_hdr_serial; // SVP HDR spatial cache revision"),
    ("    pl_tex svp_fel_output;",
     "    pl_tex svp_fel_output;\n    struct svp_hdr_spatial_frame *svp_hdr;"),
    ('#include "svp_mpv_fel_vo.h"',
     '#include "svp_mpv_hdr_spatial.h"\n#include "svp_mpv_fel_vo.h"'),
    ("    pl_tex_destroy(gpu, &fp->svp_fel_output);",
     "    svp_hdr_spatial_destroy(gpu, &fp->svp_hdr);\n    pl_tex_destroy(gpu, &fp->svp_fel_output);"),
    ("    bool render_ok = pl_render_image_mix(p->rr, &mix, &target, &params);",
     """    // Keep queue frames immutable. Each mapped output is owned by its
    // own queue entry, and target changes invalidate its mix signature.
    struct pl_frame_mix spatial_mix = mix;
    struct pl_frame spatial_frames[16];
    const struct pl_frame *spatial_ptrs[16];
    uint64_t spatial_signatures[16];
    unsigned spatial_count=0;
    if (mix.num_frames > 0 && mix.num_frames <= 16) {
        for (int i=0; i<mix.num_frames; i++) {
            spatial_ptrs[i]=mix.frames[i];
            spatial_signatures[i]=mix.signatures[i];
            uint64_t revision;
            if (svp_hdr_spatial_map(p,mix.frames[i],&target,opts->target_peak,
                                    &spatial_frames[i],&revision)) {
                spatial_ptrs[i]=&spatial_frames[i];
                spatial_signatures[i]^=UINT64_C(0x9e3779b97f4a7c15)*revision;
                spatial_count++;
            }
        }
        spatial_mix.frames=spatial_ptrs;
        spatial_mix.signatures=spatial_signatures;
    }
    struct pl_render_params spatial_params=params;
    if(spatial_count && spatial_count==(unsigned)mix.num_frames)
        spatial_params.peak_detect_params=NULL;
    bool render_ok = pl_render_image_mix(p->rr, &spatial_mix, &target, &spatial_params);"""),
], "SVP HDR spatial cache revision")
