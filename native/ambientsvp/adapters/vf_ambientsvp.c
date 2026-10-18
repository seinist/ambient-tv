/* SPDX-License-Identifier: LGPL-2.1-or-later */
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <libavutil/rational.h>
#include "common/common.h"
#include "common/msg.h"
#include "common/tags.h"
#include "filters/filter.h"
#include "filters/filter_internal.h"
#include "filters/user_filters.h"
#include "video/mp_image.h"
#include "video/img_format.h"
#include "ambientsvp_loader.h"

struct opts { double fps, source; int preset, backend, threads, artifacts, scene, algo, block, overlap, pel, search; };
struct priv {
    struct opts *opts; SvpApi api; AmbientSvp *engine;
    struct mp_image *reference; uint8_t *buffer; size_t bytes;
    bool eof, bypass; int width, height;
    /* Why frames pass through unchanged (metadata "passthrough"); 0 while the engine runs.
     * Kept separate from the engine's bypass_reason so only a real overload reads as one. */
    int passthrough;
};
enum { PASS_NONE, PASS_FORMAT, PASS_UNAVAILABLE, PASS_TIMING, PASS_ERROR };

__attribute__((visibility("default"))) int mpv_ambient_svp_abi(void);
int mpv_ambient_svp_abi(void) { return AMBIENTSVP_ABI; }

static void reset(struct mp_filter *f) {
    struct priv *p = f->priv;
    if (p->engine) p->api.destroy(p->engine);
    p->engine = NULL; mp_image_unrefp(&p->reference);
    free(p->buffer); p->buffer = NULL; p->bytes = 0;
    p->eof = p->bypass = false;
    p->passthrough = PASS_NONE;
}
static void destroy(struct mp_filter *f) {
    struct priv *p = f->priv; reset(f); if (p->api.lib) dlclose(p->api.lib);
}
static bool supported(struct mp_image *img) {
    return (img->imgfmt == IMGFMT_420P || img->imgfmt == IMGFMT_NV12)
        && !(img->fields & MP_IMGFIELD_INTERLACED) && img->pts != MP_NOPTS_VALUE
        && img->w >= 32 && img->h >= 32 && !(img->w % 2) && !(img->h % 2)
        && img->params.color.transfer != PL_COLOR_TRC_PQ && img->params.color.transfer != PL_COLOR_TRC_HLG
        && img->params.repr.sys != PL_COLOR_SYSTEM_DOLBYVISION && !img->enhancement_layer;
}
static void pack(struct mp_image *img, uint8_t *dst) {
    int w = img->w, h = img->h;
    for (int y = 0; y < h; y++) memcpy(dst + y*w, img->planes[0] + y*img->stride[0], w);
    for (int plane = 1; plane < 3; plane++) for (int y = 0; y < h/2; y++) {
        uint8_t *d = dst + w*h + (plane-1)*w*h/4 + y*w/2;
        if (img->imgfmt == IMGFMT_420P) memcpy(d, img->planes[plane] + y*img->stride[plane], w/2);
        else for (int x = 0; x < w/2; x++) d[x] = img->planes[1][y*img->stride[1] + 2*x + plane-1];
    }
}
static bool receive(struct mp_filter *f) {
    struct priv *p = f->priv;
    if (!p->engine) return false;
    int64_t pts;
    int r = p->api.receive(p->engine, p->buffer, p->bytes, &pts, p->eof);
    if (r < 0) { p->api.bypass(p->engine, 3); return false; }
    if (r == 0) return false;
    struct mp_image *out = mp_image_alloc(IMGFMT_420P, p->width, p->height);
    if (!out) { p->api.bypass(p->engine, 3); return false; }
    mp_image_copy_attributes(out, p->reference);
    for (int plane = 0, offset = 0; plane < 3; plane++) {
        int w = p->width >> (plane != 0), h = p->height >> (plane != 0);
        for (int y = 0; y < h; y++) memcpy(out->planes[plane] + y*out->stride[plane], p->buffer + offset + y*w, w);
        offset += w*h;
    }
    out->pts = pts / 1000000.0;
    AmbientSvpStats stats = {0}; p->api.stats(p->engine, &stats);
    out->pkt_duration = 1.0 / (stats.bypass_reason ? p->opts->source : p->opts->fps);
    mp_pin_in_write(f->ppins[1], MAKE_FRAME(MP_FRAME_VIDEO, out));
    return true;
}
static void process(struct mp_filter *f) {
    struct priv *p = f->priv;
    if (!mp_pin_in_needs_data(f->ppins[1])) return;
    if (receive(f)) return;
    if (p->eof) { mp_pin_in_write(f->ppins[1], MP_EOF_FRAME); p->eof = false; return; }
    struct mp_frame frame = mp_pin_out_read(f->ppins[0]);
    if (!frame.type) return;
    if (frame.type == MP_FRAME_EOF && p->engine) {
        p->eof = true; mp_filter_internal_mark_progress(f); return;
    }
    if (frame.type != MP_FRAME_VIDEO) { mp_pin_in_write(f->ppins[1], frame); return; }
    struct mp_image *img = frame.data;
    if (p->engine && (img->w != p->width || img->h != p->height || !supported(img))) reset(f);
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
        p->buffer = p->engine ? malloc(p->bytes) : NULL;
        if (!p->engine || !p->buffer) {
            reset(f); p->bypass = true; p->passthrough = PASS_UNAVAILABLE;
            MP_WARN(f, "SVP unavailable for this format/device; passing through\n");
            mp_pin_in_write(f->ppins[1], frame); return;
        }
        p->passthrough = PASS_NONE;
        MP_INFO(f, "Open-SVPFlow %dx%d %.6f -> %.6f\n", img->w, img->h, p->opts->source, p->opts->fps);
    }
    pack(img, p->buffer);
    int r = p->api.push(p->engine, p->buffer, p->bytes, llround(img->pts * 1000000.0));
    if (r != 0) {
        reset(f); p->bypass = true; p->passthrough = r == -3 ? PASS_TIMING : PASS_ERROR;
        MP_WARN(f, "SVP input discontinuity/error %d; passing through until reset\n", r);
        mp_pin_in_write(f->ppins[1], frame); return;
    }
    mp_image_unrefp(&p->reference); p->reference = img;
    mp_filter_internal_mark_progress(f);
}
static bool command(struct mp_filter *f, struct mp_filter_command *cmd) {
    struct priv *p = f->priv;
    if (cmd->type != MP_FILTER_COMMAND_GET_META) return false;
    struct mp_tags *tags = talloc_zero(NULL, struct mp_tags);
    AmbientSvpStats s = {0}; if (p->engine) p->api.stats(p->engine, &s);
    mp_tags_set_str(tags, "backend", s.backend == 2 ? "vulkan" : s.backend == 1 ? "cpu" : "off");
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
    .process = process, .reset = reset, .destroy = destroy, .command = command};
static struct mp_filter *create(struct mp_filter *parent, void *options) {
    struct mp_filter *f = mp_filter_create(parent, &info);
    if (!f) { talloc_free(options); return NULL; }
    struct priv *p = f->priv; p->opts = talloc_steal(p, options); svp_load(&p->api);
    mp_filter_add_pin(f, MP_PIN_IN, "in"); mp_filter_add_pin(f, MP_PIN_OUT, "out"); return f;
}
#define OPT_BASE_STRUCT struct opts
static const m_option_t options[] = {
    {"fps", OPT_DOUBLE(fps), M_RANGE(5, 120)}, {"source", OPT_DOUBLE(source), M_RANGE(5, 120)},
    {"preset", OPT_INT(preset), M_RANGE(0, 3)}, {"backend", OPT_INT(backend), M_RANGE(0, 2)},
    {"threads", OPT_INT(threads), M_RANGE(0, 8)},
    {"artifacts", OPT_INT(artifacts), M_RANGE(0, 3)},
    {"scene", OPT_INT(scene), M_RANGE(0, 3)},
    {"algo", OPT_INT(algo), M_RANGE(0, 23)},
    {"block", OPT_INT(block), M_RANGE(0, 32)},
    {"overlap", OPT_INT(overlap), M_RANGE(0, 4)},
    {"pel", OPT_INT(pel), M_RANGE(0, 4)},
    {"search", OPT_INT(search), M_RANGE(0, 3)},
    {0}
};
const struct mp_user_filter_entry vf_ambientsvp = {
    .desc = {.name = "ambientsvp", .description = "Open-SVPFlow motion-compensated interpolation",
        .priv_size = sizeof(struct opts), .priv_defaults = &(const struct opts){.fps = 60, .source = 24}, .options = options},
    .create = create,
};
