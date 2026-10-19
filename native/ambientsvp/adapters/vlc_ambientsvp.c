/* SPDX-License-Identifier: LGPL-2.1-or-later */
#ifdef HAVE_CONFIG_H
#include "config.h"
#endif
#include <vlc_common.h>
#include <vlc_configuration.h>
#include <vlc_plugin.h>
#include <vlc_filter.h>
#include <vlc_picture.h>
#include <math.h>
#include "ambientsvp_loader.h"
#include "svp_retry.h"

static int Open(filter_t *filter);
vlc_module_begin()
    set_description(N_("Open-SVPFlow motion-compensated interpolation"))
    set_shortname("Open-SVPFlow")
    set_subcategory(SUBCAT_VIDEO_VFILTER)
    add_shortcut("ambientsvp")
    add_float_with_range("ambientsvp-fps", 60., 5., 120., N_("Target frame rate"), NULL)
    add_integer_with_range("ambientsvp-preset", 0, 0, 3, N_("Quality preset"), NULL)
    add_integer_with_range("ambientsvp-backend", 0, 0, 2, N_("Compute backend"), NULL)
    add_integer_with_range("ambientsvp-threads", 0, 0, 8, N_("SVP threads"), NULL)
    add_integer_with_range("ambientsvp-artifacts", 0, 0, 3, N_("SVP artifacts"), NULL)
    add_integer_with_range("ambientsvp-scene", 0, 0, 3, N_("SVP scene"), NULL)
    add_integer_with_range("ambientsvp-algo", 0, 0, 23, N_("SVP algo"), NULL)
    add_integer_with_range("ambientsvp-block", 0, 0, 32, N_("SVP block"), NULL)
    add_integer_with_range("ambientsvp-overlap", 0, 0, 4, N_("SVP overlap"), NULL)
    add_integer_with_range("ambientsvp-pel", 0, 0, 4, N_("SVP pel"), NULL)
    add_integer_with_range("ambientsvp-search", 0, 0, 3, N_("SVP search"), NULL)

    set_callback_video_filter(Open)
vlc_module_end()

typedef struct {
    SvpApi api; AmbientSvp *engine; AmbientSvpConfig config;
    uint8_t *buffer; size_t bytes; bool bypass;
    picture_t *reference;
    bool measure_rate;
    int rate_samples;
    int timing_resets;
    /* Cool-down after repeated timing gaps; kept across VLC's frequent flushes. */
    SvpRetry retry;
    vlc_tick_t rate_origin, rate_last;
} filter_sys_t;
// Lossless I420/NV12 packing; retain decoder padding/crop and output format.
static void Transfer(picture_t *pic, uint8_t *data, int w, int h, bool pack) {
    const bool nv12 = pic->format.i_chroma == VLC_CODEC_NV12;
    for (int plane = 0, offset = 0; plane < 3; plane++) {
        int shift = plane != 0, pw = w >> shift, ph = h >> shift;
        int idx = nv12 && plane ? 1 : plane;
        int step = nv12 && plane ? 2 : 1;
        int ox = (pic->format.i_x_offset >> shift) * step + (nv12 && plane ? plane-1 : 0);
        int oy = pic->format.i_y_offset >> shift;
        for (int y = 0; y < ph; y++) {
            uint8_t *pixels = pic->p[idx].p_pixels + (y+oy)*pic->p[idx].i_pitch + ox;
            uint8_t *row = data + offset + y*pw;
            if (step == 1) { if (pack) memcpy(row,pixels,pw); else memcpy(pixels,row,pw); }
            else for (int x = 0; x < pw; x++) { if (pack) row[x]=pixels[x*2]; else pixels[x*2]=row[x]; }
        }
        offset += pw*ph;
    }
}
static void DropEngine(filter_t *f) {
    filter_sys_t *s = f->p_sys;
    if (s->engine) s->api.destroy(s->engine);
    s->engine = NULL;
    if (s->reference) picture_Release(s->reference);
    s->reference = NULL;
}
static void Flush(filter_t *f) {
    filter_sys_t *s = f->p_sys;
    // VLC also flushes on dropped/late pictures and clock changes. Keep the
    // measured source rate and warm Vulkan pipelines: rebuilding either on
    // every flush creates a latency -> flush -> pipeline compilation loop.
    // Overload/error is sticky for this filter instance, including seeks.
    if (s->engine) {
        AmbientSvpStats stats = {0};
        if (s->api.stats(s->engine, &stats) || stats.bypass_reason == 1 || stats.bypass_reason == 3)
            s->bypass = true;
        if (!s->api.enabled() || s->bypass || s->api.reset(s->engine)) DropEngine(f);
    }
    if (s->reference) picture_Release(s->reference);
    s->reference = NULL;
    s->rate_samples = 0; s->rate_origin = s->rate_last = VLC_TICK_INVALID;
    s->timing_resets = 0;
}
static void Close(filter_t *f) {
    filter_sys_t *s = f->p_sys; DropEngine(f); free(s->buffer);
    if (s->api.lib) dlclose(s->api.lib);
}
static picture_t *Drain(filter_t *f, bool eof) {
    filter_sys_t *s = f->p_sys; picture_t *head = NULL, **tail = &head;
    if (!s->engine || !s->reference) return NULL;
    for (int n = 0; n < 60; n++) {
        int64_t pts;
        int r = s->api.receive(s->engine, s->buffer, s->bytes, &pts, eof);
        if (r <= 0) { if (r < 0) s->api.bypass(s->engine, 3); break; }
        picture_t *out = filter_NewPicture(f);
        if (!out) { s->api.bypass(s->engine, 3); break; }
        picture_CopyProperties(out, s->reference);
        const int w = s->config.width, h = s->config.height;
        Transfer(out, s->buffer, w, h, false);
        out->date = VLC_TICK_FROM_US(pts);
        *tail = out; tail = &out->p_next;
    }
    return head;
}
static picture_t *Filter(filter_t *f, picture_t *pic) {
    filter_sys_t *s = f->p_sys;
    if (!pic) return Drain(f, true);
    if (!s->api.enabled() || s->bypass || !pic->b_progressive || pic->date == VLC_TICK_INVALID) {
        DropEngine(f);
        return pic;
    }
    if (svp_retry_waiting(&s->retry)) return pic;
    if (!s->engine) {
        // Android MediaCodec may omit frame_rate in fmt_in (0/0). Measure
        // twelve consistent source PTS intervals; pass through during probing.
        // Do not guess 24 fps or smooth variable-rate/discontinuous sources.
        if (!s->config.source_num || !s->config.source_den) {
            vlc_tick_t delta = pic->date - s->rate_last;
            vlc_tick_t average = s->rate_samples ? (s->rate_last-s->rate_origin)/s->rate_samples : delta;
            if (s->rate_last == VLC_TICK_INVALID || delta <= 0 || delta > VLC_TICK_FROM_MS(200)
                || llabs(delta-average) > __MAX(VLC_TICK_FROM_MS(3),average/10)) {
                s->rate_origin=pic->date; s->rate_samples=0;
            } else if (++s->rate_samples >= 12) {
                uint64_t duration=US_FROM_VLC_TICK(pic->date-s->rate_origin);
                vlc_ureduce(&s->config.source_num,&s->config.source_den,
                    (uint64_t)s->rate_samples*1000000,duration,1000000);
                static const unsigned rates[][2]={{24000,1001},{24,1},{25,1},{30000,1001},{30,1},{50,1},{60000,1001},{60,1},{120000,1001},{120,1}};
                double fps=(double)s->config.source_num/s->config.source_den;
                for (size_t i=0;i<ARRAY_SIZE(rates);i++) if (fabs(fps-(double)rates[i][0]/rates[i][1])<fps*0.0005) {
                    s->config.source_num=rates[i][0]; s->config.source_den=rates[i][1]; break;
                }
            }
            s->rate_last=pic->date;
            return pic;
        }
        s->engine = s->api.create(&s->config);
        if (!s->engine) { s->bypass = true; msg_Warn(f, "SVP unavailable; passing through"); return pic; }
        msg_Info(f, "Open-SVPFlow %ux%u %u/%u -> %u/%u", s->config.width, s->config.height,
            s->config.source_num, s->config.source_den, s->config.target_num, s->config.target_den);
    }
    const int w = s->config.width, h = s->config.height;
    Transfer(pic, s->buffer, w, h, true);
    int r = s->api.push(s->engine, s->buffer, s->bytes, US_FROM_VLC_TICK(pic->date));
    if (r == -3 && s->timing_resets++ < 5) {
        // The vout can skip source pictures while the first Vulkan pipelines
        // compile. Reset temporal history but retain compiled GPU resources.
        msg_Dbg(f,"SVP timing reset %d, input delta %lld us",s->timing_resets,
            (long long)(s->reference ? US_FROM_VLC_TICK(pic->date-s->reference->date) : 0));
        if (!s->api.reset(s->engine)) r=s->api.push(s->engine,s->buffer,s->bytes,US_FROM_VLC_TICK(pic->date));
    }
    if (r == -3) {
        // Still discontinuous after the quick resets: pass through for a cool-down, then retry.
        DropEngine(f); svp_retry_gap(&s->retry); s->timing_resets = 0;
        msg_Warn(f, "SVP timing gap #%d; passing through for %d pictures, then retrying", s->retry.gaps, s->retry.cooldown);
        return pic;
    }
    if (r != 0) {
        DropEngine(f); s->bypass = true;
        msg_Warn(f, "SVP error %d; passing through for this instance", r); return pic;
    }
    svp_retry_frame_ok(&s->retry);
    if (s->reference) picture_Release(s->reference);
    s->reference = pic;
    return Drain(f, false);
}
static const struct vlc_filter_operations ops = {.filter_video = Filter, .flush = Flush, .close = Close};
static int Open(filter_t *f) {
    const video_format_t *v = &f->fmt_in.video;
    // No high-bit-depth/HDR conversion just to enable an optional effect.
    if ((v->i_chroma != VLC_CODEC_I420 && v->i_chroma != VLC_CODEC_NV12)
        || f->fmt_out.video.i_chroma != v->i_chroma
        || v->i_visible_width < 32 || v->i_visible_width > 3840
        || v->i_visible_height < 32 || v->i_visible_height > 2160
        || (v->i_visible_width | v->i_visible_height | v->i_x_offset | v->i_y_offset) & 1
        || v->transfer == TRANSFER_FUNC_SMPTE_ST2084 || v->transfer == TRANSFER_FUNC_HLG) return VLC_EGENERIC;
    filter_sys_t *s = vlc_obj_calloc(VLC_OBJECT(f), 1, sizeof(*s));
    if (!s) return VLC_ENOMEM;
    f->p_sys = s;
    s->measure_rate = !v->i_frame_rate || !v->i_frame_rate_base;
    s->rate_last = s->rate_origin = VLC_TICK_INVALID;
    static const char *const options[] = {"fps", "preset", "backend", "threads", "artifacts", "scene", "algo", "block", "overlap", "pel", "search", NULL};
    config_ChainParse(f, "ambientsvp-", options, f->p_cfg);
    s->config = (AmbientSvpConfig){AMBIENTSVP_ABI, v->i_visible_width, v->i_visible_height, v->i_frame_rate, v->i_frame_rate_base,
        llround(var_InheritFloat(f, "ambientsvp-fps")*1000.), 1000, var_InheritInteger(f, "ambientsvp-preset"),
        var_InheritInteger(f, "ambientsvp-backend"), var_InheritInteger(f, "ambientsvp-threads"), var_InheritInteger(f, "ambientsvp-artifacts"), var_InheritInteger(f, "ambientsvp-scene"), var_InheritInteger(f, "ambientsvp-algo"), var_InheritInteger(f, "ambientsvp-block"), var_InheritInteger(f, "ambientsvp-overlap"), var_InheritInteger(f, "ambientsvp-pel"), var_InheritInteger(f, "ambientsvp-search")};
    if ((!s->measure_rate && (uint64_t)s->config.source_num * s->config.target_den >= (uint64_t)s->config.target_num * s->config.source_den)
        || !svp_load(&s->api)) { msg_Dbg(f,"SVP source >= target or library unavailable"); return VLC_EGENERIC; }
    s->bytes = (size_t)s->config.width * s->config.height * 3 / 2;
    s->buffer = malloc(s->bytes);
    if (!s->buffer) { dlclose(s->api.lib); return VLC_ENOMEM; }
    f->fmt_out.video.i_frame_rate = s->config.target_num;
    f->fmt_out.video.i_frame_rate_base = s->config.target_den;
    f->ops = &ops; return VLC_SUCCESS;
}
