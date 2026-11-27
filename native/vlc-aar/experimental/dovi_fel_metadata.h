#pragma once
#include "ambient_dovi_parser.h"
#include "dovi_fel_nlq.h"
#include <libplacebo/colorspace.h>
#include <string.h>

/* Convert the private RPU value snapshot to the two shader inputs. No
 * borrowed FFmpeg state and no signed integer shift on coefficient scales. */
static inline bool ambient_fel_metadata(const struct ambient_dovi_metadata *m,
    struct pl_dovi_metadata *reshape,struct ambient_fel_nlq *nlq)
{
    if (!m || !reshape || !nlq || !m->residual_present || m->full_range ||
        m->bl_bit_depth!=10 || m->el_bit_depth!=10 || m->vdr_bit_depth!=12 ||
        m->coef_log2_denom<13 || m->coef_log2_denom>32 ||
        m->nlq_pivots[0]>m->nlq_pivots[1] || m->nlq_pivots[1]>1023)
        return false;
    struct ambient_fel_nlq n={.el_bits=m->el_bit_depth,.coef_log2_denom=m->coef_log2_denom};
    memcpy(n.offset,m->nlq_offset,sizeof(n.offset));
    memcpy(n.slope,m->nlq_slope,sizeof(n.slope));
    memcpy(n.threshold,m->nlq_threshold,sizeof(n.threshold));
    memcpy(n.maximum,m->nlq_maximum,sizeof(n.maximum));
    if (!ambient_fel_nlq_valid(&n)) return false;
    struct pl_dovi_metadata d={0};
    for(unsigned i=0;i<3;i++){
        if (!isfinite(m->nonlinear_offset[i])) return false;
        d.nonlinear_offset[i]=m->nonlinear_offset[i];
        for(unsigned j=0;j<3;j++){
            if (!isfinite(m->nonlinear_matrix[3*i+j]) ||
                !isfinite(m->linear_matrix[3*i+j])) return false;
            d.nonlinear.m[i][j]=m->nonlinear_matrix[3*i+j];
            d.linear.m[i][j]=m->linear_matrix[3*i+j];
        }
        const struct ambient_dovi_curve *s=&m->curves[i];
        struct pl_reshape_data *t=&d.comp[i];
        if(s->num_pivots<2||s->num_pivots>9)return false;
        t->num_pivots=s->num_pivots;
        for(unsigned j=0;j<s->num_pivots;j++){
            if(s->pivots[j]>1023||(j&&s->pivots[j]<=s->pivots[j-1]))return false;
            t->pivots[j]=s->pivots[j]/1023.f;
        }
        for(unsigned j=0;j+1<s->num_pivots;j++){
            t->method[j]=s->mapping[j];
            if(s->mapping[j]==0){
                if(s->poly_order[j]<1||s->poly_order[j]>2)return false;
                for(unsigned k=0;k<=s->poly_order[j];k++)
                    t->poly_coeffs[j][k]=(float)ldexp((double)s->poly_coef[j][k],-(int)m->coef_log2_denom);
            }else if(s->mapping[j]==1){
                if(s->mmr_order[j]<1||s->mmr_order[j]>3)return false;
                t->mmr_order[j]=s->mmr_order[j];
                t->mmr_constant[j]=(float)ldexp((double)s->mmr_constant[j],-(int)m->coef_log2_denom);
                for(unsigned k=0;k<s->mmr_order[j];k++)for(unsigned l=0;l<7;l++)
                    t->mmr_coeffs[j][k][l]=(float)ldexp((double)s->mmr_coef[j][k][l],-(int)m->coef_log2_denom);
            }else return false;
        }
    }
    *reshape=d;*nlq=n;
    return true;
}
