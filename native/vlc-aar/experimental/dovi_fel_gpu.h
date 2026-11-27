#pragma once
#include "dovi_fel_nlq.h"
#include "dovi_fel_geometry.h"
#include <libplacebo/dispatch.h>
#include <libplacebo/shaders/custom.h>
#include <libplacebo/shaders/colorspace.h>
#include <stddef.h>
/* Planar software decoder samples are unshifted little-endian 10-bit values.
 * Use three reusable R16 UNORM textures rather than CPU upscaling or copying
 * full-resolution packed residuals. Offsets are in EL luma pixels. */
struct ambient_fel_planes {
    pl_tex tex[3];
    float chroma_x,chroma_y;
};
static inline void ambient_fel_planes_destroy(pl_gpu gpu,struct ambient_fel_planes *p)
{
    if (!p) return;
    for(unsigned i=0;i<3;i++) pl_tex_destroy(gpu,&p->tex[i]);
}
static inline bool ambient_fel_planes_upload(pl_gpu gpu,struct ambient_fel_planes *p,
    unsigned w,unsigned h,const uint16_t *const data[3],const size_t pitch[3],
    float chroma_x,float chroma_y)
{
    if(!gpu||!p||!data||!pitch||!w||!h||w>1920||h>1088||
       !isfinite(chroma_x)||!isfinite(chroma_y)||fabsf(chroma_x)>0.5f||fabsf(chroma_y)>0.5f)
        return false;
    pl_fmt fmt=pl_find_fmt(gpu,PL_FMT_UNORM,1,16,16,PL_FMT_CAP_SAMPLEABLE);
    if(!fmt||fmt->component_depth[0]!=16||fmt->texel_size!=2) return false;
    for(unsigned i=0;i<3;i++){
        unsigned pw=i?(w+1)/2:w,ph=i?(h+1)/2:h;
        if(!data[i]||pitch[i]<pw*2u||pitch[i]%fmt->texel_align) return false;
        if(!pl_tex_recreate(gpu,&p->tex[i],pl_tex_params(.w=pw,.h=ph,
            .format=fmt,.sampleable=true,.host_writable=true)))return false;
        if(!pl_tex_upload(gpu,pl_tex_transfer_params(.tex=p->tex[i],
            .ptr=(void *)data[i],.row_pitch=pitch[i])))return false;
    }
    p->chroma_x=chroma_x;p->chroma_y=chroma_y;
    return true;
}

/* GPU composition of aligned packed Y/Cb/Cr BL and EL textures.
 * Input must already be chroma-aligned, normalized raw codewords. Output is
 * reconstructed codewords, NOT RGB; a downstream DV matrix stage is required.
 * decode_rgb optionally applies the Dolby matrix and returns nonlinear PQ RGB.
 * No claim of bit-exact reference-decoder equivalence. */
static inline bool ambient_fel_compose_ex(pl_gpu gpu, pl_dispatch dispatch,
    pl_tex bl, pl_tex el, pl_tex output, const struct pl_dovi_metadata *reshape,
    const struct ambient_fel_nlq *n, bool packed_cr_y_cb, bool decode_rgb,
    const struct ambient_fel_planes *planes)
{
    const bool zero=ambient_fel_nlq_zero(n);
    if (!gpu || !dispatch || !bl || (!el && !planes && !zero) || (el && planes) || !output || !reshape ||
        !ambient_fel_nlq_valid(n) || output==bl || output==el ||
        !bl->params.sampleable || !output->params.renderable ||
        bl->params.w<=0 || bl->params.h<=0 ||
        (el && (!el->params.sampleable || bl->params.w!=el->params.w || bl->params.h!=el->params.h)) ||
        output->params.w!=bl->params.w ||
        output->params.h!=bl->params.h)
        return false;
    for (unsigned i=0;i<3;i++) {
        unsigned count=reshape->comp[i].num_pivots;
        if (count && (count<2 || count>9)) return false;
        const struct pl_reshape_data *c=&reshape->comp[i];
        for (unsigned j=0;j<count;j++) {
            if (!(c->pivots[j]>=0.f && c->pivots[j]<=1.f) ||
                (j && !(c->pivots[j]>c->pivots[j-1]))) return false;
            if (j+1==count) continue;
            if(c->method[j]==0){
                for(unsigned k=0;k<3;k++)if(!isfinite(c->poly_coeffs[j][k]))return false;
            }else if(c->method[j]==1){
                if(c->mmr_order[j]<1||c->mmr_order[j]>3||!isfinite(c->mmr_constant[j]))return false;
                for(unsigned k=0;k<c->mmr_order[j];k++)for(unsigned l=0;l<7;l++)
                    if(!isfinite(c->mmr_coeffs[j][k][l]))return false;
            }else return false;
        }
    }
    float layer_ratio[2]={1.f,1.f},layer_shift[2]={0.f,0.f},chroma_shift[2]={0.f,0.f};
    int luma_direct=0;
    if(planes){
        if(!planes->tex[0]||!planes->tex[1]||!planes->tex[2]||n->el_bits!=10||
            !isfinite(planes->chroma_x)||!isfinite(planes->chroma_y)||
            fabsf(planes->chroma_x)>0.5f||fabsf(planes->chroma_y)>0.5f)return false;
        const int ew=planes->tex[0]->params.w,eh=planes->tex[0]->params.h;
        if(ew<=0||eh<=0||
            !ambient_fel_layer_ratio(bl->params.w,ew)||
            !ambient_fel_layer_ratio(bl->params.h,eh)) return false;
        for(unsigned i=0;i<3;i++){
            pl_tex t=planes->tex[i];
            if(t==output||!t->params.sampleable||t->params.format->type!=PL_FMT_UNORM||
                t->params.format->component_depth[0]!=16||
                t->params.w!=(i?(ew+1)/2:ew)||t->params.h!=(i?(eh+1)/2:eh))return false;
        }
        // Layer siting: left horizontally, centered vertically. Chroma
        // siting is separately derived from the software decoder metadata.
        layer_ratio[0]=(float)ew/bl->params.w;layer_ratio[1]=(float)eh/bl->params.h;
        luma_direct=bl->params.w==ew&&bl->params.h==eh;
        layer_shift[0]=layer_ratio[0]<1.f?-0.5f:0.f;
        chroma_shift[0]=planes->chroma_x;chroma_shift[1]=planes->chroma_y;
    }
    const struct pl_shader_desc desc[2]={
        {.desc={.name="fel_bl",.type=PL_DESC_SAMPLED_TEX},.binding={.object=bl}},
        {.desc={.name="fel_el",.type=PL_DESC_SAMPLED_TEX},.binding={.object=el}},
    };
    float offset[3],slope[3],threshold[3],maximum[3];
    float codes=(float)((1u<<n->el_bits)-1);
    for(unsigned i=0;i<3;i++) {
        offset[i]=n->offset[i];
        slope[i]=(float)ldexp((double)n->slope[i],-(int)n->coef_log2_denom);
        threshold[i]=(float)ldexp((double)n->threshold[i],-(int)n->coef_log2_denom);
        maximum[i]=(float)ldexp((double)n->maximum[i],-(int)n->coef_log2_denom);
    }
    const struct pl_shader_var vars[]={
        {.var=pl_var_vec3("fel_offset"),.data=offset,.dynamic=true},
        {.var=pl_var_vec3("fel_slope"),.data=slope,.dynamic=true},
        {.var=pl_var_vec3("fel_threshold"),.data=threshold,.dynamic=true},
        {.var=pl_var_vec3("fel_maximum"),.data=maximum,.dynamic=true},
        {.var=pl_var_float("fel_codes"),.data=&codes,.dynamic=true},
    };
    const struct pl_shader_var plane_vars[]={
        {.var=pl_var_vec3("fel_offset"),.data=offset,.dynamic=true},
        {.var=pl_var_vec3("fel_slope"),.data=slope,.dynamic=true},
        {.var=pl_var_vec3("fel_threshold"),.data=threshold,.dynamic=true},
        {.var=pl_var_vec3("fel_maximum"),.data=maximum,.dynamic=true},
        {.var=pl_var_vec2("fel_layer_ratio"),.data=layer_ratio,.dynamic=true},
        {.var=pl_var_vec2("fel_layer_shift"),.data=layer_shift,.dynamic=true},
        {.var=pl_var_vec2("fel_chroma_shift"),.data=chroma_shift,.dynamic=true},
        {.var=pl_var_int("fel_luma_direct"),.data=&luma_direct,.dynamic=true},
    };
    // This dispatch is dedicated to the FEL prepass, one invocation per
    // frame. Retire per-frame temporary state and reuse compiled shaders.
    pl_dispatch_reset_frame(dispatch);
    pl_shader sh=pl_dispatch_begin(dispatch);
    if (!sh) return false;
    if (!pl_shader_custom(sh,&(struct pl_custom_shader){
        .input=PL_SHADER_SIG_NONE,.output=PL_SHADER_SIG_COLOR,
        .descriptors=desc,.num_descriptors=1,
        .body=packed_cr_y_cb ? "color=texelFetch(fel_bl,ivec2(gl_FragCoord.xy),0).gbra;" :
            "color=texelFetch(fel_bl,ivec2(gl_FragCoord.xy),0);",
    })) goto fail;
    pl_shader_dovi_reshape(sh,reshape);
    // Canonical MEL and any provably zero residual need no EL sampling.
    if(zero) {
        // Reshaping already clamps each component to its RPU pivot bounds.
    }else if(planes){
        const struct pl_shader_desc pd[]={
            {.desc={.name="fel_y",.type=PL_DESC_SAMPLED_TEX},.binding={.object=planes->tex[0]}},
            {.desc={.name="fel_u",.type=PL_DESC_SAMPLED_TEX},.binding={.object=planes->tex[1]}},
            {.desc={.name="fel_v",.type=PL_DESC_SAMPLED_TEX},.binding={.object=planes->tex[2]}},
        };
        if(!pl_shader_custom(sh,&(struct pl_custom_shader){
            .input=PL_SHADER_SIG_COLOR,.output=PL_SHADER_SIG_COLOR,
            .descriptors=pd,.num_descriptors=3,
            .variables=plane_vars,.num_variables=sizeof(plane_vars)/sizeof(plane_vars[0]),
            // Coordinates use texel centers; manual bilinear interpolation
            // also works when R16 linear filtering is unavailable.
            .header="float fel_code(sampler2D t,ivec2 p){"
                    "return floor(texelFetch(t,p,0).r*65535.0+0.5);}"
                    "float fel_sample(sampler2D t,vec2 p){"
                    "vec2 q=p-vec2(0.5);ivec2 i=ivec2(floor(q));vec2 f=fract(q);"
                    "ivec2 hi=textureSize(t,0)-ivec2(1);"
                    "float a=fel_code(t,clamp(i,ivec2(0),hi));"
                    "float b=fel_code(t,clamp(i+ivec2(1,0),ivec2(0),hi));"
                    "float c=fel_code(t,clamp(i+ivec2(0,1),ivec2(0),hi));"
                    "float d=fel_code(t,clamp(i+ivec2(1,1),ivec2(0),hi));"
                    "return mix(mix(a,b,f.x),mix(c,d,f.x),f.y);}",
            .body="vec2 ep=(gl_FragCoord.xy-fel_layer_shift)*fel_layer_ratio;"
                  "vec2 cp=(ep-fel_chroma_shift)*0.5;"
                  "float ey=fel_luma_direct!=0?fel_code(fel_y,ivec2(gl_FragCoord.xy)):fel_sample(fel_y,ep);"
                  "vec3 ec=vec3(ey,fel_sample(fel_u,cp),fel_sample(fel_v,cp));"
                  "vec3 delta=clamp(ec,0.0,1023.0)-fel_offset;"
                  "vec3 rr=sign(delta)*floor(abs(delta)+0.5);"
                  "vec3 r=sign(rr)*((abs(rr)-0.5)*fel_slope+fel_threshold);"
                  "color.rgb=clamp(color.rgb+clamp(r,-fel_maximum,fel_maximum),0.0,1.0);",
        }))goto fail;
    }else if (!pl_shader_custom(sh,&(struct pl_custom_shader){
        .input=PL_SHADER_SIG_COLOR,.output=PL_SHADER_SIG_COLOR,
        .descriptors=&desc[1],.num_descriptors=1,
        .variables=vars,.num_variables=sizeof(vars)/sizeof(vars[0]),
        .body="vec3 delta=clamp(texelFetch(fel_el,ivec2(gl_FragCoord.xy),0).rgb,0.0,1.0)*fel_codes-fel_offset;"
              "vec3 rr=sign(delta)*floor(abs(delta)+0.5);"
              "vec3 r=sign(rr)*((abs(rr)-0.5)*fel_slope+fel_threshold);"
              "color.rgb=clamp(color.rgb+clamp(r,-fel_maximum,fel_maximum),0.0,1.0);",
    })) goto fail;
    if (decode_rgb) {
        // The reconstructed codewords already passed the RPU reshape.
        // Reuse the pinned Dolby matrix/PQ conversion with the curves cleared,
        // rather than applying the reshape twice or treating EL as RGB.
        struct pl_dovi_metadata matrix=*reshape;
        for(unsigned i=0;i<3;i++) matrix.comp[i].num_pivots=0;
        struct pl_color_repr repr={
            .sys=PL_COLOR_SYSTEM_DOLBYVISION,.levels=PL_COLOR_LEVELS_LIMITED,
            .bits={.sample_depth=10,.color_depth=10},.dovi=&matrix,
        };
        pl_shader_decode_color(sh,&repr,NULL);
        // Keep the resulting PQ RGB signal for the normal color mapping pass.
        return pl_dispatch_finish(dispatch,pl_dispatch_params(.shader=&sh,.target=output));
    }
    return pl_dispatch_finish(dispatch,pl_dispatch_params(.shader=&sh,.target=output));
fail:
    pl_dispatch_abort(dispatch,&sh);
    return false;
}

static inline bool ambient_fel_compose(pl_gpu gpu, pl_dispatch dispatch,
    pl_tex bl, pl_tex el, pl_tex output, const struct pl_dovi_metadata *reshape,
    const struct ambient_fel_nlq *n)
{
    return ambient_fel_compose_ex(gpu,dispatch,bl,el,output,reshape,n,false,false,NULL);
}
