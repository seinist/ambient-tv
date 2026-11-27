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
    pl_tex vertical[3], expanded[3];
    float chroma_x,chroma_y;
    bool mapped;
    struct ambient_fel_axis_map x,y;
};
static inline void ambient_fel_planes_destroy(pl_gpu gpu,struct ambient_fel_planes *p)
{
    if (!p) return;
    for(unsigned i=0;i<3;i++){
        pl_tex_destroy(gpu,&p->tex[i]);
        pl_tex_destroy(gpu,&p->vertical[i]);
        pl_tex_destroy(gpu,&p->expanded[i]);
    }
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
    p->mapped=false;
    return true;
}

/* ETSI GS CCM 001 V1.1.1 Annex B.3 reference EL filter.
 * Separate integer vertical/horizontal passes preserve the mandated
 * intermediate rounding and 16-bit clipping. No CPU readback or expansion.
 * The source and destination remain 4:2:0; chroma reconstruction to the
 * packed RGB prepass grid is a distinct, existing approximation. */
static inline bool ambient_fel_filter_axis(pl_gpu gpu,pl_dispatch dp,
    pl_tex input,pl_tex *output,bool vertical,bool luma)
{
    if(!gpu||!dp||!input||!output||input==*output)return false;
    int w=input->params.w,h=input->params.h;
    if(w<=0||h<=0||w>3840||h>2160)return false;
    pl_fmt fmt=pl_find_fmt(gpu,PL_FMT_UNORM,1,16,16,
        PL_FMT_CAP_SAMPLEABLE|PL_FMT_CAP_RENDERABLE);
    if(!fmt||fmt->component_depth[0]!=16||!pl_tex_recreate(gpu,output,
        pl_tex_params(.w=vertical?w:w*2,.h=vertical?h*2:h,.format=fmt,
            .sampleable=true,.renderable=true)))return false;
    const struct pl_shader_desc d={.desc={.name="fel_filter_input",.type=PL_DESC_SAMPLED_TEX},
        .binding={.object=input}};
    int axis=vertical?1:0,kind=luma?0:1;
    const struct pl_shader_var vars[]={
        {.var=pl_var_int("fel_filter_vertical"),.data=&axis,.dynamic=true},
        {.var=pl_var_int("fel_filter_chroma"),.data=&kind,.dynamic=true},
    };
    pl_shader sh=pl_dispatch_begin(dp);
    if(!sh)return false;
    if(!pl_shader_custom(sh,&(struct pl_custom_shader){
        .input=PL_SHADER_SIG_NONE,.output=PL_SHADER_SIG_COLOR,
        .descriptors=&d,.num_descriptors=1,.variables=vars,.num_variables=2,
        .header="int fel_filter_code(ivec2 p){"
            "ivec2 hi=textureSize(fel_filter_input,0)-ivec2(1);"
            "return int(floor(texelFetch(fel_filter_input,clamp(p,ivec2(0),hi),0).r*65535.0+0.5));}",
        .body="ivec2 p=ivec2(gl_FragCoord.xy);int sum=0;int shift=0;"
            "if(fel_filter_vertical!=0){int odd=p.y&1;p.y/=2;"
            "if(fel_filter_chroma!=0){"
            "sum=192*fel_filter_code(p)+64*fel_filter_code(p+ivec2(0,odd!=0?1:-1));shift=8;"
            "}else{int k=odd!=0?1:-1;"
            "sum=111*fel_filter_code(p)+29*fel_filter_code(p+ivec2(0,k))"
            "-3*fel_filter_code(p+ivec2(0,2*k))-9*fel_filter_code(p-ivec2(0,k));shift=7;}"
            "}else{int odd=p.x&1;p.x/=2;"
            "if(odd==0){sum=fel_filter_code(p);}"
            "else{sum=2456*(fel_filter_code(p)+fel_filter_code(p+ivec2(1,0)))"
            "-524*(fel_filter_code(p-ivec2(1,0))+fel_filter_code(p+ivec2(2,0)))"
            "+94*(fel_filter_code(p-ivec2(2,0))+fel_filter_code(p+ivec2(3,0)))"
            "+22*(fel_filter_code(p-ivec2(3,0))+fel_filter_code(p+ivec2(4,0)));shift=12;}}"
            "if(shift>0)sum=max(sum+(1<<(shift-1)),0)>>shift;"
            "int code=clamp(sum,0,65535);"
            "color=vec4(float(code)/65535.0,0.0,0.0,1.0);",
    })){pl_dispatch_abort(dp,&sh);return false;}
    return pl_dispatch_finish(dp,pl_dispatch_params(.shader=&sh,.target=*output));
}

/* Produce a borrowed view of the expanded EL planes. Preserve visible
 * crop coordinates after doubling and never overwrite decoder textures.
 * For an odd visible EL extent, the doubled chroma plane can contain one
 * more sample than ceil(doubled luma / 2); reject that case rather than
 * silently changing the filter phase. */
static inline bool ambient_fel_reference_el(pl_gpu gpu,pl_dispatch dp,
    struct ambient_fel_planes *src,struct ambient_fel_planes *view)
{
    if(!src||!view||src==view||!src->mapped||!src->tex[0]||
       src->x.ratio!=0.5f||src->y.ratio!=0.5f||
       (src->tex[0]->params.w&1)||(src->tex[0]->params.h&1))return false;
    pl_dispatch_reset_frame(dp);
    for(unsigned c=0;c<3;c++){
        if(!ambient_fel_filter_axis(gpu,dp,src->tex[c],&src->vertical[c],true,c==0)||
           !ambient_fel_filter_axis(gpu,dp,src->vertical[c],&src->expanded[c],false,c==0))return false;
    }
    *view=(struct ambient_fel_planes){.mapped=true,
        .chroma_x=src->chroma_x,.chroma_y=src->chroma_y,
        .x={1.f,src->x.offset*2.f},.y={1.f,src->y.offset*2.f}};
    for(unsigned c=0;c<3;c++)view->tex[c]=src->expanded[c];
    return true;
}

/* GPU composition of aligned packed Y/Cb/Cr BL and EL textures.
 * Input must already be chroma-aligned, normalized raw codewords. Output is
 * reconstructed codewords, NOT RGB; a downstream DV matrix stage is required.
 * decode_rgb optionally applies the Dolby matrix and returns nonlinear PQ RGB.
 * No claim of bit-exact reference-decoder equivalence. */
static inline bool ambient_fel_compose_domain(pl_gpu gpu, pl_dispatch dispatch,
    pl_tex bl, pl_tex el, pl_tex output, const struct pl_dovi_metadata *reshape,
    const struct ambient_fel_nlq *n, bool packed_cr_y_cb, bool decode_rgb,
    const struct ambient_fel_planes *planes, bool rpu_domain)
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
    // The RGB path is also callable independently of the metadata bridge.
    // Reject malformed matrix inputs before emitting shader constants.
    if (decode_rgb) {
        for (unsigned i=0;i<3;i++) {
            if (!isfinite(reshape->nonlinear_offset[i])) return false;
            for (unsigned j=0;j<3;j++)
                if (!isfinite(reshape->nonlinear.m[i][j]) ||
                    !isfinite(reshape->linear.m[i][j])) return false;
        }
    }
    float layer_ratio[2]={1.f,1.f},layer_shift[2]={0.f,0.f},chroma_shift[2]={0.f,0.f};
    float layer_offset[2]={0.f,0.f};
    int luma_direct=0;
    if(planes){
        if(!planes->tex[0]||!planes->tex[1]||!planes->tex[2]||n->el_bits!=10||
            !isfinite(planes->chroma_x)||!isfinite(planes->chroma_y)||
            fabsf(planes->chroma_x)>0.5f||fabsf(planes->chroma_y)>0.5f)return false;
        const int ew=planes->tex[0]->params.w,eh=planes->tex[0]->params.h;
        if(ew<=0||eh<=0)return false;
        struct ambient_fel_axis_map x,y;
        if(planes->mapped){
            x=planes->x;y=planes->y;
            if(!ambient_fel_axis_bounds(bl->params.w,ew,x)||
               !ambient_fel_axis_bounds(bl->params.h,eh,y))return false;
        }else if(!ambient_fel_axis_mapping(bl->params.w,ew,0,0,0,0,&x)||
                 !ambient_fel_axis_mapping(bl->params.h,eh,0,0,0,0,&y))return false;
        for(unsigned i=0;i<3;i++){
            pl_tex t=planes->tex[i];
            if(t==output||!t->params.sampleable||t->params.format->type!=PL_FMT_UNORM||
                t->params.format->component_depth[0]!=16||
                t->params.w!=(i?(ew+1)/2:ew)||t->params.h!=(i?(eh+1)/2:eh))return false;
        }
        // Layer siting: left horizontally, centered vertically. Chroma
        // siting is separately derived from the software decoder metadata.
        layer_ratio[0]=x.ratio;layer_ratio[1]=y.ratio;
        layer_offset[0]=x.offset;layer_offset[1]=y.offset;
        luma_direct=x.ratio==1.f&&y.ratio==1.f&&x.offset==0.f&&y.offset==0.f;
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
        {.var=pl_var_vec2("fel_layer_offset"),.data=layer_offset,.dynamic=true},
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
    if(rpu_domain){
        // The native P010 sample is code/1023; RPU polynomial and MMR
        // coefficients use code/1024. Clamp every input component to its
        // own pivot interval BEFORE the cross-component MMR prediction.
        // Match the newer libplacebo 7.387.0 prediction domain without
        // altering the pinned library or unrelated single-layer paths.
        float lo[3]={0.f,0.f,0.f},hi[3]={1.f,1.f,1.f};
        for(unsigned i=0;i<3;i++)if(reshape->comp[i].num_pivots){
            lo[i]=reshape->comp[i].pivots[0];
            hi[i]=reshape->comp[i].pivots[reshape->comp[i].num_pivots-1];
        }
        const struct pl_shader_var bounds[]={
            {.var=pl_var_vec3("fel_pivot_lo"),.data=lo,.dynamic=true},
            {.var=pl_var_vec3("fel_pivot_hi"),.data=hi,.dynamic=true},
        };
        if(!pl_shader_custom(sh,&(struct pl_custom_shader){
            .input=PL_SHADER_SIG_COLOR,.output=PL_SHADER_SIG_COLOR,
            .variables=bounds,.num_variables=2,
            .body="color.rgb=clamp(color.rgb*(1023.0/1024.0),fel_pivot_lo,fel_pivot_hi);",
        }))goto fail;
    }
    if(rpu_domain){
        // v5 clamps the predicted OUTPUT to the first/last pivots;
        // v7 clamps to [0,1]. Inputs have already been clamped above.
        // Endpoint pivots do not participate in segment selection, so
        // widen only those endpoints in a local copy for the v5 shader.
        struct pl_dovi_metadata prediction=*reshape;
        for(unsigned i=0;i<3;i++)if(prediction.comp[i].num_pivots){
            prediction.comp[i].pivots[0]=0.f;
            prediction.comp[i].pivots[prediction.comp[i].num_pivots-1]=1.f;
        }
        pl_shader_dovi_reshape(sh,&prediction);
    }else pl_shader_dovi_reshape(sh,reshape);
    // Canonical MEL and any provably zero residual need no EL sampling.
    if(zero) {
        // Preserve the normal residual path's final signal clamp even
        // when an input component has no reshape curve.
        if(!pl_shader_custom(sh,&(struct pl_custom_shader){
            .input=PL_SHADER_SIG_COLOR,.output=PL_SHADER_SIG_COLOR,
            .body="color.rgb=clamp(color.rgb,0.0,1.0);",
        }))goto fail;
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
            .body="vec2 ep=(gl_FragCoord.xy-fel_layer_shift)*fel_layer_ratio+fel_layer_offset;"
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
    if (decode_rgb && rpu_domain) {
        // The RPU-domain codewords are already normalized by 2^bit_depth.
        // Apply the authored matrices and offsets exactly once, in that domain.
        // Near a gamut edge the difference of two linear-light components is
        // small; evaluating x = pow(PQ, 1/m2) and then c2 - c3*x loses
        // significant FP32 precision. Compute u = 1 - exp(log(PQ)/m2)
        // directly, using a short expm1 series around zero. Algebraically,
        // (x-c1)/(c2-c3*x) == ((1-c1)-u)/((c2-c3)+c3*u).
        float nonlinear[3][3],linear[3][3];
        for(unsigned i=0;i<3;i++)for(unsigned j=0;j<3;j++){
            nonlinear[j][i]=reshape->nonlinear.m[i][j];
            linear[j][i]=reshape->linear.m[i][j];
        }
        const struct pl_shader_var color_vars[]={
            {.var=pl_var_mat3("fel_nonlinear"),.data=nonlinear,.dynamic=true},
            {.var=pl_var_mat3("fel_linear"),.data=linear,.dynamic=true},
            {.var=pl_var_vec3("fel_color_offset"),.data=reshape->nonlinear_offset,.dynamic=true},
        };
        if(!pl_shader_custom(sh,&(struct pl_custom_shader){
            .input=PL_SHADER_SIG_COLOR,.output=PL_SHADER_SIG_COLOR,
            .variables=color_vars,.num_variables=sizeof(color_vars)/sizeof(color_vars[0]),
            .body="vec3 code=fel_nonlinear*(color.rgb-fel_color_offset);"
                  "vec3 t=log(max(code,vec3(1e-20)))*(32.0/2523.0);"
                  "vec3 u=-t*(1.0+t*(0.5+t*(1.0/6.0+t*(1.0/24.0+t*(1.0/120.0+t/720.0)))));"
                  "u=mix(1.0-exp(t),u,lessThanEqual(abs(t),vec3(0.125)));"
                  "vec3 light=pow(max(vec3(1.0-3424.0/4096.0)-u,0.0)/"
                  "max(vec3(21.0/128.0)+(2392.0/128.0)*u,vec3(1e-8)),vec3(16384.0/2610.0));"
                  "vec3 lms=fel_linear*light;"
                  "const mat3 hpe=mat3(3.06441879,-0.65612108,0.01736321,"
                  "-2.16597676,1.78554118,-0.04725154,0.10155818,-0.12943749,1.03004253);"
                  "vec3 p=pow(max(hpe*lms,0.0),vec3(2610.0/16384.0));"
                  "color.rgb=pow((vec3(3424.0/4096.0)+(2413.0/128.0)*p)/"
                  "(vec3(1.0)+(2392.0/128.0)*p),vec3(2523.0/32.0));",
        }))goto fail;
        return pl_dispatch_finish(dispatch,pl_dispatch_params(.shader=&sh,.target=output));
    }
    if (decode_rgb) {
        // The reconstructed codewords already passed the RPU reshape.
        // Reuse the pinned Dolby matrix/PQ conversion with the curves cleared,
        // rather than applying the reshape twice or treating EL as RGB.
        struct pl_dovi_metadata matrix=*reshape;
        for(unsigned i=0;i<3;i++){
            matrix.comp[i].num_pivots=0;
            // Legacy normalized-input callers retain the pinned matrix path.
        }
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

/* Low-level fixtures with already prediction-normalized BL input. Live
 * P010 callers must use ambient_fel_compose_domain(...,true). */
static inline bool ambient_fel_compose_ex(pl_gpu gpu, pl_dispatch dispatch,
    pl_tex bl, pl_tex el, pl_tex output, const struct pl_dovi_metadata *reshape,
    const struct ambient_fel_nlq *n, bool packed_cr_y_cb, bool decode_rgb,
    const struct ambient_fel_planes *planes)
{
    return ambient_fel_compose_domain(gpu,dispatch,bl,el,output,reshape,n,
        packed_cr_y_cb,decode_rgb,planes,false);
}

static inline bool ambient_fel_compose(pl_gpu gpu, pl_dispatch dispatch,
    pl_tex bl, pl_tex el, pl_tex output, const struct pl_dovi_metadata *reshape,
    const struct ambient_fel_nlq *n)
{
    return ambient_fel_compose_ex(gpu,dispatch,bl,el,output,reshape,n,false,false,NULL);
}
