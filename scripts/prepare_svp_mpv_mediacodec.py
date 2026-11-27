#!/usr/bin/env python3
"""Idempotently integrate the experimental Android Vulkan hwdec into pinned MPV."""
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
        raise RuntimeError(f"unexpected MPV source for {path}: {old[:80]!r}")
    p.write_text(text.replace(old, new, 1))

for name in ("svp_mpv_mediacodec.h",):
    shutil.copyfile(svp / "adapters" / name, source / "video/out/hwdec" / name)
for name in ("ambientsvp_android_vulkan.h", "ambientsvp_vulkan.h"):
    shutil.copyfile(svp / "include" / name, source / "video/out/hwdec" / name)

insert("video/out/gpu/hwdec.h",
       "    enum AVHWDeviceType device_type;",
       "    enum AVHWDeviceType device_type;\n    bool explicit_only; // SVP: never automatically choose experimental interop",
       "bool explicit_only; // SVP")
insert("video/out/gpu/hwdec.c",
       "extern const struct ra_hwdec_driver ra_hwdec_vulkan;",
       "extern const struct ra_hwdec_driver ra_hwdec_vulkan;\n"
       "#if HAVE_VULKAN && HAVE_ANDROID_MEDIA_NDK && LIBAVUTIL_VERSION_MAJOR == 61\n"
       "extern const struct ra_hwdec_driver ra_hwdec_mediacodec_svp_vulkan;\n#endif",
       "extern const struct ra_hwdec_driver ra_hwdec_mediacodec_svp_vulkan;")
insert("video/out/gpu/hwdec.c",
       '#include "config.h"',
       '#include "config.h"\n#include <libavutil/version.h> // SVP interop version guard',
       "SVP interop version guard")
insert("video/out/gpu/hwdec.c",
       "#if HAVE_VULKAN\n    &ra_hwdec_vulkan,",
       "#if HAVE_VULKAN && HAVE_ANDROID_MEDIA_NDK && LIBAVUTIL_VERSION_MAJOR == 61\n"
       "    &ra_hwdec_mediacodec_svp_vulkan,\n#endif\n#if HAVE_VULKAN\n    &ra_hwdec_vulkan,",
       "    &ra_hwdec_mediacodec_svp_vulkan,")
insert("video/out/gpu/hwdec.c",
       "            load_add_hwdec(ctx, devs, ra_hwdec_drivers[n], true);",
       "            if (!ra_hwdec_drivers[n]->explicit_only) // SVP explicit all guard\n"
       "                load_add_hwdec(ctx, devs, ra_hwdec_drivers[n], true);",
       "SVP explicit all guard")
insert("video/out/gpu/hwdec.c",
       "        const struct ra_hwdec_driver *drv = ra_hwdec_drivers[i];\n        for (int j = 0; drv->imgfmts[j]; j++) {",
       "        const struct ra_hwdec_driver *drv = ra_hwdec_drivers[i];\n"
       "        if (drv->explicit_only) continue; // SVP explicit lazy guard\n"
       "        for (int j = 0; drv->imgfmts[j]; j++) {",
       "SVP explicit lazy guard")
p = source / "video/out/hwdec/hwdec_vulkan.c"
suffix = '\n#if HAVE_ANDROID_MEDIA_NDK && LIBAVUTIL_VERSION_MAJOR == 61\n#include "svp_mpv_mediacodec.h"\n#endif\n'
if suffix not in p.read_text():
    p.write_text(p.read_text() + suffix)
insert("video/decode/vd_lavc.c", "#include <float.h>",
       '#include "config.h" // SVP decoder guard\n#include <libavutil/version.h>\n#include <float.h>',
       "SVP decoder guard")
insert("video/decode/vd_lavc.c",
       "static int decode_frame(struct mp_filter *vd)",
       "#if HAVE_VULKAN && HAVE_ANDROID_MEDIA_NDK && LIBAVUTIL_VERSION_MAJOR == 61\n"
       "int mp_svp_mediacodec_convert(AVBufferRef *,AVCodecContext *,AVFrame *,AVRational);\n"
       "#endif\nstatic int decode_frame(struct mp_filter *vd)",
       "int mp_svp_mediacodec_convert(")
insert("video/decode/vd_lavc.c",
       "    struct mp_image *mpi = mp_image_from_av_frame(ctx->pic);",
       "#if HAVE_VULKAN && HAVE_ANDROID_MEDIA_NDK && LIBAVUTIL_VERSION_MAJOR == 61\n"
       "    if (mp_svp_mediacodec_convert(ctx->hwdec_dev,avctx,ctx->pic,ctx->codec_timebase)<0) {\n"
       '        MP_WARN(vd, "SVP MediaCodec Vulkan import failed; falling back to another decoder\\n");\n'
       "        av_frame_unref(ctx->pic);\n"
       "        ctx->hwdec_failed = true;\n"
       "        return AVERROR_EXTERNAL;\n"
       "    }\n#endif\n"
       "    struct mp_image *mpi = mp_image_from_av_frame(ctx->pic);",
       "SVP MediaCodec Vulkan import failed")
insert("video/out/vulkan/context.c",
       "    const char *opt_extensions[] = {",
       "    const char *opt_extensions[] = {\n#ifdef __ANDROID__\n"
       '        "VK_ANDROID_external_memory_android_hardware_buffer", // SVP external memory\n'
       '        "VK_EXT_queue_family_foreign",\n'
       '        "VK_KHR_external_semaphore_fd",\n#endif',
       "SVP external memory")
insert("video/out/vulkan/context.c",
       "        .uniformAndStorageBuffer16BitAccess = true,",
       "        .uniformAndStorageBuffer16BitAccess = true,\n#ifdef __ANDROID__\n"
       "        .samplerYcbcrConversion = true, // SVP optional Android sampling\n#endif",
       "SVP optional Android sampling")
insert("video/out/vulkan/context.c",
       "            .shaderInt16 = true,",
       "            .shaderInt16 = true,\n#ifdef __ANDROID__\n"
       "            .shaderStorageImageExtendedFormats = true, // SVP optional raw YUV storage\n#endif",
       "SVP optional raw YUV storage")
