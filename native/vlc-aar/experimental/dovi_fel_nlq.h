#pragma once
/* Profile 7 FEL composition reference for decoded, normalized codewords.
 * The EL texture is not conventional HDR video: no range or color conversion
 * may be applied before inverse quantization. This component is independent
 * of decoder lifetime; the active experimental renderer uses the same math. */
#include <math.h>
#include <stdbool.h>
#include <stdint.h>
struct ambient_fel_nlq {
    unsigned el_bits, coef_log2_denom;
    uint16_t offset[3];
    uint64_t slope[3], threshold[3], maximum[3];
};
static inline bool ambient_fel_nlq_valid(const struct ambient_fel_nlq *n)
{
    if (!n || n->el_bits<8 || n->el_bits>16 || n->coef_log2_denom>32)
        return false;
    unsigned max=(1u<<n->el_bits)-1;
    for (unsigned i=0;i<3;i++)
        if (n->offset[i]>max)
            return false;
    return true;
}
/* A zero maximum or zero slope AND threshold proves an identically
 * zero residual, independent of EL codewords. This includes canonical
 * Profile 7 MEL NLQ parameters, but is not a profile classifier. */
static inline bool ambient_fel_nlq_zero(const struct ambient_fel_nlq *n)
{
    if(!ambient_fel_nlq_valid(n))return false;
    for(unsigned i=0;i<3;i++)
        if(n->maximum[i]&&(n->slope[i]||n->threshold[i]))return false;
    return true;
}
static inline double ambient_fel_residual(const struct ambient_fel_nlq *n,
    unsigned component, double sample)
{
    if (!ambient_fel_nlq_valid(n) || component>=3 || !isfinite(sample))
        return NAN;
    // Round the signed difference, not the unsigned resampled code.
    // Define half ties symmetrically away from zero on CPU and GPU.
    double rr=round(fmin(1.0,fmax(0.0,sample))*((1u<<n->el_bits)-1)-n->offset[component]);
    if (rr==0.0) return 0.0;
    double scale=ldexp(1.0,-(int)n->coef_log2_denom);
    double v=((fabs(rr)-0.5)*n->slope[component]+n->threshold[component])*scale;
    return copysign(fmin(v,n->maximum[component]*scale),rr);
}
