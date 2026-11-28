#!/usr/bin/env python3
"""Stage private FEL pair ownership and reconstruction in the pinned MPV."""
import pathlib
import shutil
import sys
source = pathlib.Path(sys.argv[1])
adapter = pathlib.Path(__file__).resolve().parents[1] / "native/ambientsvp/adapters"
for directory in ("video/filter", "video/out"):
    shutil.copyfile(adapter / "svp_mpv_fel_pair.h", source / directory / "svp_mpv_fel_pair.h")
shutil.copyfile(adapter / "svp_mpv_fel_vo.h", source / "video/out/svp_mpv_fel_vo.h")
shutil.copyfile(adapter / "svp_placebo_quality.h", source / "video/out/svp_placebo_quality.h")
for name in ("ambientsvp_quality.h", "ambientsvp_vulkan.h"):
    shutil.copyfile(adapter.parent / "include" / name, source / "video/out" / name)
shutil.copyfile(adapter.parent / "include/ambientsvp_quality.h", source / "video/filter/ambientsvp_quality.h")
def patch(path, edits, marker):
    p = source / path
    s = p.read_text()
    if marker in s:
        # HDR spatial staging legitimately inserts a cleanup call inside
        # the FEL cleanup sequence. Validate the composed patch as well.
        composed = s.replace('    svp_hdr_spatial_destroy(gpu, &fp->svp_hdr);\n', '') if path == 'video/out/vo_gpu_next.c' else s
        for _, new in edits:
            if new not in composed:
                raise SystemExit("Incomplete SVP FEL staging: " + path)
        return
    for old, new in edits:
        if s.count(old) != 1:
            raise SystemExit("SVP FEL anchor mismatch: " + path + " " + old[:80])
        s = s.replace(old, new)
    p.write_text(s)
patch("video/mp_image.h", [
    ("    struct mp_image *enhancement_layer;",
     "    struct mp_image *enhancement_layer;\n    struct AVBufferRef *ambient_svp_pair; // SVP FEL private source pair"),
], "SVP FEL private source pair")
patch("video/mp_image.c", [
    ("    mp_image_unrefp(&mpi->enhancement_layer);",
     "    mp_image_unrefp(&mpi->enhancement_layer);\n    av_buffer_unref(&mpi->ambient_svp_pair); // SVP FEL ownership"),
    ("    new->enhancement_layer = img->enhancement_layer\n        ? mp_image_new_ref(img->enhancement_layer) : NULL;",
     "    new->enhancement_layer = img->enhancement_layer\n        ? mp_image_new_ref(img->enhancement_layer) : NULL;\n    ref_buffer(&new->ambient_svp_pair);"),
    ("    new->enhancement_layer = NULL;\n    return new;",
     "    new->enhancement_layer = NULL;\n    new->ambient_svp_pair = NULL;\n    return new;"),
    ("    mp_image_setrefp(&dst->enhancement_layer, src->enhancement_layer);",
     "    mp_image_setrefp(&dst->enhancement_layer, src->enhancement_layer);\n    assign_bufref(&dst->ambient_svp_pair, src->ambient_svp_pair);"),
], "SVP FEL ownership")
patch("video/out/vo_gpu_next.c", [
    ("    struct hwdec_slot el_hwdec;",
     "    struct hwdec_slot el_hwdec;\n    struct svp_fel_vo_cache *svp_fel; // SVP FEL renderer cache"),
    ("    struct pl_frame el_frame;",
     "    struct pl_frame el_frame;\n    pl_tex svp_fel_output;"),
    ("static bool map_frame(pl_gpu gpu, pl_tex *tex, const struct pl_source_frame *src,",
     '#include "svp_mpv_fel_vo.h"\n\nstatic bool map_frame(pl_gpu gpu, pl_tex *tex, const struct pl_source_frame *src,'),
    ("    fp->hwdec = ra_hwdec_get(&p->hwdec_ctx, mpi->imgfmt);",
     "    if (mpi->ambient_svp_pair && svp_fel_vo_map(p, vo, mpi, frame))\n        return true;\n\n    fp->hwdec = ra_hwdec_get(&p->hwdec_ctx, mpi->imgfmt);"),
    ("    slot_release_owner(p, &p->hwdec, mpi);",
     "    svp_fel_vo_retire_output(p, fp->svp_fel_output);\n    pl_tex_destroy(gpu, &fp->svp_fel_output);\n    slot_release_owner(p, &p->hwdec, mpi);"),
    ("    // Drain any in-flight uploads.",
     "    svp_fel_vo_preflight_retirement(p); // SVP ownership before GPU teardown\n    // Drain any in-flight uploads."),
    ("    pl_queue_destroy(&p->queue); // destroy this first",
     "    pl_queue_destroy(&p->queue); // destroy this first\n    svp_fel_vo_destroy(p);"),
], "SVP FEL renderer cache")
