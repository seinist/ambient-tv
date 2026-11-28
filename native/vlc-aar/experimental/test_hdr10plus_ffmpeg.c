/* Independent bitstream producer/parser from pinned FFmpeg. These are
 * syntax interoperability fixtures, not a certified display reference. */
#include "hdr10plus_payload.h"
#include <libavutil/hdr_dynamic_metadata.h>
#include <libavutil/mem.h>
#include <assert.h>
#include <math.h>
#include <stdio.h>

static void eq(float a,AVRational b)
{
    assert(b.den>0&&fabs((double)a-av_q2d(b))<0.000002);
}
static void compare(const struct ambient_hdrplus *m,const AVDynamicHDRPlus *f)
{
    assert(m->version==f->application_version&&m->window_count==f->num_windows);
    eq(m->target_nits,f->targeted_system_display_maximum_luminance);
    assert(!!m->targeted_grid.rows==!!f->targeted_system_display_actual_peak_luminance_flag);
    assert(!!m->mastering_grid.rows==!!f->mastering_display_actual_peak_luminance_flag);
    assert(m->targeted_grid.rows==f->num_rows_targeted_system_display_actual_peak_luminance);
    assert(m->targeted_grid.cols==f->num_cols_targeted_system_display_actual_peak_luminance);
    assert(m->mastering_grid.rows==f->num_rows_mastering_display_actual_peak_luminance);
    assert(m->mastering_grid.cols==f->num_cols_mastering_display_actual_peak_luminance);
    for(unsigned y=0;y<m->targeted_grid.rows;y++)for(unsigned x=0;x<m->targeted_grid.cols;x++)
        eq(m->targeted_grid.values[y][x]/15.f,f->targeted_system_display_actual_peak_luminance[y][x]);
    for(unsigned y=0;y<m->mastering_grid.rows;y++)for(unsigned x=0;x<m->mastering_grid.cols;x++)
        eq(m->mastering_grid.values[y][x]/15.f,f->mastering_display_actual_peak_luminance[y][x]);
    for(unsigned i=0;i<m->window_count;i++){
        const struct ambient_hdrplus_window *a=&m->windows[i];
        const AVHDRPlusColorTransformParams *b=&f->params[i];
        if(i){
            const struct ambient_hdrplus_geometry *g=&a->geometry;
            eq(g->left,b->window_upper_left_corner_x);eq(g->top,b->window_upper_left_corner_y);
            eq(g->right,b->window_lower_right_corner_x);eq(g->bottom,b->window_lower_right_corner_y);
            assert(g->center_x==b->center_of_ellipse_x&&g->center_y==b->center_of_ellipse_y);
            assert(g->angle==b->rotation_angle&&g->overlap==b->overlap_process_option);
            assert(g->semimajor_inner==b->semimajor_axis_internal_ellipse);
            assert(g->semimajor_outer==b->semimajor_axis_external_ellipse);
            assert(g->semiminor_outer==b->semiminor_axis_external_ellipse);
        }
        for(unsigned j=0;j<3;j++)eq(a->maxscl[j],b->maxscl[j]);
        eq(a->average,b->average_maxrgb);eq(a->fraction,b->fraction_bright_pixels);
        assert(a->histogram_count==b->num_distribution_maxrgb_percentiles);
        for(unsigned j=0;j<a->histogram_count;j++){
            assert(a->histogram[j].percentage==b->distribution_maxrgb[j].percentage);
            eq(a->histogram[j].percentile,b->distribution_maxrgb[j].percentile);
        }
        assert(a->tone_mapping==!!b->tone_mapping_flag);
        if(a->tone_mapping){
            eq(a->knee_x,b->knee_point_x);eq(a->knee_y,b->knee_point_y);
            assert(a->anchor_count==b->num_bezier_curve_anchors);
            for(unsigned j=0;j<a->anchor_count;j++)eq(a->anchors[j],b->bezier_curve_anchors[j]);
        }
        assert(a->saturation_mapping==!!b->color_saturation_mapping_flag);
        if(a->saturation_mapping)eq(a->saturation_weight/8.f,b->color_saturation_weight);
    }
}
int main(void)
{
    for(unsigned windows=1;windows<=3;windows++)for(unsigned variant=0;variant<4;variant++){
        AVDynamicHDRPlus *f=av_dynamic_hdr_plus_alloc(NULL);
        AVDynamicHDRPlus *decoded=av_dynamic_hdr_plus_alloc(NULL);
        assert(f&&decoded);
        f->application_version=1;f->num_windows=windows;
        f->targeted_system_display_maximum_luminance=(AVRational){variant==0?0:10000,1};
        if(variant>=2){
            unsigned dim=variant==3?25:2;
            f->targeted_system_display_actual_peak_luminance_flag=1;
            f->mastering_display_actual_peak_luminance_flag=1;
            f->num_rows_targeted_system_display_actual_peak_luminance=dim;
            f->num_cols_targeted_system_display_actual_peak_luminance=dim;
            f->num_rows_mastering_display_actual_peak_luminance=dim;
            f->num_cols_mastering_display_actual_peak_luminance=dim;
            for(unsigned y=0;y<dim;y++)for(unsigned x=0;x<dim;x++){
                f->targeted_system_display_actual_peak_luminance[y][x]=(AVRational){(y+3*x)%16,15};
                f->mastering_display_actual_peak_luminance[y][x]=(AVRational){(3*y+x)%16,15};
            }
        }
        for(unsigned i=0;i<windows;i++){
            AVHDRPlusColorTransformParams *a=&f->params[i];
            a->window_upper_left_corner_x=(AVRational){10+i,1};
            a->window_upper_left_corner_y=(AVRational){20+i,1};
            a->window_lower_right_corner_x=(AVRational){300+i,1};
            a->window_lower_right_corner_y=(AVRational){400+i,1};
            a->center_of_ellipse_x=150+i;a->center_of_ellipse_y=200+i;
            a->rotation_angle=90*i;
            a->semimajor_axis_internal_ellipse=20;
            a->semimajor_axis_external_ellipse=40;
            a->semiminor_axis_external_ellipse=30;
            a->overlap_process_option=i%2;
            for(unsigned j=0;j<3;j++)a->maxscl[j]=(AVRational){100000-1000*i-100*j,100000};
            a->average_maxrgb=(AVRational){25000+i,100000};
            a->fraction_bright_pixels=(AVRational){variant*100+i,1000};
            a->num_distribution_maxrgb_percentiles=variant==3?15:variant*3;
            for(unsigned j=0;j<a->num_distribution_maxrgb_percentiles;j++){
                a->distribution_maxrgb[j].percentage=1+j*7;
                a->distribution_maxrgb[j].percentile=(AVRational){1000+j*3000,100000};
            }
            a->tone_mapping_flag=variant!=0;
            a->knee_point_x=(AVRational){variant==1?0:2048,4095};
            a->knee_point_y=(AVRational){variant==1?4095:1024,4095};
            a->num_bezier_curve_anchors=variant==3?15:variant;
            for(unsigned j=0;j<a->num_bezier_curve_anchors;j++)
                a->bezier_curve_anchors[j]=(AVRational){(j+1)*63,1023};
            a->color_saturation_mapping_flag=variant>=2;
            a->color_saturation_weight=(AVRational){variant==3?63:8,8};
        }
        uint8_t *body=NULL;
        size_t n=0;
        assert(av_dynamic_hdr_plus_to_t35(f,&body,&n)==0&&body&&n<=907);
        uint8_t packet[1024]={0xb5,0,0x3c,0,1,4};
        memcpy(packet+6,body,n);
        struct ambient_hdrplus m;
        assert(ambient_hdrplus_parse(packet,n+6,&m));
        assert(av_dynamic_hdr_plus_from_t35(decoded,body,n)==0);
        compare(&m,decoded);
        // FFmpeg serializes version 1 only. Its independent reader also
        // accepts the same payload with application version 0.
        packet[6]=0;
        memset(decoded,0,sizeof(*decoded));
        assert(ambient_hdrplus_parse(packet,n+6,&m));
        assert(av_dynamic_hdr_plus_from_t35(decoded,packet+6,n)==0);
        compare(&m,decoded);
        for(size_t j=0;j<n+6;j++)assert(!ambient_hdrplus_parse(packet,j,&m));
        av_free(body);av_free(f);av_free(decoded);
    }
    puts("HDR10+ syntax matches pinned FFmpeg for all fields, both versions and maximum payload");
}
