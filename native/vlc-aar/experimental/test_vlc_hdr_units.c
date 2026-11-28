/* Exercise the actual patched VLC libplacebo helpers (not a copy).
 * The host link discards unrelated VLC plugin sections. */
#include "utils.h"
#include <assert.h>
#include <math.h>

int main(void)
{
    video_format_t fmt={0};
    fmt.primaries=COLOR_PRIMARIES_BT2020;
    fmt.transfer=TRANSFER_FUNC_SMPTE_ST2084;
    fmt.mastering.primaries[0]=8500;fmt.mastering.primaries[1]=39850;
    fmt.mastering.primaries[2]=6550;fmt.mastering.primaries[3]=2300;
    fmt.mastering.primaries[4]=35400;fmt.mastering.primaries[5]=14600;
    fmt.mastering.white_point[0]=15635;fmt.mastering.white_point[1]=16450;
    fmt.mastering.max_luminance=10000000;fmt.mastering.min_luminance=50;
    fmt.lighting.MaxCLL=900;fmt.lighting.MaxFALL=400;
    struct pl_color_space color=vlc_placebo_ColorSpace(&fmt);
    assert(color.primaries==PL_COLOR_PRIM_BT_2020&&color.transfer==PL_COLOR_TRC_PQ);
    assert(fabsf(color.hdr.prim.green.x-0.17f)<1e-6f);
    assert(fabsf(color.hdr.prim.green.y-0.797f)<1e-6f);
    assert(fabsf(color.hdr.prim.blue.x-0.131f)<1e-6f);
    assert(fabsf(color.hdr.prim.blue.y-0.046f)<1e-6f);
    assert(fabsf(color.hdr.prim.red.x-0.708f)<1e-6f);
    assert(fabsf(color.hdr.prim.red.y-0.292f)<1e-6f);
    assert(fabsf(color.hdr.prim.white.x-0.3127f)<1e-6f);
    assert(fabsf(color.hdr.prim.white.y-0.329f)<1e-6f);
    assert(color.hdr.max_luma==1000.f&&fabsf(color.hdr.min_luma-0.005f)<1e-7f);
    assert(color.hdr.max_cll==900.f&&color.hdr.max_fall==400.f);
    vlc_video_hdr_dynamic_metadata_t hdr={.maxscl={0.1f,0.08f,0.06f},
        .average_maxrgb=0.025f,.targeted_luminance=203.f,.tone_mapping_flag=1,
        .knee_point_x=0.5f,.knee_point_y=0.25f,.num_bezier_anchors=1,
        .bezier_curve_anchors={0.5f}};
    vlc_placebo_HdrMetadata(&hdr,&color.hdr);
    assert(color.hdr.scene_max[0]==1000.f&&fabsf(color.hdr.scene_max[1]-800.f)<1e-4f);
    assert(fabsf(color.hdr.scene_max[2]-600.f)<1e-4f&&color.hdr.scene_avg==250.f);
    assert(color.hdr.ootf.target_luma==203.f&&color.hdr.ootf.num_anchors==1);
    hdr.tone_mapping_flag=0;
    vlc_placebo_HdrMetadata(&hdr,&color.hdr);
    assert(color.hdr.ootf.num_anchors==0&&color.hdr.ootf.target_luma==0.f);
    assert(color.hdr.max_luma==1000.f&&color.hdr.max_cll==900.f);

    vlc_video_dovi_metadata_t src={.bl_bit_depth=10,.coef_log2_denom=32};
    struct pl_dovi_metadata dst={0};
    for(unsigned i=0;i<3;i++){
        struct vlc_dovi_reshape_t *c=&src.curves[i];
        c->num_pivots=2;c->pivots[1]=1023;
        c->mapping_idc[0]=VLC_DOVI_RESHAPE_POLYNOMIAL;
        c->poly_order[0]=1;c->poly_coef[0][0]=-(INT64_C(1)<<30);
        c->poly_coef[0][1]=INT64_C(1)<<32;
    }
    vlc_placebo_DoviMetadata(&src,&dst);
    for(unsigned i=0;i<3;i++){
        assert(dst.comp[i].pivots[1]==1.f);
        assert(dst.comp[i].poly_coeffs[0][0]==-0.25f);
        assert(dst.comp[i].poly_coeffs[0][1]==1.f);
    }
    return 0;
}
