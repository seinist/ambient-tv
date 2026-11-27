#pragma once
#include "dovi_fel_nlq.h"
#include "ambient_dovi_parser.h"
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
    bool mapped, reference_codes;
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
    p->mapped=false;p->reference_codes=false;
    return true;
}

/* ETSI GS CCM 001 V1.1.1 Annex B.3 reference EL filter.
 * Separate integer vertical/horizontal passes preserve the mandated
 * intermediate rounding and 16-bit clipping. No CPU readback or expansion.
 * The source and destination remain 4:2:0; chroma reconstruction to the
 * packed RGB prepass grid is a distinct, existing approximation. */
static inline bool ambient_fel_filter_axis_extent(pl_gpu gpu,pl_dispatch dp,
    pl_tex input,pl_tex *output,bool vertical,int kind,int extent)
{
    if(!gpu||!dp||!input||!output||input==*output)return false;
    int w=input->params.w,h=input->params.h;
    if(w<=0||h<=0||w>1920||h>(vertical?1088:2176))return false;
    int full=2*(vertical?h:w);
    if(!extent)extent=full;
    if(extent!=full&&extent!=full-1)return false;
    pl_fmt fmt=pl_find_fmt(gpu,PL_FMT_UNORM,1,16,16,
        PL_FMT_CAP_SAMPLEABLE|PL_FMT_CAP_RENDERABLE);
    if(!fmt||fmt->component_depth[0]!=16||!pl_tex_recreate(gpu,output,
        pl_tex_params(.w=vertical?w:extent,.h=vertical?extent:h,.format=fmt,
            .sampleable=true,.renderable=true)))return false;
    const struct pl_shader_desc d={.desc={.name="fel_filter_input",.type=PL_DESC_SAMPLED_TEX},
        .binding={.object=input}};
    int axis=vertical?1:0;
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
            "if(fel_filter_chroma==2){int k=odd!=0?-1:1;"
            "sum=222*fel_filter_code(p)+65*fel_filter_code(p-ivec2(0,k))"
            "-12*fel_filter_code(p-ivec2(0,2*k))+2*fel_filter_code(p-ivec2(0,3*k))"
            "-25*fel_filter_code(p+ivec2(0,k))+4*fel_filter_code(p+ivec2(0,2*k));shift=8;"
            "}else if(fel_filter_chroma!=0){"
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

static inline bool ambient_fel_filter_axis_kind(pl_gpu gpu,pl_dispatch dp,
    pl_tex input,pl_tex *output,bool vertical,int kind)
{
    return ambient_fel_filter_axis_extent(gpu,dp,input,output,vertical,kind,0);
}
static inline bool ambient_fel_filter_axis(pl_gpu gpu,pl_dispatch dp,
    pl_tex input,pl_tex *output,bool vertical,bool luma)
{
    return ambient_fel_filter_axis_kind(gpu,dp,input,output,vertical,luma?0:1);
}

/* Produce a borrowed view of the expanded EL planes. Preserve visible
 * crop coordinates after doubling and never overwrite decoder textures.
 * Odd visible extents have ceil(W/2) native chroma samples. After 2x
 * filtering, discard only the extra right/bottom output sample, never
 * shift the origin or discard an input tap. */
static inline bool ambient_fel_reference_el(pl_gpu gpu,pl_dispatch dp,
    struct ambient_fel_planes *src,struct ambient_fel_planes *view)
{
    if(!gpu||!dp||!src||!view||src==view||!src->mapped||!src->tex[0]||
       src->x.ratio!=0.5f||src->y.ratio!=0.5f)return false;
    int w=src->tex[0]->params.w,h=src->tex[0]->params.h;
    pl_dispatch_reset_frame(dp);
    for(unsigned c=0;c<3;c++){
        if(!src->tex[c]||src->tex[c]->params.w!=(c?(w+1)/2:w)||
           src->tex[c]->params.h!=(c?(h+1)/2:h)||
           !ambient_fel_filter_axis_extent(gpu,dp,src->tex[c],&src->vertical[c],true,c?1:0,c?h:h*2)||
           !ambient_fel_filter_axis_extent(gpu,dp,src->vertical[c],&src->expanded[c],false,c?1:0,c?w:w*2))return false;
    }
    *view=(struct ambient_fel_planes){.mapped=true,.reference_codes=true,
        .chroma_x=src->chroma_x,.chroma_y=src->chroma_y,
        .x={1.f,src->x.offset*2.f},.y={1.f,src->y.offset*2.f}};
    for(unsigned c=0;c<3;c++)view->tex[c]=src->expanded[c];
    return true;
}

/* Input is reconstructed RPU-domain Y/Cb/Cr, with no second reshape. */
static inline bool ambient_fel_rgb_shader(pl_shader sh,const struct pl_dovi_metadata *reshape)
{
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
    }))return false;
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
    float plane_code_max=1023.f;
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
        // Annex B clips each pass to 16 bits; retain overshoots until NLQ.
        if(planes->reference_codes)plane_code_max=65535.f;
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
        {.var=pl_var_float("fel_plane_code_max"),.data=&plane_code_max,.dynamic=true},
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
                  "vec3 delta=clamp(ec,0.0,fel_plane_code_max)-fel_offset;"
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
        if(!ambient_fel_rgb_shader(sh,reshape))goto fail;
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

/* 96-bit two's-complement accumulator using core GLSL 32-bit operations.
 * Coefficients retain all 64 bits. Products are at most 64+20 bits, and
 * the sum of at most 22 terms fits in 96 bits even for hostile coefficients.
 * Uniforms use uvec2 words, never floating coefficient conversions. */
static const char ambient_fel_fixed_glsl[] =
    "uvec3 fadd(uvec3 a,uvec3 b){uint x=a.x+b.x;uint c=uint(x<a.x);"
    "uint t=a.y+b.y;uint y=t+c;return uvec3(x,y,a.z+b.z+uint(t<a.y)+uint(y<t));}"
    "uvec3 fneg(uvec3 a){return fadd(~a,uvec3(1,0,0));}"
    "uvec3 fmul(uvec2 a,uint b,bool sg){uint h0,l0,h1,l1;"
    "umulExtended(a.x,b,h0,l0);umulExtended(a.y,b,h1,l1);"
    "uint y=h0+l1;uint z=h1+uint(y<h0);"
    "if(sg&&(a.y&0x80000000u)!=0u)z-=b;return uvec3(l0,y,z);}"
    "bool fgreater(uvec3 a,uvec3 b){return a.z!=b.z?a.z>b.z:(a.y!=b.y?a.y>b.y:a.x>b.x);}"
    "uint fshift(uvec3 a,int sh,uint cap){"
    "if(sh<0){if(a.z!=0u||a.y!=0u||a.x>(cap>>uint(-sh)))return cap;return a.x<<uint(-sh);}"
    "if(sh==0)return a.z!=0u||a.y!=0u?cap:min(a.x,cap);"
    "uint lo;uint hi;"
    "if(sh<32){lo=(a.x>>uint(sh))|(a.y<<uint(32-sh));hi=(a.y>>uint(sh))|(a.z<<uint(32-sh));"
    "if((a.z>>uint(sh))!=0u||hi!=0u)return cap;}"
    "else if(sh==32){lo=a.y;if(a.z!=0u)return cap;}"
    "else{lo=(a.y>>uint(sh-32))|(a.z<<uint(64-sh));if((a.z>>uint(sh-32))!=0u)return cap;}"
    "return min(lo,cap);}"
    "uint fq20(uint a,uint b){uint h,l;umulExtended(a,b,h,l);return (h<<12)|(l>>20);}"
    "uint fpredict(uvec3 b,int c){int p=c*10;int count=int(fixed_pivots[p]);"
    "int seg=0;for(int j=1;j<count-1;j++){if(b[c]>=fixed_pivots[p+1+j])seg=j;}"
    "for(int j=0;j<3;j++){int k=j*10;b[j]=clamp(b[j],fixed_pivots[k+1],fixed_pivots[k+int(fixed_pivots[k])]);}"
    "int base=(c*8+seg)*22;uint mode=fixed_modes[c*8+seg];"
    "uvec3 acc=fmul(fixed_coef[base],1048576u,true);"
    "if(mode<=2u){acc=fadd(acc,fmul(fixed_coef[base+1],b[c]<<10,true));"
    "if(mode==2u)acc=fadd(acc,fmul(fixed_coef[base+2],b[c]*b[c],true));}"
    "else{uint t[7];t[0]=b.x<<10;t[1]=b.y<<10;t[2]=b.z<<10;"
    "t[3]=b.x*b.y;t[4]=b.x*b.z;t[5]=b.y*b.z;t[6]=fq20(t[3],t[2]);"
    "for(int j=0;j<7;j++){uint v=t[j];for(int k=0;k<int(mode)-2;k++){"
    "acc=fadd(acc,fmul(fixed_coef[base+1+k*7+j],v,true));v=fq20(v,t[j]);}}}"
    "if((acc.z&0x80000000u)!=0u)return 0u;return fshift(acc,fixed_denom+4,65535u);}"
    "int fresidual(uint e,int c){int d=int(e)-int(fixed_offsets[c]);if(d==0)return 0;"
    "int base=528+c*3;uvec3 a=fadd(fmul(fixed_coef[base],uint(2*abs(d)-1),false),fmul(fixed_coef[base+1],2u,false));"
    "uvec3 cap=fmul(fixed_coef[base+2],2u,false);if(fgreater(a,cap))a=cap;"
    "int sh=fixed_denom-15;"
    "if(d<0&&sh>0)a=fadd(a,uvec3((1u<<uint(sh))-1u,0,0));"
    "int v=int(fshift(a,sh,131072u));return d<0?-v:v;}"
    "uint freconstruct(uvec3 b,uint e,int c){int v=int(fpredict(b,c))+fresidual(e,c);"
    "return uint(clamp((v+8)>>4,0,4095));}";

enum { AMBIENT_FIXED_COEFS=537 };
struct ambient_fel_fixed_params {
    uint32_t coef[AMBIENT_FIXED_COEFS][2],pivots[30],modes[24],offsets[3];
    int denom;
};
static inline void ambient_fixed_word(uint32_t out[2],uint64_t v)
{
    out[0]=(uint32_t)v;out[1]=(uint32_t)(v>>32);
}
static inline bool ambient_fel_fixed_params_init(struct ambient_fel_fixed_params *p,
    const struct ambient_dovi_metadata *m)
{
    if(!p||!m||m->bl_bit_depth!=10||m->el_bit_depth!=10||m->vdr_bit_depth!=12||
       m->coef_log2_denom<13||m->coef_log2_denom>32)return false;
    *p=(struct ambient_fel_fixed_params){.denom=m->coef_log2_denom};
    for(unsigned c=0;c<3;c++){
        const struct ambient_dovi_curve *q=&m->curves[c];
        if(q->num_pivots<2||q->num_pivots>9||m->nlq_offset[c]>1023)return false;
        p->pivots[10*c]=q->num_pivots;
        for(unsigned j=0;j<q->num_pivots;j++){
            if(q->pivots[j]>1023||(j&&q->pivots[j]<=q->pivots[j-1]))return false;
            p->pivots[10*c+1+j]=q->pivots[j];
        }
        for(unsigned j=0;j+1<q->num_pivots;j++){
            unsigned base=(c*8+j)*22;
            if(q->mapping[j]==0){
                if(q->poly_order[j]<1||q->poly_order[j]>2)return false;
                p->modes[c*8+j]=q->poly_order[j];
                for(unsigned k=0;k<=q->poly_order[j];k++)ambient_fixed_word(p->coef[base+k],(uint64_t)q->poly_coef[j][k]);
            }else{
                if(c==0||q->mapping[j]!=1||q->mmr_order[j]<1||q->mmr_order[j]>3)return false;
                p->modes[c*8+j]=2+q->mmr_order[j];
                ambient_fixed_word(p->coef[base],(uint64_t)q->mmr_constant[j]);
                for(unsigned k=0;k<q->mmr_order[j];k++)for(unsigned l=0;l<7;l++)
                    ambient_fixed_word(p->coef[base+1+7*k+l],(uint64_t)q->mmr_coef[j][k][l]);
            }
        }
        p->offsets[c]=m->nlq_offset[c];
        ambient_fixed_word(p->coef[528+3*c],m->nlq_slope[c]);
        ambient_fixed_word(p->coef[529+3*c],m->nlq_threshold[c]);
        ambient_fixed_word(p->coef[530+3*c],m->nlq_maximum[c]);
    }
    return true;
}

/* Native 4:2:0 reconstruction: separate Y and Cb/Cr images in 12-bit
 * codeword units stored exactly in UNORM16, followed by display conversion.
 * The imported P010 sampler is RGB_IDENTITY + NEAREST, order Cr/Y/Cb.
 * Read chroma at the even native-grid position, never interpolate BL chroma
 * before MMR. ETSI 5.4.2.3.3 uses two individually rounded horizontal
 * [1,2,1] sums and a rounded vertical mean for the chroma predictor. */
struct ambient_fel_native420 {
    pl_tex y,uv;
    pl_tex bl_raw[3],bl_vertical[3],bl_expanded[3],bl_packed;
};
static inline void ambient_fel_native420_destroy(pl_gpu gpu,struct ambient_fel_native420 *s)
{
    pl_tex_destroy(gpu,&s->y);pl_tex_destroy(gpu,&s->uv);
    for(unsigned c=0;c<3;c++){
        pl_tex_destroy(gpu,&s->bl_raw[c]);pl_tex_destroy(gpu,&s->bl_vertical[c]);
        pl_tex_destroy(gpu,&s->bl_expanded[c]);
    }
    pl_tex_destroy(gpu,&s->bl_packed);
}
/* Annex B.2, signalled BL factor-two resampling. Operate on native grids
 * before MMR, not on a pre-interpolated RGB or chroma image. */
static inline bool ambient_fel_reference_bl(pl_gpu gpu,pl_dispatch dp,
    struct ambient_fel_native420 *s,pl_tex bl)
{
    if(!gpu||!dp||!s||!bl||!bl->params.sampleable||bl==s->bl_packed)return false;
    int w=bl->params.w,h=bl->params.h;
    if(w<=0||h<=0||w>1920||h>1088)return false;
    pl_fmt r16=pl_find_fmt(gpu,PL_FMT_UNORM,1,16,16,
        PL_FMT_CAP_SAMPLEABLE|PL_FMT_CAP_RENDERABLE);
    if(!r16||r16->component_depth[0]!=16)return false;
    const struct pl_shader_desc d={.desc={.name="bl_unpack",.type=PL_DESC_SAMPLED_TEX},.binding={.object=bl}};
    pl_dispatch_reset_frame(dp);
    for(unsigned c=0;c<3;c++){
        if(!pl_tex_recreate(gpu,&s->bl_raw[c],pl_tex_params(.w=c?(w+1)/2:w,.h=c?(h+1)/2:h,
            .format=r16,.sampleable=true,.renderable=true)))return false;
        pl_shader sh=pl_dispatch_begin(dp);if(!sh)return false;
        const char *body=c==0?
            "float v=texelFetch(bl_unpack,ivec2(gl_FragCoord.xy),0).g; color=vec4(floor(v*1023.0+0.5)/65535.0,0,0,1);":
            c==1?
            "float v=texelFetch(bl_unpack,ivec2(gl_FragCoord.xy)*2,0).b; color=vec4(floor(v*1023.0+0.5)/65535.0,0,0,1);":
            "float v=texelFetch(bl_unpack,ivec2(gl_FragCoord.xy)*2,0).r; color=vec4(floor(v*1023.0+0.5)/65535.0,0,0,1);";
        if(!pl_shader_custom(sh,&(struct pl_custom_shader){
            .input=PL_SHADER_SIG_NONE,.output=PL_SHADER_SIG_COLOR,
            .descriptors=&d,.num_descriptors=1,.body=body,
        })){pl_dispatch_abort(dp,&sh);return false;}
        if(!pl_dispatch_finish(dp,pl_dispatch_params(.shader=&sh,.target=s->bl_raw[c]))||
           !ambient_fel_filter_axis_extent(gpu,dp,s->bl_raw[c],&s->bl_vertical[c],true,2,c?h:h*2)||
           !ambient_fel_filter_axis_extent(gpu,dp,s->bl_vertical[c],&s->bl_expanded[c],false,2,c?w:w*2))return false;
    }
    // Preserve the full integer code range, including the filter's positive
    // overshoot, until the per-component pivot clamp in prediction.
    pl_fmt fmt=pl_find_fmt(gpu,PL_FMT_FLOAT,4,32,32,PL_FMT_CAP_SAMPLEABLE|PL_FMT_CAP_RENDERABLE);
    if(!fmt||!pl_tex_recreate(gpu,&s->bl_packed,pl_tex_params(.w=w*2,.h=h*2,.format=fmt,
        .sampleable=true,.renderable=true)))return false;
    const struct pl_shader_desc p[]={
        {.desc={.name="bl_y",.type=PL_DESC_SAMPLED_TEX},.binding={.object=s->bl_expanded[0]}},
        {.desc={.name="bl_u",.type=PL_DESC_SAMPLED_TEX},.binding={.object=s->bl_expanded[1]}},
        {.desc={.name="bl_v",.type=PL_DESC_SAMPLED_TEX},.binding={.object=s->bl_expanded[2]}},
    };
    pl_shader sh=pl_dispatch_begin(dp);if(!sh)return false;
    if(!pl_shader_custom(sh,&(struct pl_custom_shader){
        .input=PL_SHADER_SIG_NONE,.output=PL_SHADER_SIG_COLOR,.descriptors=p,.num_descriptors=3,
        .body="ivec2 q=ivec2(gl_FragCoord.xy);"
            "float y=floor(texelFetch(bl_y,q,0).r*65535.0+0.5);"
            "float u=floor(texelFetch(bl_u,q/2,0).r*65535.0+0.5);"
            "float v=floor(texelFetch(bl_v,q/2,0).r*65535.0+0.5);"
            "color=vec4(vec3(v,y,u)/1023.0,1.0);",
    })){pl_dispatch_abort(dp,&sh);return false;}
    return pl_dispatch_finish(dp,pl_dispatch_params(.shader=&sh,.target=s->bl_packed));
}
static inline bool ambient_fel_native_grid(pl_gpu gpu,pl_dispatch dp,
    pl_tex bl,pl_tex *out,const struct ambient_fel_fixed_params *fixed,
    const struct ambient_fel_nlq *n,const struct ambient_fel_planes *el,bool chroma)
{
    if(!gpu||!dp||!bl||!out||!fixed||!ambient_fel_nlq_valid(n)||bl==*out||
       !bl->params.sampleable||bl->params.w<=0||bl->params.h<=0)return false;
    bool zero=ambient_fel_nlq_zero(n);
    if(!zero&&(!el||!el->tex[0]||!el->tex[1]||!el->tex[2]||!el->mapped||
       el->x.ratio!=1.f||el->y.ratio!=1.f||
       !ambient_fel_axis_bounds(bl->params.w,el->tex[0]->params.w,el->x)||
       !ambient_fel_axis_bounds(bl->params.h,el->tex[0]->params.h,el->y)||
       ((unsigned)el->x.offset&1u)||((unsigned)el->y.offset&1u)))return false;
    unsigned w=bl->params.w,h=bl->params.h;
    if(chroma){w=(w+1)/2;h=(h+1)/2;}
    unsigned components=chroma?2:1;
    pl_fmt fmt=pl_find_fmt(gpu,PL_FMT_UNORM,components,16,16,
        PL_FMT_CAP_SAMPLEABLE|PL_FMT_CAP_RENDERABLE);
    if(!fmt)fmt=pl_find_fmt(gpu,PL_FMT_UNORM,4,16,16,
        PL_FMT_CAP_SAMPLEABLE|PL_FMT_CAP_RENDERABLE);
    if(!fmt||fmt->component_depth[0]!=16||
       (chroma&&fmt->component_depth[1]!=16)||
       !pl_tex_recreate(gpu,out,pl_tex_params(.w=w,.h=h,.format=fmt,
           .sampleable=true,.renderable=true)))return false;
    const struct pl_shader_desc b={.desc={.name="native_bl",.type=PL_DESC_SAMPLED_TEX},
        .binding={.object=bl}};
    pl_shader sh=pl_dispatch_begin(dp);if(!sh)return false;
    if(!pl_shader_custom(sh,&(struct pl_custom_shader){
        .input=PL_SHADER_SIG_NONE,.output=PL_SHADER_SIG_COLOR,
        .descriptors=&b,.num_descriptors=1,
        .header="vec3 native_code(ivec2 p){"
            "return floor(texelFetch(native_bl,clamp(p,ivec2(0),textureSize(native_bl,0)-ivec2(1)),0).gbr*1023.0+0.5);}",
        .body=chroma?
            "ivec2 p=ivec2(gl_FragCoord.xy)*2;"
            "int a=(int(native_code(p-ivec2(1,0)).x)+2*int(native_code(p).x)+int(native_code(p+ivec2(1,0)).x)+2)>>2;"
            "p.y+=1;"
            "int b=(int(native_code(p-ivec2(1,0)).x)+2*int(native_code(p).x)+int(native_code(p+ivec2(1,0)).x)+2)>>2;"
            "vec3 v=native_code(p-ivec2(0,1));"
            "color=vec4(float((a+b+1)>>1),v.yz,1024.0)/1024.0;":
            "vec3 v=native_code(ivec2(gl_FragCoord.xy));color=vec4(v/1024.0,1.0);",
    }))goto fail;
    const struct pl_shader_desc ed[]={
        {.desc={.name="native_ey",.type=PL_DESC_SAMPLED_TEX},.binding={.object=zero?bl:el->tex[0]}},
        {.desc={.name="native_eu",.type=PL_DESC_SAMPLED_TEX},.binding={.object=zero?bl:el->tex[1]}},
        {.desc={.name="native_ev",.type=PL_DESC_SAMPLED_TEX},.binding={.object=zero?bl:el->tex[2]}},
    };
    float origin[2]={zero?0:el->x.offset,zero?0:el->y.offset};
    struct pl_var cv=pl_var_uvec2("fixed_coef");cv.dim_a=AMBIENT_FIXED_COEFS;
    struct pl_var pv=pl_var_uint("fixed_pivots");pv.dim_a=30;
    struct pl_var mv=pl_var_uint("fixed_modes");mv.dim_a=24;
    const struct pl_shader_var vars[]={
        {.var=cv,.data=fixed->coef,.dynamic=true},
        {.var=pv,.data=fixed->pivots,.dynamic=true},
        {.var=mv,.data=fixed->modes,.dynamic=true},
        {.var=pl_var_uvec3("fixed_offsets"),.data=fixed->offsets,.dynamic=true},
        {.var=pl_var_int("fixed_denom"),.data=&fixed->denom,.dynamic=true},
        {.var=pl_var_vec2("native_origin"),.data=origin,.dynamic=true},
    };
    if(!pl_shader_custom(sh,&(struct pl_custom_shader){
        .input=PL_SHADER_SIG_COLOR,.output=PL_SHADER_SIG_COLOR,
        .descriptors=ed,.num_descriptors=3,.variables=vars,.num_variables=6,
        .header=ambient_fel_fixed_glsl,
        .body=chroma?
            (zero?
              "uvec3 b=uvec3(floor(color.rgb*1024.0+0.5));"
              "uvec2 q=uvec2(freconstruct(b,fixed_offsets.y,1),freconstruct(b,fixed_offsets.z,2));color=vec4(vec2(q)/65535.0,0.0,1.0);":
              "uvec3 b=uvec3(floor(color.rgb*1024.0+0.5));ivec2 p=ivec2(gl_FragCoord.xy)+ivec2(native_origin)/2;"
              "uint u=uint(floor(texelFetch(native_eu,p,0).r*65535.0+0.5));"
              "uint v=uint(floor(texelFetch(native_ev,p,0).r*65535.0+0.5));"
              "uvec2 q=uvec2(freconstruct(b,u,1),freconstruct(b,v,2));color=vec4(vec2(q)/65535.0,0.0,1.0);"):
            (zero?
              "uvec3 b=uvec3(floor(color.rgb*1024.0+0.5));"
              "color=vec4(float(freconstruct(b,fixed_offsets.x,0))/65535.0,0.0,0.0,1.0);":
              "uvec3 b=uvec3(floor(color.rgb*1024.0+0.5));ivec2 p=ivec2(gl_FragCoord.xy)+ivec2(native_origin);"
              "uint y=uint(floor(texelFetch(native_ey,p,0).r*65535.0+0.5));"
              "color=vec4(float(freconstruct(b,y,0))/65535.0,0.0,0.0,1.0);"),
    }))goto fail;
    return pl_dispatch_finish(dp,pl_dispatch_params(.shader=&sh,.target=*out));
fail:
    pl_dispatch_abort(dp,&sh);return false;
}

static inline bool ambient_fel_native420_output(pl_dispatch dp,
    const struct ambient_fel_native420 *s,pl_tex output,
    const struct pl_dovi_metadata *reshape)
{
    if(!dp||!s||!s->y||!s->uv||!output||output==s->y||output==s->uv||
       output->params.w!=s->y->params.w||output->params.h!=s->y->params.h)return false;
    const struct pl_shader_desc d[]={
        {.desc={.name="native_y",.type=PL_DESC_SAMPLED_TEX},.binding={.object=s->y}},
        {.desc={.name="native_uv",.type=PL_DESC_SAMPLED_TEX},.binding={.object=s->uv}},
    };
    pl_shader sh=pl_dispatch_begin(dp);if(!sh)return false;
    // Display-side 4:2:0 to 4:4:4: Annex C four-tap reference kernel.
    // Vertical intermediate retains Q6 precision, signed overshoot;
    // horizontal pass rounds once and clips to the 12-bit display signal.
    // Annex C describes BT.1886 conversion; using its kernel for PQ here
    // is an explicit display policy, not a normative Dolby PQ requirement.
    if(!pl_shader_custom(sh,&(struct pl_custom_shader){
        .input=PL_SHADER_SIG_NONE,.output=PL_SHADER_SIG_COLOR,
        .descriptors=d,.num_descriptors=2,
        .header="ivec2 native_uv_at(ivec2 p){return ivec2(floor(texelFetch(native_uv,clamp(p,ivec2(0),textureSize(native_uv,0)-ivec2(1)),0).rg*65535.0+0.5));}"
            "ivec2 native_v(ivec2 p,int odd){if(odd==0)return 64*native_uv_at(p);"
            "return -4*native_uv_at(p-ivec2(0,1))+36*native_uv_at(p)+36*native_uv_at(p+ivec2(0,1))-4*native_uv_at(p+ivec2(0,2));}",
        .body="ivec2 p=ivec2(gl_FragCoord.xy),i=p/2;int odd=p.y&1;"
            "ivec2 uvq;if((p.x&1)==0)uvq=(native_v(i,odd)+ivec2(32))>>6;"
            "else uvq=(-4*native_v(i-ivec2(1,0),odd)+36*native_v(i,odd)+36*native_v(i+ivec2(1,0),odd)-4*native_v(i+ivec2(2,0),odd)+ivec2(2048))>>12;"
            "vec2 uv=vec2(clamp(uvq,ivec2(0),ivec2(4095)));"
            "float y=floor(texelFetch(native_y,ivec2(gl_FragCoord.xy),0).r*65535.0+0.5);"
            "color=vec4(vec3(y,uv)/4096.0,1.0);",
    })||(reshape&&!ambient_fel_rgb_shader(sh,reshape))){
        pl_dispatch_abort(dp,&sh);return false;
    }
    return pl_dispatch_finish(dp,pl_dispatch_params(.shader=&sh,.target=output));
}

static inline bool ambient_fel_native420_compose(pl_gpu gpu,pl_dispatch dp,
    struct ambient_fel_native420 *s,pl_tex bl,pl_tex output,
    const struct pl_dovi_metadata *reshape,const struct ambient_fel_nlq *n,
    const struct ambient_fel_planes *el,const struct ambient_dovi_metadata *raw)
{
    struct ambient_fel_fixed_params fixed;
    if(!ambient_fel_fixed_params_init(&fixed,raw))return false;
    if(!gpu||!dp||!s||!bl||!output||!reshape||output==bl||
       output->params.w!=bl->params.w||output->params.h!=bl->params.h)return false;
    pl_dispatch_reset_frame(dp);
    if(!ambient_fel_native_grid(gpu,dp,bl,&s->y,&fixed,n,el,false)||
       !ambient_fel_native_grid(gpu,dp,bl,&s->uv,&fixed,n,el,true))return false;
    return ambient_fel_native420_output(dp,s,output,reshape);
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
