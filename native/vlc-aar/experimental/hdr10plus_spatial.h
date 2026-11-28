#pragma once
/* Default-off local-window HDR10+ prototype. Uses libplacebo's own ST2094-40
 * LUT generator and GPU-side elliptical weighting. No claim of complete
 * ST2094-40 conformance. Scene LUTs and saturation use an
 * adapted display peak with the existing libplacebo curve generator.
 * RGB PQ or native packed P010 AHB frames -> SDR or explicit PQ/HLG
 * targets, no Dolby Vision. Keep display-linear light until PQ re-encoding. */
#include <libplacebo/dispatch.h>
#include <libplacebo/shaders/custom.h>
#include <libplacebo/shaders/colorspace.h>
#include <libplacebo/tone_mapping.h>
#include <libplacebo/renderer.h>
#include "hdr10plus_spatial_geometry.h"

// For degree <= 16 with control points in [0,1], |B''| <= 480.
// Linear interpolation on 1024 uniform knots bounds normalized authored
// curve error by 480/(8*1023^2) < 0.000058 (excluding FP roundoff).
#define AMBIENT_HDRPLUS_LUT_SIZE 1024
struct ambient_hdrplus_spatial {
    pl_dispatch dispatch;
    pl_tex lut, output;
    bool valid;
    float curves[3][AMBIENT_HDRPLUS_LUT_SIZE];
    float domains[3];
    // x/y knee, normalized output peak and direct-authored mode flag.
    float authored[3][4];
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
/* Only parameters consumed by the LUT generator belong to its cache key.
 * Moving windows and updated distributions must not trigger GPU LUT uploads.
 * Compare fields, not struct bytes (which include padding and unused slots). */
static inline bool ambient_hdrplus_curve_key_equal(const struct ambient_hdrplus *a,
    const struct ambient_hdrplus *b)
{
    if (!a || !b || a->window_count<1 || a->window_count>3 ||
        a->window_count != b->window_count || a->target_nits != b->target_nits)
        return false;
    for (unsigned i=0; i<a->window_count; ++i) {
        const struct ambient_hdrplus_window *x=&a->windows[i], *y=&b->windows[i];
        float xp=ambient_hdrplus_normalization_nits(a,x);
        float yp=ambient_hdrplus_normalization_nits(b,y);
        float am,at,bm,bt;
        if (!ambient_hdrplus_grid_factor(&a->mastering_grid,x,&am) ||
            !ambient_hdrplus_grid_factor(&a->targeted_grid,x,&at) ||
            !ambient_hdrplus_grid_factor(&b->mastering_grid,y,&bm) ||
            !ambient_hdrplus_grid_factor(&b->targeted_grid,y,&bt) || am!=bm || at!=bt)
            return false;
        if (xp!=yp || x->tone_mapping!=y->tone_mapping || x->average!=y->average || x->knee_x!=y->knee_x ||
            x->knee_y!=y->knee_y || x->anchor_count!=y->anchor_count ||
            x->anchor_count>15)
            return false;
        for (unsigned j=0; j<x->anchor_count; ++j)
            if (x->anchors[j]!=y->anchors[j]) return false;
    }
    return true;
}
static inline bool ambient_hdrplus_spatial_curves(pl_gpu gpu, struct ambient_hdrplus_spatial *s,
    const struct ambient_hdrplus *m, float out_min, float out_max)
{
    if (!gpu || !s || !m || m->window_count<1 || m->window_count>3 ||
        !isfinite(out_min) || !isfinite(out_max) || out_min<0.f || out_max<=out_min)
        return false;
    if (s->valid && s->output_min==out_min && s->output_max==out_max &&
        ambient_hdrplus_curve_key_equal(&s->previous,m)) return true;
    s->valid=false;
    for (unsigned i=0;i<m->window_count;++i) {
        const struct ambient_hdrplus_window *w=&m->windows[i];
        float peak=ambient_hdrplus_normalization_nits(m,w);
        if (!isfinite(peak) || peak<=out_min || peak>10000.f ||
            !isfinite(w->average) || w->average<0.f || w->average>1.f ||
            w->anchor_count>15 ||
            (w->tone_mapping && !ambient_hdrplus_usable_curve(w,m->target_nits))) return false;
        float source_factor,target_factor;
        if (!ambient_hdrplus_grid_factor(&m->mastering_grid,w,&source_factor) ||
            !ambient_hdrplus_grid_factor(&m->targeted_grid,w,&target_factor)) return false;
        float target_peak=out_max*target_factor;
        if (peak<=out_min || target_peak<=out_min) return false;
        float dst=fminf(peak,target_peak);
        struct pl_tone_map_params p={
            .function=peak>target_peak?&pl_tone_map_st2094_40:&pl_tone_map_clip,
            .input_scaling=PL_HDR_SQRT,.output_scaling=PL_HDR_NORM,
            .lut_size=AMBIENT_HDRPLUS_LUT_SIZE,
            .input_min=0.f,.input_max=pl_hdr_rescale(PL_HDR_NITS,PL_HDR_SQRT,peak),
            .input_avg=pl_hdr_rescale(PL_HDR_NITS,PL_HDR_SQRT,fminf(peak,w->average*10000.f)),
            .output_min=pl_hdr_rescale(PL_HDR_NITS,PL_HDR_NORM,out_min),
            .output_max=pl_hdr_rescale(PL_HDR_NITS,PL_HDR_NORM,dst),
        };
        if(w->tone_mapping){
            p.hdr.ootf.target_luma=m->target_nits*target_factor;
            p.hdr.ootf.knee_x=w->knee_x;
            p.hdr.ootf.knee_y=w->knee_y;
            p.hdr.ootf.num_anchors=w->anchor_count;
            memcpy(p.hdr.ootf.anchors,w->anchors,w->anchor_count * sizeof(w->anchors[0]));
        }
        s->domains[i]=p.input_max;
        // For the authored target, evaluate the specified normalized
        // function directly. Adaptation to a different display peak is
        // libplacebo policy, except for endpoint knees which its fitting
        // implementation cannot represent reliably.
        const bool direct=w->tone_mapping && (!w->anchor_count ||
            w->knee_x==0.f||w->knee_x==1.f||w->knee_y==0.f||w->knee_y==1.f ||
            fabsf(out_max-m->target_nits)<=0.01f);
        memset(s->authored[i],0,sizeof(s->authored[i]));
        if(direct) {
            // Exact linear segment and a LUT parameterized only over the
            // curved segment. Never interpolate across the knee. The
            // specified normalized curve is scaled by target peak, without
            // adding a display-black pedestal (the main renderer handles it).
            p.output_max=pl_hdr_rescale(PL_HDR_NITS,PL_HDR_NORM,target_peak);
            s->authored[i][0]=w->knee_x;s->authored[i][1]=w->knee_y;
            s->authored[i][2]=p.output_max;s->authored[i][3]=1.f;
            struct ambient_hdrplus_window segment=*w;
            segment.knee_x=0.f;
            for(unsigned j=0;j<AMBIENT_HDRPLUS_LUT_SIZE;j++){
                float t=(float)j/(AMBIENT_HDRPLUS_LUT_SIZE-1);
                float y=w->knee_x==1.f?w->knee_y:ambient_hdrplus_bezier(&segment,t);
                s->curves[i][j]=p.output_max*y;
            }
        }else pl_tone_map_generate(s->curves[i],&p);
        float previous=0.f;
        for(unsigned j=0;j<AMBIENT_HDRPLUS_LUT_SIZE;++j) {
            float v=s->curves[i][j];
            if (!isfinite(v) || v<0.f || v>p.output_max+1e-4f || v+1e-5f<previous)
                return false;
            previous=v;
        }
    }
    // Unused local rows mirror the global row without repeating CPU curve
    // generation. Their selectors are disabled, but keep shader inputs valid.
    for(unsigned i=m->window_count;i<3;++i){
        memcpy(s->curves[i],s->curves[0],sizeof(s->curves[i]));
        memcpy(s->authored[i],s->authored[0],sizeof(s->authored[i]));
        s->domains[i]=s->domains[0];
    }
    pl_fmt fmt=pl_find_fmt(gpu,PL_FMT_FLOAT,1,32,32,PL_FMT_CAP_SAMPLEABLE);
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
    "  float d=length(v); if(d>=1.0) return 0.0; if(d<=t.z) return 1.0;\n"
    "  float u=clamp((d-t.z)/max(1.0-t.z,1e-6),0.0,1.0);\n"
    "  return 0.5+0.5*cos(3.141592653589793*u);\n"
    "}\n"
    "float ahp_curve(int i,float root) {\n"
    "  float u=clamp(root/ahp_domains[i],0.0,1.0);\n"
    "  vec4 k=ahp_authored[i];\n"
    "  if(k.w>0.0) {\n"
    "    float s=u*u;\n"
    "    if(k.x==1.0 || s<k.x) return k.z*k.y*s/k.x;\n"
    "    u=(s-k.x)/(1.0-k.x);\n"
    "  }\n"
    "  int last=textureSize(ahp_lut,0).x-1;\n"
    "  float x=clamp(u,0.0,1.0)*float(last);\n"
    "  int n=int(floor(x));\n"
    "  return mix(texelFetch(ahp_lut,ivec2(n,i),0).r,texelFetch(ahp_lut,ivec2(min(n+1,last),i),0).r,fract(x));\n"
    "}\n"
    "vec3 ahp_map_rgb(int i,vec3 rgb,float v,float root) {\n"
    "  if(v<=0.0) return vec3(0.0);\n"
    "  float domain=ahp_domains[i]*ahp_domains[i];\n"
    "  vec3 normalized=clamp(rgb/domain,0.0,1.0);\n"
    "  float s=min(v/domain,1.0);\n"
    "  float mapped=ahp_curve(i,root);\n"
    "  vec3 out_rgb=normalized*(mapped/s);\n"
    "  if(ahp_sat[i]<=0.0 || mapped<=0.0) return out_rgb;\n"
    "  float source_nits=s*ahp_sat_source_scale[i];\n"
    "  float target_nits=clamp(mapped/ahp_sat_peak[i],0.0,1.0)*ahp_sat_target_scale[i];\n"
    "  if(target_nits<=1.0001 || source_nits<=target_nits) return out_rgb;\n"
    "  float gain=min(4.0,1.0+ahp_sat[i]*max(0.0,log(source_nits)/log(target_nits)-1.0));\n"
    "  vec3 enc=pow(clamp(out_rgb/ahp_sat_peak[i],0.0,1.0),vec3(1.0/2.4));\n"
    "  float y=dot(enc,vec3(0.2627,0.6780,0.0593)); vec3 d=enc-vec3(y);\n"
    "  for(int k=0;k<3;++k) {\n"
    "    if(d[k]>0.0) gain=min(gain,(1.0-y)/d[k]);\n"
    "    else if(d[k]<0.0) gain=min(gain,-y/d[k]);\n"
    "  }\n"
    "  return pow(clamp(vec3(y)+gain*d,0.0,1.0),vec3(2.4))*ahp_sat_peak[i];\n"
    "}\n";
/* ST 2094-40:2020 informative Annex B.5 uses BT.1886 as an intermediate
 * color transform (B.6/B.10), NOT the final display transfer function.
 * The adapted curve supplies display-linear light, not PQ/HLG code values.
 * Use ideal-black 2.4 power, bounded gain and RGB gamut (B.12), then return
 * linear light. The ordinary renderer handles final PQ/HLG/SDR encoding.
 * Guard logarithm singularities and leave blacks/neutral colors unchanged. */
static const char ambient_hdrplus_map_glsl[] =
    "vec2 p=floor(ahp_uv*ahp_size);\n"
    "vec3 rgb=max(color.rgb,vec3(0.0));\n"
    "float v=max(rgb.r,max(rgb.g,rgb.b)), root=sqrt(v);\n"
    "float a=ahp_weight(0,p), b=ahp_weight(1,p);\n"
    "vec3 g=ahp_map_rgb(0,rgb,v,root);\n"
    "if(ahp_layering!=0) {\n"
    "  if(a>0.0) color.rgb=mix(g,ahp_map_rgb(1,rgb,v,root),a);\n"
    "  else if(b>0.0) color.rgb=mix(g,ahp_map_rgb(2,rgb,v,root),b);\n"
    "  else color.rgb=g;\n"
    "} else if(a+b>0.0) {\n"
    "  float wa=a*a/(a+b), wb=b*b/(a+b);\n"
    "  color.rgb=g*(1.0-wa-wb);\n"
    "  if(a>0.0) color.rgb+=wa*ahp_map_rgb(1,rgb,v,root);\n"
    "  if(b>0.0) color.rgb+=wb*ahp_map_rgb(2,rgb,v,root);\n"
    "} else color.rgb=g;\n";
/* Display luminance is supplied independently of source/mastering metadata.
 * Mode 1 = PQ, 2 = HLG. Never infer HDR target values from swapchain hints. */
static inline bool ambient_hdrplus_target_luma_valid(int mode,float min,float max)
{
    if((mode!=1&&mode!=2)||!(max>=100.f&&max<=10000.f)||
        !(min>=0.f&&min<max))return false;
    if(mode==2){
        float gamma=fmaxf(1.f,1.2f+0.42f*log10f(max/1000.f));
        float beta=sqrtf(3.f*powf(min/max,1.f/gamma));
        if(!(beta>=0.f&&beta<1.f))return false;
    }
    return true;
}
static inline bool ambient_hdrplus_characterize_target(struct pl_frame *target,
    int mode,float min,float max)
{
    if(!target||!ambient_hdrplus_target_luma_valid(mode,min,max)||
        target->color.transfer!=(mode==1?PL_COLOR_TRC_PQ:PL_COLOR_TRC_HLG)||
        target->color.primaries!=PL_COLOR_PRIM_BT_2020||
        target->repr.bits.color_depth<10)return false;
    target->color.hdr=(struct pl_hdr_metadata){
        .prim=*pl_raw_primaries_get(PL_COLOR_PRIM_BT_2020),.min_luma=min,.max_luma=max};
    return true;
}
static inline bool ambient_hdrplus_spatial_render(pl_gpu gpu, pl_log log,
    struct ambient_hdrplus_spatial *s, struct pl_frame *img,
    const struct pl_frame *target, const struct ambient_hdrplus *m)
{
    if (!gpu || !s || !img || !target || !m || img->num_planes!=1 ||
        img->color.transfer!=PL_COLOR_TRC_PQ ||
        img->color.primaries!=PL_COLOR_PRIM_BT_2020 || !img->planes[0].texture)
        return false;
    const struct pl_plane *plane=&img->planes[0];
    const bool raw=img->repr.sys==PL_COLOR_SYSTEM_BT_2020_NC;
    // Only the exact native AHB identity layout, not arbitrary YUV planes.
    // Reject Dolby metadata and unproven range/bit depth before decoding.
    if(img->repr.dovi || img->profile.len || target->profile.len ||
        img->lut || target->lut || plane->shift_x!=0.f || plane->shift_y!=0.f ||
        plane->components!=3 || plane->component_mapping[3]!=-1 ||
        (raw ? (plane->component_mapping[0]!=2 || plane->component_mapping[1]!=0 ||
            plane->component_mapping[2]!=1 || img->repr.bits.sample_depth!=10 ||
            img->repr.bits.color_depth!=10 || img->repr.bits.bit_shift!=0 ||
            (img->repr.levels!=PL_COLOR_LEVELS_FULL && img->repr.levels!=PL_COLOR_LEVELS_LIMITED)) :
            (img->repr.sys!=PL_COLOR_SYSTEM_RGB || plane->component_mapping[0]!=0 ||
             plane->component_mapping[1]!=1 || plane->component_mapping[2]!=2 ||
             (img->repr.levels!=PL_COLOR_LEVELS_FULL && img->repr.levels!=PL_COLOR_LEVELS_UNKNOWN) ||
             img->repr.bits.bit_shift!=0)))
        return false;
    pl_tex input=plane->texture;
    int w=input->params.w,h=input->params.h;
    // The prepass reads the whole source in unrotated logical coordinates.
    // A full reversed crop is VLC's orientation transform, not a subcrop.
    // Leave crop direction and rotation for the main renderer. A physically
    // flipped plane is normalized in the fetch below before mask evaluation.
    bool full_x=(img->crop.x0==0.f && img->crop.x1==(float)w) ||
                (img->crop.x1==0.f && img->crop.x0==(float)w);
    bool full_y=(img->crop.y0==0.f && img->crop.y1==(float)h) ||
                (img->crop.y1==0.f && img->crop.y0==(float)h);
    if (w<=0 || h<=0 || input==s->output || !input->params.sampleable ||
        !full_x || !full_y) return false;
    struct ambient_hdrplus_mask masks[2];
    bool layering;
    if (!ambient_hdrplus_spatial_supported(m,w,h,masks,&layering)) return false;
    struct pl_color_space src=img->color,dst=target->color;
    // Do not mistake an inferred nominal peak for a characterized target.
    // Pinned libplacebo ignores static peak metadata below 100 nits.
    const bool hdr_target=dst.transfer==PL_COLOR_TRC_PQ || dst.transfer==PL_COLOR_TRC_HLG;
    if(hdr_target && !ambient_hdrplus_target_luma_valid(
        dst.transfer==PL_COLOR_TRC_PQ?1:2,dst.hdr.min_luma,dst.hdr.max_luma))return false;
    pl_color_space_infer_map(&src,&dst);
    if (pl_color_space_is_hdr(&dst) && !hdr_target) return false;
    float out_min=0.f,out_max=0.f;
    pl_color_space_nominal_luma_ex(pl_nominal_luma_params(
        .color=&dst,.metadata=PL_HDR_METADATA_HDR10,.scaling=PL_HDR_NITS,
        .out_min=&out_min,.out_max=&out_max));
    if (!(out_min>=0.f && out_max>out_min && out_max<=(hdr_target?10000.f:1000.f))) return false;
    if (!ambient_hdrplus_spatial_curves(gpu,s,m,out_min,out_max)) return false;
    pl_fmt fmt=pl_find_fmt(gpu,PL_FMT_FLOAT,4,16,0,PL_FMT_CAP_SAMPLEABLE | PL_FMT_CAP_RENDERABLE | PL_FMT_CAP_LINEAR);
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
    int layer=layering?1:0, flipped=plane->flipped?1:0;
    float sat[3]={0},sat_peak[3]={0},sat_source_scale[3]={0},sat_target_scale[3]={0};
    for(unsigned i=0;i<3;++i) {
        const struct ambient_hdrplus_window *window=&m->windows[i<m->window_count?i:0];
        float factor;
        if (!ambient_hdrplus_grid_factor(&m->targeted_grid,window,&factor)) return false;
        sat[i]=window->saturation_mapping?window->saturation_weight/8.f:0.f;
        sat_peak[i]=pl_hdr_rescale(PL_HDR_NITS,PL_HDR_NORM,out_max*factor);
        if(sat[i]>0.f){
            // Use explicit mastering metadata when present. Scene peak is
            // a documented adaptation fallback, not invented ST2086 data.
            float mastering=img->color.hdr.max_luma;
            if(!(mastering>0.f && mastering<=10000.f))
                mastering=10000.f*ambient_hdrplus_source_peak(window,m->version);
            float authored=m->target_nits>0.f?m->target_nits:out_max;
            if(!ambient_hdrplus_saturation_scales(m,window,mastering,authored,
                &sat_source_scale[i],&sat_target_scale[i])) sat[i]=0.f;
        }
    }
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
        {.var=pl_var_int("ahp_flipped"),.data=&flipped,.dynamic=true},
        {.var=pl_var_vec3("ahp_sat"),.data=sat,.dynamic=true},
        {.var=pl_var_vec3("ahp_sat_peak"),.data=sat_peak,.dynamic=true},
        {.var=pl_var_vec3("ahp_sat_source_scale"),.data=sat_source_scale,.dynamic=true},
        {.var=pl_var_vec3("ahp_sat_target_scale"),.data=sat_target_scale,.dynamic=true},
        {.var=pl_var_vec4("ahp_authored"),.data=s->authored,.dynamic=true},
    };
    for(unsigned i=0;i<3;++i) vars[i].var.dim_a=2;
    vars[sizeof(vars)/sizeof(vars[0])-1].var.dim_a=3;
    pl_dispatch_reset_frame(s->dispatch);
    pl_shader sh=pl_dispatch_begin(s->dispatch);
    if (!pl_shader_custom(sh,&(struct pl_custom_shader){
        .description="Ambient HDR10+ spatial source",.input=PL_SHADER_SIG_NONE,
        .output=PL_SHADER_SIG_COLOR,
        .body=raw ?
            "ivec2 pos=clamp(ivec2(ahp_uv*ahp_size),ivec2(0),ivec2(ahp_size)-1);"
            "if(ahp_flipped!=0) pos.y=int(ahp_size.y)-1-pos.y;"
            "color=texelFetch(ahp_input,pos,0).gbra;" :
            "ivec2 pos=clamp(ivec2(ahp_uv*ahp_size),ivec2(0),ivec2(ahp_size)-1);"
            "if(ahp_flipped!=0) pos.y=int(ahp_size.y)-1-pos.y;"
            "color=texelFetch(ahp_input,pos,0);",
        .descriptors=desc,.num_descriptors=2,.variables=vars,.num_variables=sizeof(vars)/sizeof(vars[0]),
        .vertex_attribs=&va,.num_vertex_attribs=1,.output_w=w,.output_h=h,
    })) goto fail;
    if(raw){
        // Native sampler delivered Cr/Y/Cb; swizzle and apply the existing
        // BT.2020 NCL range/matrix conversion exactly once, in this prepass.
        struct pl_color_repr repr=img->repr;
        pl_shader_decode_color(sh,&repr,NULL);
    }
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
    img->planes[0]=(struct pl_plane){.texture=s->output,.components=3,
        .component_mapping={0,1,2,-1}};
    img->repr=pl_color_repr_rgb;
    img->color=mapped;
    return true;
fail:
    pl_dispatch_abort(s->dispatch,&sh);
    return false;
}

/* Explicit characterized HDR is an opt-in spatial contract. SDR retains
 * its documented global projection fallback. */
enum ambient_hdrplus_spatial_result {
    AMBIENT_HDRPLUS_SPATIAL_GLOBAL,
    AMBIENT_HDRPLUS_SPATIAL_MAPPED,
    AMBIENT_HDRPLUS_SPATIAL_REJECT,
};
static inline enum ambient_hdrplus_spatial_result ambient_hdrplus_spatial_apply(
    pl_gpu gpu, pl_log log, struct ambient_hdrplus_spatial *s,
    struct pl_frame *img, const struct pl_frame *target,
    const struct ambient_hdrplus *m, bool eligible, bool explicit_hdr)
{
    if(eligible && m && ambient_hdrplus_spatial_render(gpu,log,s,img,target,m))
        return AMBIENT_HDRPLUS_SPATIAL_MAPPED;
    return explicit_hdr ? AMBIENT_HDRPLUS_SPATIAL_REJECT : AMBIENT_HDRPLUS_SPATIAL_GLOBAL;
}
