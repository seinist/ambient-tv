/* SPDX-License-Identifier: LGPL-2.1-or-later
 * GPU-next local HDR10+ evaluation for marked SVP intermediate frames.
 * Source mapping and synchronization use the original MPV frame callbacks.
 */
#ifndef SVP_MPV_HDR_SPATIAL_H
#define SVP_MPV_HDR_SPATIAL_H
#include <libavutil/hdr_dynamic_metadata.h>
#include "hdr10plus/hdr10plus_spatial.h"
struct svp_hdr_spatial_frame {
    struct ambient_hdrplus_spatial spatial;
    struct pl_color_space target,color;
    bool valid;
    uint64_t signature;
};
static inline void svp_hdr_spatial_destroy(pl_gpu gpu,struct svp_hdr_spatial_frame **p)
{
    if(!*p)return;
    ambient_hdrplus_spatial_destroy(gpu,&(*p)->spatial);
    free(*p);*p=NULL;
}
static inline bool svp_hdr_rational(AVRational q,float max,float *out)
{
    if(q.den<=0||q.num<0)return false;
    float x=(float)q.num/q.den;
    if(!isfinite(x)||x>max)return false;
    *out=x;return true;
}
static inline bool svp_hdr_spatial_metadata(const struct mp_image *mpi,struct ambient_hdrplus *out)
{
    const AVDynamicHDRPlus *m=NULL;
    for(int i=0;i<mpi->num_ff_side_data;i++){
        if(mpi->ff_side_data[i].type!=AV_FRAME_DATA_DYNAMIC_HDR_PLUS)continue;
        const AVBufferRef *b=mpi->ff_side_data[i].buf;
        if(!b||!b->data||b->size!=sizeof(*m))return false;
        m=(const AVDynamicHDRPlus *)b->data;break;
    }
    if(!m||m->itu_t_t35_country_code!=0xb5||m->application_version>1||
        m->num_windows<1||m->num_windows>3||mpi->w<2||mpi->h<2||mpi->w>65536||mpi->h>65536)return false;
    *out=(struct ambient_hdrplus){.version=m->application_version,.window_count=m->num_windows};
    if(!svp_hdr_rational(m->targeted_system_display_maximum_luminance,10000,&out->target_nits))return false;
    for(unsigned w=0;w<m->num_windows;w++){
        const AVHDRPlusColorTransformParams *p=&m->params[w];
        struct ambient_hdrplus_window *d=&out->windows[w];
        if(p->num_distribution_maxrgb_percentiles>15||p->num_bezier_curve_anchors>15||
            p->tone_mapping_flag>1||p->color_saturation_mapping_flag>1)return false;
        if(w){
            const AVRational q[]={p->window_upper_left_corner_x,p->window_upper_left_corner_y,
                p->window_lower_right_corner_x,p->window_lower_right_corner_y};
            uint16_t pos[4];
            for(unsigned k=0;k<4;k++){
                float value;if(!svp_hdr_rational(q[k],1,&value))return false;
                pos[k]=(uint16_t)lroundf(value*(k%2?mpi->h-1:mpi->w-1));
            }
            if(p->rotation_angle>180||p->overlap_process_option>1)return false;
            d->geometry=(struct ambient_hdrplus_geometry){
                .left=pos[0],.top=pos[1],.right=pos[2],.bottom=pos[3],
                .center_x=p->center_of_ellipse_x,.center_y=p->center_of_ellipse_y,
                .semimajor_inner=p->semimajor_axis_internal_ellipse,
                .semimajor_outer=p->semimajor_axis_external_ellipse,
                .semiminor_outer=p->semiminor_axis_external_ellipse,
                .angle=p->rotation_angle,.overlap=p->overlap_process_option};
        }
        for(unsigned c=0;c<3;c++)if(!svp_hdr_rational(p->maxscl[c],1,&d->maxscl[c]))return false;
        if(!svp_hdr_rational(p->average_maxrgb,1,&d->average)||
            !svp_hdr_rational(p->fraction_bright_pixels,1,&d->fraction))return false;
        d->histogram_count=p->num_distribution_maxrgb_percentiles;
        for(unsigned j=0;j<d->histogram_count;j++){
            d->histogram[j].percentage=p->distribution_maxrgb[j].percentage;
            if(!svp_hdr_rational(p->distribution_maxrgb[j].percentile,1,&d->histogram[j].percentile))return false;
        }
        if(!ambient_hdrplus_distribution_ordered(d,out->version))return false;
        d->tone_mapping=p->tone_mapping_flag;
        if(d->tone_mapping){
            d->anchor_count=p->num_bezier_curve_anchors;
            if(!svp_hdr_rational(p->knee_point_x,1,&d->knee_x)||!svp_hdr_rational(p->knee_point_y,1,&d->knee_y))return false;
            for(unsigned j=0;j<d->anchor_count;j++)if(!svp_hdr_rational(p->bezier_curve_anchors[j],1,&d->anchors[j]))return false;
            if(!ambient_hdrplus_usable_curve(d,out->target_nits))return false;
        }
        d->saturation_mapping=p->color_saturation_mapping_flag;
        if(d->saturation_mapping){
            float weight;if(!svp_hdr_rational(p->color_saturation_weight,7.875,&weight))return false;
            d->saturation_weight=lroundf(weight*8);
        }
    }
#define GRID(prefix,dest) do{ \
    if(m->prefix##_flag){ \
        if(m->prefix##_flag!=1||m->num_rows_##prefix<2||m->num_rows_##prefix>25|| \
            m->num_cols_##prefix<2||m->num_cols_##prefix>25)return false; \
        out->dest.rows=m->num_rows_##prefix;out->dest.cols=m->num_cols_##prefix; \
        for(unsigned i=0;i<out->dest.rows;i++)for(unsigned j=0;j<out->dest.cols;j++){ \
            float value;if(!svp_hdr_rational(m->prefix[i][j],1,&value))return false; \
            out->dest.values[i][j]=lroundf(value*15); \
        } \
    } \
}while(0)
    GRID(targeted_system_display_actual_peak_luminance,targeted_grid);
    GRID(mastering_display_actual_peak_luminance,mastering_grid);
#undef GRID
    out->metadata_constraints_ok=true;
    return true;
}
static inline bool svp_hdr_spatial_map(struct priv *p,const struct pl_frame *image,
    const struct pl_frame *target,float explicit_peak,struct pl_frame *out,uint64_t *revision)
{
    struct mp_image *mpi=image->user_data;
    if(!mpi||!mpi->ambient_svp_hdr_generated||image->repr.sys!=PL_COLOR_SYSTEM_RGB||
        image->color.transfer!=PL_COLOR_TRC_PQ||image->color.primaries!=PL_COLOR_PRIM_BT_2020||
        image->repr.dovi||image->enhancement_layer||image->num_planes!=1||
        image->profile.len||target->profile.len||image->icc||target->icc||image->lut||target->lut)return false;
    // HDR local mapping requires a characterized target, not a source hint.
    if(pl_color_space_is_hdr(&target->color)&&
        (!(explicit_peak>0)||fabsf(target->color.hdr.max_luma-explicit_peak)>.01f||
         target->repr.bits.color_depth<10||target->color.primaries!=PL_COLOR_PRIM_BT_2020||
         (target->color.transfer!=PL_COLOR_TRC_PQ&&target->color.transfer!=PL_COLOR_TRC_HLG)))return false;
    struct frame_priv *fp=mpi->priv;
    if(!fp->svp_hdr)fp->svp_hdr=calloc(1,sizeof(*fp->svp_hdr));
    struct svp_hdr_spatial_frame *s=fp->svp_hdr;
    if(!s)return false;
    if(!s->valid||!pl_color_space_equal(&s->target,&target->color)){
        struct ambient_hdrplus metadata;
        if(!svp_hdr_spatial_metadata(mpi,&metadata))return false;
        struct pl_frame input=*image;
        if(input.acquire&&!input.acquire(p->gpu,&input))return false;
        struct pl_frame mapped=input;
        pl_tex t=input.planes[0].texture;
        bool ok=t&&t->params.w==mpi->w&&t->params.h==mpi->h;
        if(ok){
            if(mapped.planes[0].components==4&&mapped.repr.alpha==PL_ALPHA_NONE){
                mapped.planes[0].components=3;mapped.planes[0].component_mapping[3]=-1;
            }
            mapped.crop=(struct pl_rect2df){0,0,mpi->w,mpi->h};
            ok=ambient_hdrplus_spatial_render(p->gpu,p->pllog,&s->spatial,&mapped,target,&metadata);
        }
        if(input.release)input.release(p->gpu,&input);
        if(!ok){s->valid=false;return false;}
        s->color=mapped.color;s->target=target->color;s->valid=true;
        s->signature=++p->svp_hdr_serial;
    }
    *out=*image;
    out->planes[0]=(struct pl_plane){.texture=s->spatial.output,.components=3,.component_mapping={0,1,2,-1}};
    out->repr=pl_color_repr_rgb;out->color=s->color;
    out->acquire=NULL;out->release=NULL;
    *revision=s->signature;
    return true;
}
#endif
