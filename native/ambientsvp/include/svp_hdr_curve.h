/* SPDX-License-Identifier: Apache-2.0
 * Content-dependent monotonic shoulder for synthesized HDR10+ frames.
 * A renderer policy, not a normative HDR10+ authoring algorithm.
 */
#ifndef SVP_HDR_CURVE_H
#define SVP_HDR_CURVE_H
#include <math.h>
#include <stdbool.h>
#include <stdint.h>
typedef struct {
    uint16_t knee_x,knee_y;
    uint8_t count;
    uint16_t anchors[9];
} SvpHdrCurve;
/* Quantized version-1 scene statistics, recommended distribution list.
 * Reserved marker entries are not actual percentiles and may exceed peak. */
static inline bool svp_hdr_scene_values_schema(const uint32_t maxscl[3],uint32_t average,
    const uint32_t percentiles[9],uint32_t fraction,const uint32_t schema[9])
{
    if(!maxscl||!percentiles||!schema||fraction<1||fraction>1000)return false;
    for(unsigned i=0;i<9;i++)if(schema[i]>100||(i&&schema[i]<=schema[i-1]))return false;
    bool reserved=schema[1]==5&&schema[2]==10;
    if(reserved&&(percentiles[1]!=0||percentiles[2]!=255))return false;
    uint32_t peak=0,last=0;
    for(unsigned i=0;i<3;i++){if(maxscl[i]>100000)return false;if(maxscl[i]>peak)peak=maxscl[i];}
    if(average>peak)return false;
    for(unsigned i=0;i<9;i++){
        if(reserved&&(i==1||i==2))continue;
        if(percentiles[i]>peak||percentiles[i]<last)return false;
        last=percentiles[i];
    }
    return true;
}
static inline bool svp_hdr_scene_values(const uint32_t maxscl[3],uint32_t average,
    const uint32_t percentiles[9],uint32_t fraction)
{
    const uint32_t schema[9]={1,5,10,25,50,75,90,95,99};
    return svp_hdr_scene_values_schema(maxscl,average,percentiles,fraction,schema);
}
/* Prove nonnegative Bezier derivative in Bernstein form. A decreasing
 * control-point pair is not necessarily a nonmonotone curve. */
static inline bool svp_hdr_bernstein_nonnegative(const double *p,unsigned n,unsigned depth)
{
    bool positive=true;
    for(unsigned i=0;i<=n;i++)if(p[i]<0.0){positive=false;break;}
    if(positive)return true;
    if(p[0]<0.0||p[n]<0.0||!depth)return false;
    double v[16],left[16],right[16];
    for(unsigned i=0;i<=n;i++)v[i]=p[i];
    left[0]=v[0];right[n]=v[n];
    for(unsigned k=1;k<=n;k++){
        for(unsigned j=0;j<=n-k;j++)v[j]=(v[j]+v[j+1])*.5;
        left[k]=v[0];right[n-k]=v[n-k];
    }
    return svp_hdr_bernstein_nonnegative(left,n,depth-1)&&svp_hdr_bernstein_nonnegative(right,n,depth-1);
}
static inline bool svp_hdr_anchors_monotone(const uint16_t *anchors,unsigned count)
{
    if(count>15||(!anchors&&count))return false;
    double delta[16];unsigned last=0;
    for(unsigned i=0;i<count;i++){
        if(anchors[i]>1023)return false;
        delta[i]=(double)anchors[i]-last;last=anchors[i];
    }
    delta[count]=1023.0-last;
    return svp_hdr_bernstein_nonnegative(delta,count,14);
}
static inline bool svp_hdr_curve(uint32_t peak,uint32_t mean,uint32_t percentile,
    double target_nits,SvpHdrCurve *out)
{
    if(!out||peak>100000||mean>peak||percentile>peak||
       !isfinite(target_nits)||target_nits<=0||target_nits>10000)return false;
    *out=(SvpHdrCurve){0};
    const double source=peak*.1;
    // No shoulder needed when the measured content fits the target.
    if(source<=target_nits||!peak)return true;
    const double ratio=target_nits/source;
    // Protect measured midtones and the upper percentile. Keep a
    // continuous 1:1 linear segment followed by a monotone shoulder.
    const double mid=mean*.1/target_nits;
    const double highlight=percentile*.1/source;
    const double k=fmin(.85,fmax(.08,.55+fmin(.25,mid*.20)-.20*highlight));
    const double x=k*ratio,y=k;
    out->knee_x=(uint16_t)fmax(1,fmin(4094,round(x*4095)));
    out->knee_y=(uint16_t)fmax(1,fmin(4094,round(y*4095)));
    // Choose a rational shoulder whose initial slope approximates the
    // preceding linear segment after normalizing to the curved interval.
    const double qx=out->knee_x/4095.0,qy=out->knee_y/4095.0;
    const double slope=fmax(1.0,(source/target_nits)*(1-qx)/(1-qy));
    const double strength=fmin(64.0,slope-1.0);
    out->count=9;
    for(unsigned i=0;i<out->count;i++){
        const double t=(i+1.0)/(out->count+1.0);
        const double v=(1+strength)*t/(1+strength*t);
        out->anchors[i]=(uint16_t)fmin(1023,fmax(0,round(v*1023)));
    }
    return true;
}
#endif
