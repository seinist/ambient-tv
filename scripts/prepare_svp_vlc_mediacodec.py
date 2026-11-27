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
       "    AImageReader *air;\n    bool svp_surface; // SVP selected GPU-readable PRIVATE producer\n    bool svp_hdr10; // explicit Main10 PQ/HLG",
       "bool svp_surface; // SVP")
insert(utils, "    AImage *image;\n    int fence_fd;",
       "    AImage *image;\n    AHardwareBuffer *svp_buffer; // SVP compositor-owned RGB output\n"
       "    void *svp_lease;\n    void *svp_recycle;\n    atomic_uint svp_present_count;\n    atomic_int svp_consumer_fd;\n    int fence_fd;",
       "AHardwareBuffer *svp_buffer; // SVP")

decoder = "modules/codec/omxil/mediacodec.c"
shutil.copyfile(svp / "include/svp_sdr_profile.h", source / "modules/codec/omxil/svp_sdr_profile.h")
shutil.copyfile(svp / "include/svp_av1_config.h", source / "modules/codec/omxil/svp_av1_config.h")
insert("modules/codec/Makefile.am",
       "libmediacodec_plugin_la_SOURCES = codec/omxil/mediacodec.c codec/omxil/mediacodec.h",
       "libmediacodec_plugin_la_SOURCES = packetizer/av1_obu.c packetizer/av1_obu.h \\\n"
       "\tcodec/omxil/mediacodec.c codec/omxil/mediacodec.h",
       "libmediacodec_plugin_la_SOURCES = packetizer/av1_obu.c")
insert(decoder, '#include <vlc_common.h>', '#include <vlc_common.h>\n#include "svp_sdr_profile.h" // SVP profile allowlist',
       '"svp_sdr_profile.h" // SVP')
insert(decoder, '#include "mediacodec.h"', '#include "mediacodec.h"\n#include "svp_av1_config.h"\n#include "../../packetizer/av1_obu.h" // SVP AV1 parser',
       '"../../packetizer/av1_obu.h" // SVP AV1 parser')
insert(decoder, "static int ParseExtra(decoder_t *p_dec)",
       """/* SVP AV1: validate both av1C and actual sequence syntax. */
static bool SvpAv1ConfigSDR8(const es_format_t *fmt, SvpAv1Config *config)
{
    if (!svp_av1_config(fmt->p_extra, fmt->i_extra, config))
        return false;
    av1_OBU_sequence_header_t *seq = AV1_OBU_parse_sequence_header(
        config->sequence.data, config->sequence.size);
    if (!seq) return false;
    int profile, level, tier;
    AV1_get_profile_level(seq, &profile, &level, &tier);
    bool ok = profile == 0 && AV1_get_chroma(seq) == VLC_CODEC_I420;
    video_color_primaries_t prim;
    video_transfer_func_t transfer;
    video_color_space_t space;
    video_color_range_t range;
    if (ok && AV1_get_colorimetry(seq, &prim, &transfer, &space, &range)) {
        if (transfer != TRANSFER_FUNC_UNDEF &&
            transfer != TRANSFER_FUNC_BT709 && transfer != TRANSFER_FUNC_SRGB)
            ok = false;
        if (prim != COLOR_PRIMARIES_UNDEF && prim != COLOR_PRIMARIES_BT709 &&
            prim != COLOR_PRIMARIES_BT601_625 && prim != COLOR_PRIMARIES_BT601_525)
            ok = false;
    }
    const video_format_t *v = &fmt->video;
    ok = ok && (v->transfer == TRANSFER_FUNC_BT709 || v->transfer == TRANSFER_FUNC_SRGB) &&
        (v->primaries == COLOR_PRIMARIES_BT709 || v->primaries == COLOR_PRIMARIES_BT601_625 ||
         v->primaries == COLOR_PRIMARIES_BT601_525);
    AV1_release_sequence_header(seq);
    return ok;
}

static int SvpAv1_OnNewBlock(decoder_t *p_dec, block_t **pp_block)
{
    SvpAv1Config config;
    if (!svp_av1_config(p_dec->fmt_in->p_extra, p_dec->fmt_in->i_extra, &config) ||
        !svp_av1_same_sequence(&config, (*pp_block)->p_buffer, (*pp_block)->i_buffer))
        return VLC_EGENERIC; /* changed sequence: normal decoder fallback */
    return Video_OnNewBlock(p_dec, pp_block);
}

static int ParseExtra(decoder_t *p_dec)""",
       "/* SVP AV1: validate both")
insert(decoder,
       "    case VLC_CODEC_HEVC:\n        return ParseVideoExtraHEVC(p_dec, p_extra, i_extra);",
       "    case VLC_CODEC_HEVC:\n        return ParseVideoExtraHEVC(p_dec, p_extra, i_extra);\n"
       "    case VLC_CODEC_AV1: {\n"
       "        SvpAv1Config config;\n"
       "        if (!SvpAv1ConfigSDR8(p_dec->fmt_in, &config)) return VLC_EGENERIC;\n"
       "        p_sys->pf_on_new_block = SvpAv1_OnNewBlock;\n"
       "        return CSDDup(p_sys, config.csd, config.csd_size);\n"
       "    }",
       "p_sys->pf_on_new_block = SvpAv1_OnNewBlock")
insert(decoder, "        case VLC_CODEC_APV:\n            mime = \"video/avp\"; break;",
       "        case VLC_CODEC_AV1: {\n"
       "            SvpAv1Config config;\n"
       "            if (!var_InheritBool(p_dec, CFG_PREFIX \"svp-vulkan\") ||\n"
       "                !var_InheritBool(p_dec, CFG_PREFIX \"dr\") ||\n"
       "                !SvpAv1ConfigSDR8(p_dec->fmt_in, &config)) return VLC_EGENERIC;\n"
       "            i_profile = 0; mime = \"video/av01\"; break;\n"
       "        }\n"
       "        case VLC_CODEC_APV:\n            mime = \"video/avp\"; break;",
       'mime = "video/av01"')
insert(decoder, '    add_bool(CFG_PREFIX "dr", true,',
       '    add_bool(CFG_PREFIX "svp-vulkan", false, N_("Experimental GPU-resident SVP Surface"), NULL)\n'
       '    add_bool(CFG_PREFIX "dr", true,',
       'add_bool(CFG_PREFIX "svp-vulkan"')
insert(decoder, "            bool use_air;",
       "            bool use_air;\n            bool svp_sdr8; // SVP explicit 8-bit codec profile\n            bool svp_hdr10;",
       "bool svp_sdr8;")
insert(decoder, "        p_sys->video.use_air = false;",
       "        p_sys->video.use_air = false;\n"
       "        // Explicit elementary-stream profiles that guarantee 8-bit 4:2:0.\n"
       "        SvpSdrCodec svp_codec = SVP_CODEC_UNSUPPORTED;\n"
       "        switch (p_dec->fmt_in->i_codec) {\n"
       "        case VLC_CODEC_H264: svp_codec = SVP_CODEC_AVC; break;\n"
       "        case VLC_CODEC_HEVC: svp_codec = SVP_CODEC_HEVC; break;\n"
       "        case VLC_CODEC_VP9: svp_codec = SVP_CODEC_VP9; break;\n"
       "        default: break;\n"
       "        }\n"
       "        p_sys->video.svp_sdr8 = svp_sdr8_profile(svp_codec, i_profile) ||\n"
       "            p_dec->fmt_in->i_codec == VLC_CODEC_AV1; /* validated in MIME selection */\n"
       "        const video_format_t *svpfmt = &p_dec->fmt_in->video;\n"
       "        p_sys->video.svp_hdr10 = p_dec->fmt_in->i_codec == VLC_CODEC_HEVC && i_profile == 2 &&\n"
       "            svpfmt->primaries == COLOR_PRIMARIES_BT2020 && svpfmt->space == COLOR_SPACE_BT2020 &&\n"
       "            (svpfmt->transfer == TRANSFER_FUNC_SMPTE_ST2084 || svpfmt->transfer == TRANSFER_FUNC_HLG) &&\n"
       "            (svpfmt->color_range == COLOR_RANGE_FULL || svpfmt->color_range == COLOR_RANGE_LIMITED) &&\n"
       "            !svpfmt->dovi.profile && !svpfmt->dovi.rpu_present && !svpfmt->dovi.el_present;",
       "p_sys->video.svp_sdr8 =")
insert(decoder, "    if (need_gpu)\n        usage |= AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;",
       "    // SVP explicitly selects the same PRIVATE reader with GPU usage.\n"
       "    // No second reader/Surface, and no competing output release.\n"
       '    bool svp_surface = !need_gpu && (p_sys->video.svp_sdr8 || p_sys->video.svp_hdr10) && var_InheritBool(p_dec, CFG_PREFIX "svp-vulkan");\n'
       "    if (need_gpu || svp_surface)\n        usage |= AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;",
       "bool svp_surface = !need_gpu")
insert(decoder, "    avctx->air = reader;",
       "    avctx->air = reader;\n    avctx->svp_surface = svp_surface;\n    avctx->svp_hdr10 = svp_surface && p_sys->video.svp_hdr10;",
       "avctx->svp_surface = svp_surface;")
insert(decoder, "    avctx->air = NULL;",
       "    avctx->air = NULL;\n    avctx->svp_surface = false;\n    avctx->svp_hdr10 = false;",
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
       "    video_format_t dataspace_fmt = pic->format;\n"
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
