#pragma once
/* Default-off local-window HDR10+ prototype. Uses libplacebo's own ST2094-40
 * LUT generator and GPU-side elliptical weighting. No claim of conformance
 * for the full spatial metadata model (luminance grids/saturation excluded).
 * Only RGB PQ AHB frames -> SDR, no raw P010 or Dolby Vision. */
#include <libplacebo/dispatch.h>
#include <libplacebo/shaders/custom.h>
#include <libplacebo/shaders/colorspace.h>
#include <libplacebo/tone_mapping.h>
#include <libplacebo/renderer.h>
#include "hdr10plus_spatial_geometry.h"

#define AMBIENT_HDRPLUS_LUT_SIZE 256
struct ambient_hdrplus_spatial {
    pl_dispatch dispatch;
    pl_tex lut, output;
    bool valid;
    float curves[3][AMBIENT_HDRPLUS_LUT_SIZE];
    float domains[3];
    struct ambient_hdrplus previous;
    float output_min, output_max;
};
static inline void ambient_hdrplus_spatial_destroy(pl_gpu gpu, struct ambient_hdrplus_spatial *s)
{
    pl_dispatch_destroy(&s->dispatch);
    pl_tex_destroy(gpu,&s->lut);
    pl_tex_destroy(gpu,&s->output);
    s->valid=false;
}
static inline bool ambient_hdrplus_spatial_curves(pl_gpu gpu, struct ambient_hdrplus_spatial *s,
    const struct ambient_hdrplus *m, float out_min, float out_max)
{
    if (s->valid && s->output_min==out_min && s->output_max==out_max &&
        !memcmp(&s->previous,m,sizeof(*m))) return true;
    s->valid=false;
    for (unsigned i=0;i<3;++i) {
        const struct ambient_hdrplus_window *w=&m->windows[i<m->window_count?i:0];
        float peak=10000.f*fmaxf(w->maxscl[0],fmaxf(w->maxscl[1],w->maxscl[2]));
        if (!isfinite(peak) || peak<=out_min || peak>10000.f) return false;
        float dst=fminf(peak,out_max);
        struct pl_tone_map_params p={
            .function=peak>out_max?&pl_tone_map_st2094_40:&pl_tone_map_clip,
            .input_scaling=PL_HDR_SQRT,.output_scaling=PL_HDR_NORM,
            .lut_size=AMBIENT_HDRPLUS_LUT_SIZE,
            .input_min=0.f,.input_max=pl_hdr_rescale(PL_HDR_NITS,PL_HDR_SQRT,peak),
            .input_avg=pl_hdr_rescale(PL_HDR_NITS,PL_HDR_SQRT,w->average*10000.f),
            .output_min=pl_hdr_rescale(PL_HDR_NITS,PL_HDR_NORM,out_min),
            .output_max=pl_hdr_rescale(PL_HDR_NITS,PL_HDR_NORM,dst),
        };
        p.hdr.ootf.target_luma=m->target_nits;
        p.hdr.ootf.knee_x=w->knee_x;
        p.hdr.ootf.knee_y=w->knee_y;
        p.hdr.ootf.num_anchors=w->anchor_count;
        memcpy(p.hdr.ootf.anchors,w->anchors,sizeof(w->anchors));
        s->domains[i]=p.input_max;
        pl_tone_map_generate(s->curves[i],&p);
        float previous=0.f;
        for(unsigned j=0;j<AMBIENT_HDRPLUS_LUT_SIZE;++j) {
            float v=s->curves[i][j];
            if (!isfinite(v) || v<0.f || v>p.output_max+1e-4f || v+1e-5f<previous)
                return false;
            previous=v;
        }
    }
    pl_fmt fmt=pl_find_fmt(gpu,PL_FMT_FLOAT,1,32,32,PL_FMT_CAP_SAMPLEABLE | PL_FMT_CAP_HOST_WRITABLE);
    if (!fmt || !pl_tex_recreate(gpu,&s->lut,pl_tex_params(
        .w=AMBIENT_HDRPLUS_LUT_SIZE,.h=3,.format=fmt,.sampleable=true,.host_writable=true)))
        return false;
    if (!pl_tex_upload(gpu,pl_tex_transfer_params(.tex=s->lut,.ptr=s->curves))) return false;
    s->previous=*m;s->output_min=out_min;s->output_max=out_max;s->valid=true;
    return true;
}
static const char ambient_hdrplus_mask_glsl[] =
    "float ahp_weight(int i, vec2 p) {\n"
    "  vec4 r=ahp_rect[i], e=ahp_ellipse[i], t=ahp_rotation[i];\n"
    "  if(t.w==0.0 || any(lessThan(p,r.xy)) || any(greaterThan(p,r.zw))) return 0.0;\n"
    "  vec2 q=p-e.xy; vec2 v=vec2(dot(t.xy,q),dot(vec2(-t.y,t.x),q))*e.zw;\n"
    "  float d=length(v); if(d<=t.z) return 1.0;\n"
    "  return clamp((1.0-d)/max(1.0-t.z,1e-6),0.0,1.0);\n"
    "}\n"
    "float ahp_curve(int i,float v) {\n"
    "  float x=clamp(sqrt(max(v,0.0))/ahp_domains[i],0.0,1.0)*255.0;\n"
    "  int n=int(floor(x));\n"
    "  return mix(texelFetch(ahp_lut,ivec2(n,i),0).r,texelFetch(ahp_lut,ivec2(min(n+1,255),i),0).r,fract(x));\n"
    "}\n";
static const char ambient_hdrplus_map_glsl[] =
    "vec2 p=floor(ahp_uv*ahp_size);\n"
    "float v=max(0.0,max(color.r,max(color.g,color.b)));\n"
    "float a=ahp_weight(0,p), b=ahp_weight(1,p);\n"
    "float g=ahp_curve(0,v), l=ahp_curve(1,v), r=ahp_curve(2,v);\n"
    "float mapped=ahp_layering!=0?mix(mix(g,l,a),r,b):"
    "(g*max(0.0,1.0-a-b)+l*a+r*b)/max(1.0,a+b);\n"
    "color.rgb=v>1e-7?max(color.rgb,vec3(0.0))*(mapped/v):vec3(0.0);\n";
static inline bool ambient_hdrplus_spatial_render(pl_gpu gpu, pl_log log,
    struct ambient_hdrplus_spatial *s, struct pl_frame *img,
    const struct pl_frame *target, const struct ambient_hdrplus *m)
{
    if (!gpu || !s || !img || !target || !m || img->num_planes!=1 ||
        img->repr.sys!=PL_COLOR_SYSTEM_RGB || img->color.transfer!=PL_COLOR_TRC_PQ ||
        img->color.primaries!=PL_COLOR_PRIM_BT_2020 || !img->planes[0].texture)
        return false;
    pl_tex input=img->planes[0].texture;
    int w=input->params.w,h=input->params.h;
    if (w<=0 || h<=0) return false;
    struct ambient_hdrplus_mask masks[2];
    bool layering;
    if (!ambient_hdrplus_spatial_supported(m,w,h,masks,&layering)) return false;
    struct pl_color_space src=img->color,dst=target->color;
    pl_color_space_infer_map(&src,&dst);
    if (pl_color_space_is_hdr(&dst)) return false;
    float out_min=0.f,out_max=0.f;
    pl_color_space_nominal_luma_ex(pl_nominal_luma_params(
        .color=&dst,.metadata=PL_HDR_METADATA_HDR10,.scaling=PL_HDR_NITS,
        .out_min=&out_min,.out_max=&out_max));
    if (!(out_min>=0.f && out_max>out_min && out_max<=1000.f)) return false;
    if (!ambient_hdrplus_spatial_curves(gpu,s,m,out_min,out_max)) return false;
    pl_fmt fmt=pl_find_fmt(gpu,PL_FMT_FLOAT,4,16,0,PL_FMT_CAP_SAMPLEABLE | PL_FMT_CAP_RENDERABLE);
    if (!fmt || !pl_tex_recreate(gpu,&s->output,pl_tex_params(
        .w=w,.h=h,.format=fmt,.sampleable=true,.renderable=true))) return false;
    if (!s->dispatch) s->dispatch=pl_dispatch_create(log,gpu);
    pl_fmt vertex=pl_find_vertex_fmt(gpu,PL_FMT_FLOAT,2);
    if (!s->dispatch || !vertex) return false;
    const float uv[4][2]={{0,0},{1,0},{0,1},{1,1}};
    const struct pl_shader_va va={.attr={.name="ahp_uv",.fmt=vertex},
        .data={uv[0],uv[1],uv[2],uv[3]}};
    const struct pl_shader_desc desc[2]={
        {.desc={.name="ahp_input",.type=PL_DESC_SAMPLED_TEX},.binding={.object=input}},
        {.desc={.name="ahp_lut",.type=PL_DESC_SAMPLED_TEX},.binding={.object=s->lut}},
    };
    float rect[2][4],ellipse[2][4],rotation[2][4],size[2]={(float)w,(float)h};
    int layer=layering?1:0;
    for (unsigned i=0;i<2;++i) {
        memcpy(rect[i],masks[i].rect,sizeof(rect[i]));
        memcpy(ellipse[i],masks[i].ellipse,sizeof(ellipse[i]));
        memcpy(rotation[i],masks[i].rotation,sizeof(rotation[i]));
    }
    struct pl_shader_var vars[]={
        {.var=pl_var_vec4("ahp_rect"),.data=rect,.dynamic=true},
        {.var=pl_var_vec4("ahp_ellipse"),.data=ellipse,.dynamic=true},
        {.var=pl_var_vec4("ahp_rotation"),.data=rotation,.dynamic=true},
        {.var=pl_var_vec3("ahp_domains"),.data=s->domains,.dynamic=true},
        {.var=pl_var_vec2("ahp_size"),.data=size,.dynamic=true},
        {.var=pl_var_int("ahp_layering"),.data=&layer,.dynamic=true},
    };
    for(unsigned i=0;i<3;++i) vars[i].var.dim_a=2;
    pl_dispatch_reset_frame(s->dispatch);
    pl_shader sh=pl_dispatch_begin(s->dispatch);
    if (!pl_shader_custom(sh,&(struct pl_custom_shader){
        .description="Ambient HDR10+ spatial source",.input=PL_SHADER_SIG_NONE,
        .output=PL_SHADER_SIG_COLOR,
        .body="color=texelFetch(ahp_input,clamp(ivec2(ahp_uv*ahp_size),ivec2(0),ivec2(ahp_size)-1),0);",
        .descriptors=desc,.num_descriptors=2,.variables=vars,.num_variables=6,
        .vertex_attribs=&va,.num_vertex_attribs=1,.output_w=w,.output_h=h,
    })) goto fail;
    pl_shader_linearize(sh,&src);
    if (!pl_shader_custom(sh,&(struct pl_custom_shader){
        .description="Ambient HDR10+ local-window blend",.input=PL_SHADER_SIG_COLOR,
        .output=PL_SHADER_SIG_COLOR,.header=ambient_hdrplus_mask_glsl,.body=ambient_hdrplus_map_glsl,
    })) goto fail;
    // Keep PQ encoding so the main renderer can still perform gamut mapping,
    // dithering, scaling and overlays. Advertise the actual mapped peak,
    // not the old source peak or old HDR10+ metadata.
    struct pl_color_space mapped=src;
    mapped.hdr=(struct pl_hdr_metadata){.prim=src.hdr.prim,.min_luma=out_min,.max_luma=out_max};
    pl_shader_delinearize(sh,&mapped);
    if (!pl_dispatch_finish(s->dispatch,pl_dispatch_params(.shader=&sh,.target=s->output)))
        return false;
    img->planes[0].texture=s->output;
    img->color=mapped;
    return true;
fail:
    pl_dispatch_abort(s->dispatch,&sh);
    return false;
}
