#!/usr/bin/env python3
"""Wire Android opaque SVP pictures into the pinned VLC decoder and ASC output."""
from pathlib import Path
import sys
import shutil

source = Path(sys.argv[1])
svp = Path(__file__).resolve().parents[1] / "native/ambientsvp"

def insert(path, old, new, marker):
    p = source / path
    text = p.read_text()
    if marker in text:
        if new not in text:
            raise RuntimeError(f"stale SVP integration in {path}")
        return
    if text.count(old) != 1:
        raise RuntimeError(f"unexpected VLC source for {path}: {old[:80]!r}")
    p.write_text(text.replace(old, new, 1))

shutil.copyfile(svp / "adapters/svp_vlc_android.h", source / "modules/video_filter/svp_vlc_android.h")
shutil.copyfile(svp / "include/ambientsvp_android_stream.h", source / "modules/video_filter/ambientsvp_android_stream.h")

utils = "modules/video_output/android/utils.h"
insert(utils, "#include <assert.h>", "#include <assert.h>\n#include <stdatomic.h> // SVP consumer fence state",
       "stdatomic.h> // SVP")
insert(utils, "    AImageReader *air;",
       "    AImageReader *air;\n    bool svp_surface; // SVP selected GPU-readable PRIVATE producer",
       "bool svp_surface; // SVP")
insert(utils, "    AImage *image;\n    int fence_fd;",
       "    AImage *image;\n    AHardwareBuffer *svp_buffer; // SVP compositor-owned RGB output\n"
       "    void *svp_lease;\n    void *svp_recycle;\n    atomic_uint svp_present_count;\n    atomic_int svp_consumer_fd;\n    int fence_fd;",
       "AHardwareBuffer *svp_buffer; // SVP")

decoder = "modules/codec/omxil/mediacodec.c"
insert(decoder, '    add_bool(CFG_PREFIX "dr", true,',
       '    add_bool(CFG_PREFIX "svp-vulkan", false, N_("Experimental GPU-resident SVP Surface"), NULL)\n'
       '    add_bool(CFG_PREFIX "dr", true,',
       'add_bool(CFG_PREFIX "svp-vulkan"')
insert(decoder, "            bool use_air;",
       "            bool use_air;\n            bool svp_sdr8; // SVP explicit 8-bit codec profile",
       "bool svp_sdr8;")
insert(decoder, "        p_sys->video.use_air = false;",
       "        p_sys->video.use_air = false;\n"
       "        // Restrict initial opt-in to verified 8-bit AVC profiles.\n"
       "        p_sys->video.svp_sdr8 = p_dec->fmt_in->i_codec == VLC_CODEC_H264 &&\n"
       "            (i_profile == 66 || i_profile == 77 || i_profile == 100);",
       "p_sys->video.svp_sdr8 =")
insert(decoder, "    if (need_gpu)\n        usage |= AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;",
       "    // SVP explicitly selects the same PRIVATE reader with GPU usage.\n"
       "    // No second reader/Surface, and no competing output release.\n"
       '    bool svp_surface = !need_gpu && p_sys->video.svp_sdr8 && var_InheritBool(p_dec, CFG_PREFIX "svp-vulkan");\n'
       "    if (need_gpu || svp_surface)\n        usage |= AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;",
       "bool svp_surface = !need_gpu")
insert(decoder, "    avctx->air = reader;",
       "    avctx->air = reader;\n    avctx->svp_surface = svp_surface;",
       "avctx->svp_surface = svp_surface;")
insert(decoder, "    avctx->air = NULL;",
       "    avctx->air = NULL;\n    avctx->svp_surface = false;",
       "avctx->svp_surface = false;")
insert(decoder, "    apctx->image = image;\n    apctx->fence_fd = fence_fd;",
       "    apctx->image = image;\n    apctx->svp_buffer = NULL; // SVP source picture\n    apctx->fence_fd = fence_fd;",
       "apctx->svp_buffer = NULL; // SVP")
insert(decoder,
       "    if (apctx->fence_fd >= 0)\n        close(apctx->fence_fd);\n\n"
       "    avctx->air_api->AImage.deleteAsync(apctx->image, apctx->read_fence_fd);",
       "    // SVP source acquire may now be a GPU handback fence. Do not\n"
       "    // discard it when the source was never presented.\n"
       "    if (apctx->read_fence_fd < 0) {\n"
       "        apctx->read_fence_fd = apctx->fence_fd;\n"
       "        apctx->fence_fd = -1;\n"
       "    }\n"
       "    if (apctx->fence_fd >= 0)\n        close(apctx->fence_fd);\n"
       "    avctx->air_api->AImage.deleteAsync(apctx->image, apctx->read_fence_fd);",
       "SVP source acquire may now")
insert(decoder,
       "    if (fence_fd > 0)\n        close(fence_fd);\n    avctx->air_api->AImage.deleteAsync(image, -1);",
       "    avctx->air_api->AImage.deleteAsync(image, fence_fd); // SVP preserve producer fence on error",
       "SVP preserve producer fence on error")
display = "modules/video_output/android/display.c"
insert(display, "    apctx->sc = sys->asc.sc;",
       "    // SVP repeated prepares, including failures, conservatively disqualify reuse.\n"
       "    if (apctx->svp_buffer) {\n"
       "        unsigned expected = 0;\n"
       "        if (!atomic_compare_exchange_strong(&apctx->svp_present_count, &expected, 1))\n"
       "            atomic_store(&apctx->svp_present_count, 2);\n"
       "    }\n"
       "    apctx->sc = sys->asc.sc;",
       "SVP repeated prepares")
insert(display,
       "    if (release_fd >= 0)\n        android_picture_ctx_set_read_fence(apctx, release_fd);",
       "    if (apctx->svp_buffer) {\n"
       "        // SVP only recycles after one presentation with an explicit positive release fd.\n"
       "        int expected = -1;\n"
       "        if (release_fd >= 0 && (atomic_load(&apctx->svp_present_count) != 1 ||\n"
       "            !atomic_compare_exchange_strong(&apctx->svp_consumer_fd, &expected, release_fd)))\n"
       "            close(release_fd);\n"
       "    } else if (release_fd >= 0)\n"
       "        android_picture_ctx_set_read_fence(apctx, release_fd);",
       "SVP only recycles after")
insert(display, "    int32_t status = air_api->AImage.getHardwareBuffer(apctx->image, &buffer);",
       "    // SVP output already is a compositor-compatible RGB AHB.\n"
       "    int32_t status = 0;\n"
       "    if (apctx->svp_buffer) buffer = apctx->svp_buffer;\n"
       "    else status = air_api->AImage.getHardwareBuffer(apctx->image, &buffer);",
       "SVP output already is")
# Duplicate fence BEFORE handing previous-picture callback ownership to the
# transaction; reject failed dup rather than silently treating it as ready.
insert(display,
       "    if (sys->asc.previous_picture != NULL)\n    {\n"
       "        asc_api->ASurfaceTransaction.setOnComplete(txn, sys->asc.previous_picture,",
       "    int fence_fd = android_picture_ctx_get_fence_fd(apctx);\n"
       "    if (apctx->fence_fd >= 0 && fence_fd < 0) {\n"
       "        asc_api->ASurfaceTransaction.delete(txn);\n"
       "        return; // SVP never present an unsynchronized buffer\n"
       "    }\n"
       "    if (sys->asc.previous_picture != NULL)\n    {\n"
       "        asc_api->ASurfaceTransaction.setOnComplete(txn, sys->asc.previous_picture,",
       "SVP never present an unsynchronized buffer")
insert(display,
       "    int fence_fd = android_picture_ctx_get_fence_fd(apctx);\n\n"
       "    asc_api->ASurfaceTransaction.setBuffer(txn, sys->asc.sc, buffer, fence_fd);",
       "    // SVP generated RGB is full-range; restore source dataspace on fallback.\n"
       "    video_format_t dataspace_fmt = *vd->source;\n"
       "    if (apctx->svp_buffer) dataspace_fmt.color_range = COLOR_RANGE_FULL;\n"
       "    int32_t dataspace = video_format_to_adataspace(&dataspace_fmt);\n"
       "    if (dataspace != ADATASPACE_UNKNOWN)\n"
       "        asc_api->ASurfaceTransaction.setBufferDataSpace(txn, sys->asc.sc, dataspace);\n"
       "    asc_api->ASurfaceTransaction.setBuffer(txn, sys->asc.sc, buffer, fence_fd);",
       "SVP generated RGB is full-range")
# This path is designed for ASC. Never pass generated RGB to the existing
# GLES YUV external sampler with the old format/color contract.
insert("modules/video_output/opengl/interop_aimage.c",
       "    bool has_aimage = avctx->air != NULL && avctx->air_api != NULL;",
       "    if (avctx->svp_surface) return VLC_EGENERIC; // SVP requires SurfaceControl\n"
       "    bool has_aimage = avctx->air != NULL && avctx->air_api != NULL;",
       "SVP requires SurfaceControl")
insert("modules/video_filter/Makefile.am",
       "libambientsvp_plugin_la_LIBADD = $(LIBDL)",
       "libambientsvp_plugin_la_LIBADD = $(LIBDL)\n"
       "if HAVE_ANDROID\nlibambientsvp_plugin_la_LIBADD += -landroid\nendif # SVP Android native buffer refs",
       "SVP Android native buffer refs")
