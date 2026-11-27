#pragma once
#include "dovi_fel_nlq.h"
#include <libplacebo/dispatch.h>
#include <libplacebo/shaders/custom.h>
#include <libplacebo/shaders/colorspace.h>
/* GPU composition of aligned packed Y/Cb/Cr BL and EL textures.
 * Input must already be chroma-aligned, normalized raw codewords. Output is
 * reconstructed codewords, NOT RGB; a downstream DV matrix stage is required.
 * No claim of bit-exact reference decoder or complete playback integration. */
static inline bool ambient_fel_compose(pl_gpu gpu, pl_dispatch dispatch,
    pl_tex bl, pl_tex el, pl_tex output, const struct pl_dovi_metadata *reshape,
    const struct ambient_fel_nlq *n)
{
    if (!gpu || !dispatch || !bl || !el || !output || !reshape ||
        !ambient_fel_nlq_valid(n) || output==bl || output==el ||
        !bl->params.sampleable || !el->params.sampleable || !output->params.renderable ||
        bl->params.w<=0 || bl->params.h<=0 || bl->params.w!=el->params.w ||
        bl->params.h!=el->params.h || output->params.w!=bl->params.w ||
        output->params.h!=bl->params.h)
        return false;
    for (unsigned i=0;i<3;i++) {
        unsigned count=reshape->comp[i].num_pivots;
        if (count && (count<2 || count>9)) return false;
        for (unsigned j=1;j<count;j++)
            if (!(reshape->comp[i].pivots[j]>reshape->comp[i].pivots[j-1])) return false;
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
    pl_shader sh=pl_dispatch_begin(dispatch);
    if (!sh) return false;
    if (!pl_shader_custom(sh,&(struct pl_custom_shader){
        .input=PL_SHADER_SIG_NONE,.output=PL_SHADER_SIG_COLOR,
        .descriptors=desc,.num_descriptors=1,
        .body="color=texelFetch(fel_bl,ivec2(gl_FragCoord.xy),0);",
    })) goto fail;
    pl_shader_dovi_reshape(sh,reshape);
    if (!pl_shader_custom(sh,&(struct pl_custom_shader){
        .input=PL_SHADER_SIG_COLOR,.output=PL_SHADER_SIG_COLOR,
        .descriptors=&desc[1],.num_descriptors=1,
        .variables=vars,.num_variables=sizeof(vars)/sizeof(vars[0]),
        .body="vec3 rr=floor(clamp(texelFetch(fel_el,ivec2(gl_FragCoord.xy),0).rgb,0.0,1.0)*fel_codes+0.5)-fel_offset;"
              "vec3 r=sign(rr)*((abs(rr)-0.5)*fel_slope+fel_threshold);"
              "color.rgb=clamp(color.rgb+clamp(r,-fel_maximum,fel_maximum),0.0,1.0);",
    })) goto fail;
    return pl_dispatch_finish(dispatch,pl_dispatch_params(.shader=&sh,.target=output));
fail:
    pl_dispatch_abort(dispatch,&sh);
    return false;
}
