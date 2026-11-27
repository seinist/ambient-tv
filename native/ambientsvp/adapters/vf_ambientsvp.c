/* SPDX-License-Identifier: LGPL-2.1-or-later */
#include "config.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <libavutil/rational.h>
#include <libavutil/buffer.h>
#include <libavutil/version.h>
#include "common/common.h"
#include "common/msg.h"
#include "common/tags.h"
#include "filters/filter.h"
#include "filters/filter_internal.h"
#include "filters/user_filters.h"
#include "video/mp_image.h"
#include "video/mp_image_pool.h"
#include "video/img_format.h"
#include "ambientsvp_loader.h"
#include "svp_retry.h"
#include "svp_planes.h"
#if HAVE_VULKAN && LIBAVUTIL_VERSION_MAJOR == 61
#include <libplacebo/utils/libav.h>
#include "svp_mpv_vulkan.h"
#define SVP_MPV_RESIDENT 1
#else
#define SVP_MPV_RESIDENT 0
#endif

struct opts { double fps, source; int preset, backend, threads, artifacts, scene, algo, block, overlap, pel, search, hwresident; };
struct priv {
    struct opts *opts; SvpApi api; AmbientSvp *engine;
    struct mp_image *reference; uint8_t *buffer; size_t bytes;
    struct mp_image_pool *output_pool;
    struct mp_image_pool *download_pool;
    unsigned output_pool_count;
#if SVP_MPV_RESIDENT
    struct svp_vk_mpv *vk;
    bool vk_disabled;
#endif
    bool eof, bypass; int width, height;
    /* Why frames pass through unchanged (metadata "passthrough"); 0 while the engine runs.
     * Kept separate from the engine's bypass_reason so only a real overload reads as one. */
    int passthrough;
    /* Timing-gap cool-down; survives the internal engine reset, cleared on seek. */
    SvpRetry retry;
};
enum { PASS_NONE, PASS_FORMAT, PASS_UNAVAILABLE, PASS_TIMING, PASS_ERROR };

__attribute__((visibility("default"))) int mpv_ambient_svp_abi(void);
int mpv_ambient_svp_abi(void) { return AMBIENTSVP_ABI; }

static void reset(struct mp_filter *f) {
    struct priv *p = f->priv;
#if SVP_MPV_RESIDENT
    svp_vk_reset_history(p->vk);
#endif
    if (p->engine) p->api.destroy(p->engine);
    p->engine = NULL; mp_image_unrefp(&p->reference);
    free(p->buffer); p->buffer = NULL; p->bytes = 0;
    if (p->output_pool) mp_image_pool_clear(p->output_pool);
    if (p->download_pool) mp_image_pool_clear(p->download_pool);
    p->output_pool_count = 0;
    p->eof = p->bypass = false;
    p->passthrough = PASS_NONE;
}
/* A normal seek discards temporal state without rebuilding warm GPU
 * resources. A bypassed engine keeps the old explicit-seek retry behavior. */
static void seek_reset(struct mp_filter *f) {
    struct priv *p = f->priv;
#if SVP_MPV_RESIDENT
    svp_vk_reset_history(p->vk);
    p->vk_disabled = false;
#endif
    AmbientSvpStats stats = {0};
    if (p->engine && !p->bypass && p->api.enabled()
        && !p->api.stats(p->engine, &stats) && !stats.bypass_reason
        && !p->api.reset(p->engine)) {
        mp_image_unrefp(&p->reference);
        p->eof = false;
        p->passthrough = PASS_NONE;
    } else reset(f);
    svp_retry_clear(&p->retry);
}
static void destroy(struct mp_filter *f) {
    struct priv *p = f->priv; reset(f);
#if SVP_MPV_RESIDENT
    if (!svp_vk_destroy_state(p->vk)) {
        // Do not unload the library or free GPU resources still in flight.
        MP_WARN(f, "SVP resident GPU did not retire; retaining its resources until process teardown\\n");
        return;
    }
    p->vk = NULL;
#endif
    if (p->api.lib) dlclose(p->api.lib);
}
static bool eligible(struct mp_image *img) {
    return !(img->fields & MP_IMGFIELD_INTERLACED) && img->pts != MP_NOPTS_VALUE
        && img->w >= 32 && img->h >= 32 && img->w <= 3840 && img->h <= 2160
        && !(img->w % 2) && !(img->h % 2)
        && img->params.color.transfer != PL_COLOR_TRC_PQ && img->params.color.transfer != PL_COLOR_TRC_HLG
        && img->params.repr.sys != PL_COLOR_SYSTEM_DOLBYVISION && !img->enhancement_layer;
}
static bool supported(struct mp_image *img) {
    return (img->imgfmt == IMGFMT_420P || img->imgfmt == IMGFMT_NV12) && eligible(img);
}
/* Vulkan AVHWFramesContext exposes an actual transferable hardware image.
 * MPV's MediaCodec surface handle is NOT a hardware-buffer pointer and
 * cannot be downloaded by this helper. Leave it untouched. */
static struct mp_image *download_vulkan(struct priv *p, struct mp_image *img) {
    if (img->imgfmt != IMGFMT_VULKAN || !img->hwctx || !eligible(img))
        return NULL;
    int fmt = mp_image_hw_download_get_sw_format(img);
    if (fmt != IMGFMT_420P && fmt != IMGFMT_NV12)
        return NULL;
    struct mp_image *sw = mp_image_hw_download(img, p->download_pool);
    if (sw && (!supported(sw) || sw->w != img->w || sw->h != img->h))
        mp_image_unrefp(&sw);
    return sw;
}
static void pack(struct mp_image *img, uint8_t *dst) {
    int w = img->w, h = img->h;
    svp_copy_plane(dst, w, img->planes[0], img->stride[0], w, h);
    uint8_t *u = dst + w*h, *v = u + w*h/4;
    if (img->imgfmt == IMGFMT_420P) {
        svp_copy_plane(u, w/2, img->planes[1], img->stride[1], w/2, h/2);
        svp_copy_plane(v, w/2, img->planes[2], img->stride[2], w/2, h/2);
    } else for (int y = 0; y < h/2; y++) {
        svp_split_uv(u + y*w/2, v + y*w/2,
                     img->planes[1] + (ptrdiff_t)y*img->stride[1], w/2);
    }
}
static int fill_input(void *context, uint8_t *dst, size_t len) {
    struct mp_image *img = context;
    if (len != (size_t)img->w * img->h * 3 / 2) return -1;
    pack(img, dst);
    return 0;
}
/* MPV's pinned image pool retains backing allocations until the last
 * renderer reference is released. Cap retained images by both count and
 * byte budget; when all are busy, allocate a normal unpooled image. */
static struct mp_image *output_image(struct priv *p) {
    struct mp_image *out = mp_image_pool_get_no_alloc(p->output_pool, IMGFMT_420P, p->width, p->height);
    if (out) return out;
    out = mp_image_alloc(IMGFMT_420P, p->width, p->height);
    if (!out) return NULL;
    const size_t budget = 32u * 1024u * 1024u;
    if (p->output_pool_count < 6 && out->bufs[0] && out->bufs[0]->size > 0
        && out->bufs[0]->size <= budget / (p->output_pool_count + 1)) {
        mp_image_pool_add(p->output_pool, out);
        p->output_pool_count++;
        return mp_image_pool_get_no_alloc(p->output_pool, IMGFMT_420P, p->width, p->height);
    }
    return out;
}
/* Only write the Rust contiguous slice into a proven single AVBufferRef.
 * Never infer contiguity from adjacent plane addresses alone. */
static bool packed_output(struct mp_image *out, int w, int h, size_t bytes) {
    if (!out->bufs[0] || !mp_image_is_writeable(out)
        || out->stride[0] != w || out->stride[1] != w/2 || out->stride[2] != w/2)
        return false;
    uintptr_t base = (uintptr_t)out->bufs[0]->data, y = (uintptr_t)out->planes[0];
    size_t luma = (size_t)w*h;
    if (y < base || y-base > out->bufs[0]->size || bytes > out->bufs[0]->size-(y-base))
        return false;
    return (uintptr_t)out->planes[1] == y+luma
        && (uintptr_t)out->planes[2] == y+luma+luma/4;
}
static void visit_output(void *context, const uint8_t *data, size_t len) {
    struct mp_image *out = context;
    if (len != (size_t)out->w * out->h * 3 / 2) return;
    for (int plane = 0, offset = 0; plane < 3; plane++) {
        int w = out->w >> (plane != 0), h = out->h >> (plane != 0);
        svp_copy_plane(out->planes[plane], out->stride[plane], data + offset, w, w, h);
        offset += w*h;
    }
}
static bool receive(struct mp_filter *f) {
    struct priv *p = f->priv;
    if (!p->engine || !p->reference) return false;
    if (p->api.ready) {
        int ready = p->api.ready(p->engine, p->eof);
        if (ready <= 0) {
            if (ready < 0) p->api.bypass(p->engine, 3);
            return false;
        }
    }
    struct mp_image *out = output_image(p);
    if (!out) { p->api.bypass(p->engine, 3); return false; }
    bool direct = packed_output(out, p->width, p->height, p->bytes);
    int64_t pts;
    bool visit = !direct && p->api.receive_visit;
    int r = visit ? p->api.receive_visit(p->engine, out, visit_output, &pts, p->eof)
                  : p->api.receive(p->engine, direct ? out->planes[0] : p->buffer, p->bytes, &pts, p->eof);
    if (r <= 0) {
        talloc_free(out);
        if (r < 0) p->api.bypass(p->engine, 3);
        return false;
    }
    mp_image_copy_attributes(out, p->reference);
    if (!direct && !visit) visit_output(out, p->buffer, p->bytes);
    out->pts = pts / 1000000.0;
    AmbientSvpStats stats = {0}; p->api.stats(p->engine, &stats);
    out->pkt_duration = 1.0 / (stats.bypass_reason ? p->opts->source : p->opts->fps);
    mp_pin_in_write(f->ppins[1], MAKE_FRAME(MP_FRAME_VIDEO, out));
    return true;
}
static void process(struct mp_filter *f) {
    struct priv *p = f->priv;
    if (!mp_pin_in_needs_data(f->ppins[1])) return;
#if SVP_MPV_RESIDENT
    if (p->vk) {
        svp_vk_reap(p->vk, false);
        if (!p->api.enabled()) {
            struct mp_image *previous = svp_vk_take_previous(p->vk);
            svp_vk_reset_history(p->vk);
            if (previous) { mp_pin_in_write(f->ppins[1], MAKE_FRAME(MP_FRAME_VIDEO, previous)); return; }
        }
        struct mp_image *out = svp_vk_take_output(p->vk);
        if (out) { mp_pin_in_write(f->ppins[1], MAKE_FRAME(MP_FRAME_VIDEO, out)); return; }
    }
#endif
    if (receive(f)) return;
    if (p->eof) { mp_pin_in_write(f->ppins[1], MP_EOF_FRAME); p->eof = false; return; }
    struct mp_frame frame = mp_pin_out_read(f->ppins[0]);
    if (!frame.type) return;
#if SVP_MPV_RESIDENT
    if (frame.type == MP_FRAME_EOF && p->vk && p->vk->previous) {
        svp_vk_eof(p->vk);
        p->eof = true; mp_filter_internal_mark_progress(f); return;
    }
#endif
    if (frame.type == MP_FRAME_EOF && p->engine) {
        p->eof = true; mp_filter_internal_mark_progress(f); return;
    }
    if (frame.type != MP_FRAME_VIDEO) { mp_pin_in_write(f->ppins[1], frame); return; }
    struct mp_image *img = frame.data;
#if SVP_MPV_RESIDENT
    // Explicitly opt-in until the independent resident search has device
    // quality/performance validation. Never route MediaCodec surface handles
    // here; only actual FFmpeg Vulkan frames with supported plane views.
    if (p->opts->hwresident && !p->vk_disabled && !p->bypass
        && p->api.lib && p->api.enabled() && svp_vk_eligible(img)) {
        if (p->engine) reset(f);
        if (p->vk && !svp_vk_matches(p->vk, img) && !p->vk->previous
            && !svp_vk_reap(p->vk, false)) {
            if (svp_vk_destroy_state(p->vk)) p->vk = NULL;
        }
        if (!p->vk) {
            p->vk = svp_vk_new(p->api.lib, img, p->opts->source, p->opts->fps);
            if (p->vk) MP_INFO(f, "SVP native Vulkan resident path %dx%d %.6f -> %.6f\n",
                               img->w, img->h, p->opts->source, p->opts->fps);
        }
        if (p->vk && svp_vk_feed(p->vk, img)) {
            p->passthrough = PASS_NONE;
            mp_filter_internal_mark_progress(f); return;
        }
        p->vk_disabled = true;
        MP_WARN(f, "SVP native Vulkan path unavailable; using existing transfer/passthrough\n");
    }
    if (p->vk && p->vk->previous) {
        // Preserve the last source frame at a transition/failure. Put the
        // new frame back so the existing path can handle it next time.
        struct mp_image *previous = svp_vk_take_previous(p->vk);
        p->vk_disabled = true;
        mp_pin_out_unread(f->ppins[0], frame);
        mp_pin_in_write(f->ppins[1], MAKE_FRAME(MP_FRAME_VIDEO, previous));
        return;
    }
#endif
    if (p->engine && (img->w != p->width || img->h != p->height || !eligible(img))) reset(f);
    if (!p->bypass && p->passthrough == PASS_TIMING && svp_retry_waiting(&p->retry)) {
        mp_pin_in_write(f->ppins[1], frame); return;
    }
    if (!p->bypass && p->api.lib && p->api.enabled() && img->imgfmt == IMGFMT_VULKAN) {
        struct mp_image *sw = download_vulkan(p, img);
        if (sw) {
            // The download owns its software allocation and attributes.
            // Retire the hardware reference only after transfer succeeds.
            mp_frame_unref(&frame);
            frame = MAKE_FRAME(MP_FRAME_VIDEO, sw);
            img = sw;
        }
    }
    if (p->engine && !supported(img)) reset(f);
    if (p->bypass || !supported(img) || !p->api.lib || !p->api.enabled()) {
        int reason = p->passthrough;
        if (!p->bypass) reason = !p->api.lib ? PASS_UNAVAILABLE : !supported(img) ? PASS_FORMAT : PASS_NONE;
        if (p->engine) reset(f);
        p->passthrough = reason;
        mp_pin_in_write(f->ppins[1], frame); return;
    }
    if (!p->engine) {
        AVRational src = av_d2q(p->opts->source, 1001000), dst = av_d2q(p->opts->fps, 1001000);
        AmbientSvpConfig cfg = {AMBIENTSVP_ABI, img->w, img->h, src.num, src.den, dst.num, dst.den,
            p->opts->preset, p->opts->backend, p->opts->threads, p->opts->artifacts, p->opts->scene, p->opts->algo, p->opts->block, p->opts->overlap, p->opts->pel, p->opts->search};
        p->engine = p->api.create(&cfg);
        p->width = img->w; p->height = img->h; p->bytes = (size_t)img->w * img->h * 3 / 2;
        const bool legacy_buffer = !p->api.push_fill || !p->api.receive_visit;
        p->buffer = p->engine && legacy_buffer ? malloc(p->bytes) : NULL;
        if (!p->engine || (legacy_buffer && !p->buffer)) {
            reset(f); p->bypass = true; p->passthrough = PASS_UNAVAILABLE;
            MP_WARN(f, "SVP unavailable for this format/device; passing through\n");
            mp_pin_in_write(f->ppins[1], frame); return;
        }
        p->passthrough = PASS_NONE;
        MP_INFO(f, "Open-SVPFlow %dx%d %.6f -> %.6f\n", img->w, img->h, p->opts->source, p->opts->fps);
    }
    int64_t pts = llround(img->pts * 1000000.0);
    int r;
    if (p->api.push_fill) r = p->api.push_fill(p->engine, pts, img, fill_input);
    else {
        pack(img, p->buffer);
        r = p->api.push(p->engine, p->buffer, p->bytes, pts);
    }
    if (r == -3) {
        reset(f); svp_retry_gap(&p->retry); p->passthrough = PASS_TIMING;
        MP_WARN(f, "SVP timing gap #%d; passing through for %d frames, then retrying\n", p->retry.gaps, p->retry.cooldown);
        mp_pin_in_write(f->ppins[1], frame); return;
    }
    if (r != 0) {
        reset(f); p->bypass = true; p->passthrough = PASS_ERROR;
        MP_WARN(f, "SVP input error %d; passing through until reset\n", r);
        mp_pin_in_write(f->ppins[1], frame); return;
    }
    svp_retry_frame_ok(&p->retry);
    mp_image_unrefp(&p->reference); p->reference = img;
    mp_filter_internal_mark_progress(f);
}
static bool command(struct mp_filter *f, struct mp_filter_command *cmd) {
    struct priv *p = f->priv;
    if (cmd->type != MP_FILTER_COMMAND_GET_META) return false;
    struct mp_tags *tags = talloc_zero(NULL, struct mp_tags);
    AmbientSvpStats s = {0}; if (p->engine) p->api.stats(p->engine, &s);
    bool resident = false;
#if SVP_MPV_RESIDENT
    resident = p->vk && p->vk->previous && !p->vk_disabled;
#endif
    mp_tags_set_str(tags, "backend", resident || s.backend == 2 ? "vulkan" : s.backend == 1 ? "cpu" : "off");
    mp_tags_set_str(tags, "transport", resident ? "hardware-resident" : "host-i420");
#if SVP_MPV_RESIDENT
    if (resident) {
        s.output_frames = p->vk->output_frames;
        // No GPU readback to count genuinely novel images. Generated
        // output ticks are exposed separately from novelty statistics.
        mp_tags_set_str(tags, "gpu-generated-frames", talloc_asprintf(tags, "%llu",
            (unsigned long long)p->vk->generated_frames));
    }
#endif
    /* bypass: engine reason only (1 overload, 2 gate, 3 engine error); passthrough: adapter reason. */
    mp_tags_set_str(tags, "bypass", talloc_asprintf(tags, "%u", p->engine ? s.bypass_reason : 0u));
    mp_tags_set_str(tags, "passthrough", talloc_asprintf(tags, "%d", p->passthrough));
    mp_tags_set_str(tags, "interpolated-frames", talloc_asprintf(tags, "%llu", (unsigned long long)s.interpolated_frames));
    mp_tags_set_str(tags, "output-frames", talloc_asprintf(tags, "%llu", (unsigned long long)s.output_frames));
    mp_tags_set_str(tags, "analysis-us", talloc_asprintf(tags, "%llu", (unsigned long long)s.analysis_us));
    mp_tags_set_str(tags, "synthesis-us", talloc_asprintf(tags, "%llu", (unsigned long long)s.synthesis_us));
    *(struct mp_tags **)cmd->res = tags; return true;
}
static const struct mp_filter_info info = {.name = "ambientsvp", .priv_size = sizeof(struct priv),
    .process = process, .reset = seek_reset, .destroy = destroy, .command = command};
static struct mp_filter *create(struct mp_filter *parent, void *options) {
    struct mp_filter *f = mp_filter_create(parent, &info);
    if (!f) { talloc_free(options); return NULL; }
    struct priv *p = f->priv; p->opts = talloc_steal(p, options); svp_load(&p->api);
    p->output_pool = mp_image_pool_new(p);
    p->download_pool = mp_image_pool_new(p);
    mp_filter_add_pin(f, MP_PIN_IN, "in"); mp_filter_add_pin(f, MP_PIN_OUT, "out"); return f;
}
#define OPT_BASE_STRUCT struct opts
static const m_option_t options[] = {
    {"fps", OPT_DOUBLE(fps), M_RANGE(5, 120)}, {"source", OPT_DOUBLE(source), M_RANGE(5, 120)},
    {"preset", OPT_INT(preset), M_RANGE(0, 4)}, {"backend", OPT_INT(backend), M_RANGE(0, 2)},
    {"threads", OPT_INT(threads), M_RANGE(0, 8)},
    {"artifacts", OPT_INT(artifacts), M_RANGE(0, 3)},
    {"scene", OPT_INT(scene), M_RANGE(0, 3)},
    {"algo", OPT_INT(algo), M_RANGE(0, 23)},
    {"block", OPT_INT(block), M_RANGE(0, 32)},
    {"overlap", OPT_INT(overlap), M_RANGE(0, 4)},
    {"pel", OPT_INT(pel), M_RANGE(0, 4)},
    {"search", OPT_INT(search), M_RANGE(0, 3)},
    {"hwresident", OPT_INT(hwresident), M_RANGE(0, 1)},
    {0}
};
const struct mp_user_filter_entry vf_ambientsvp = {
    .desc = {.name = "ambientsvp", .description = "Open-SVPFlow motion-compensated interpolation",
        .priv_size = sizeof(struct opts), .priv_defaults = &(const struct opts){.fps = 60, .source = 24}, .options = options},
    .create = create,
};
