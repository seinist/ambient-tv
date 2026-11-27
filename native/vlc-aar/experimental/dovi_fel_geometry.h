#pragma once
#include <stdbool.h>
#include <stdint.h>

/* Visible-picture origin must be shared between layers. Allocation padding
 * after the visible rectangle is not a spatial offset. */
static inline bool ambient_fel_visible_geometry(uint32_t coded_w,uint32_t coded_h,
    uint32_t visible_w,uint32_t visible_h,uint32_t x,uint32_t y)
{
    return !x&&!y&&visible_w&&visible_h&&visible_w<=coded_w&&visible_h<=coded_h;
}

/* Spatial HDR metadata is only admitted for an origin-aligned visible
 * picture. A decoder may report a zero format offset even though the
 * physical AImage crop is shifted; do not treat that as confirmed geometry. */
static inline bool ambient_hdrplus_origin_aligned(uint32_t coded_w,uint32_t coded_h,
    uint32_t visible_w,uint32_t visible_h,uint32_t declared_x,uint32_t declared_y,
    int32_t actual_x,int32_t actual_y)
{
    return actual_x==0&&actual_y==0&&ambient_fel_visible_geometry(coded_w,coded_h,
        visible_w,visible_h,declared_x,declared_y);
}

/* Reconstruction is defined in visible coordinates. Support identity and
 * exact factor-two layers per axis without integer overflow. */
static inline bool ambient_fel_layer_ratio(uint32_t bl,uint32_t el)
{
    return bl&&el&&(bl==el||((bl%2u)==0&&bl/2u==el));
}

/* Exact visible coordinate mapping with 4:2:0 phase preserved.
 * AImage crop origins are checked separately against the declared BL
 * origin. Use division rather than multiplication to avoid overflow. */
static inline bool ambient_fel_crop_axis(uint32_t bl,uint32_t el,
    uint32_t bl_origin,uint32_t el_origin)
{
    if(!ambient_fel_layer_ratio(bl,el)||(bl_origin&1u)||(el_origin&1u))return false;
    unsigned ratio=bl==el?1:2;
    return bl_origin%ratio==0&&bl_origin/ratio==el_origin;
}
/* Coordinate mapping from a BL-local texel center into the visible EL.
 * ratio is 1 or 1/2, offset is an integral EL luma coordinate. An odd
 * EL offset is an explicit half-chroma-sample phase, not a crop error. */
struct ambient_fel_axis_map { float ratio, offset; };
static inline bool ambient_fel_axis_bounds(uint32_t bl,uint32_t el,
    struct ambient_fel_axis_map m)
{
    // Geometry is bounded to actual supported HEVC layer dimensions.
    return bl&&el&&bl<=8192&&el<=4096&&
        (m.ratio==1.f||m.ratio==0.5f)&&m.offset>=0.f&&
        m.offset<=(float)el&&m.offset+bl*m.ratio<=(float)el;
}
static inline bool ambient_fel_axis_mapping(uint32_t bl,uint32_t el,
    uint32_t bl_origin,uint32_t el_origin,uint32_t bl_coded,uint32_t el_coded,
    struct ambient_fel_axis_map *out)
{
    if(!out||!bl||!el||(bl_origin&1u)||(el_origin&1u))return false;
    struct ambient_fel_axis_map m={0};
    // Preserve the established matched visible-rectangle contract.
    if(ambient_fel_crop_axis(bl,el,bl_origin,el_origin)){
        m.ratio=bl==el?1.f:0.5f;
    }else{
        // For a different visible rectangle the coded extents must prove
        // the scale. Never extrapolate missing residual pixels.
        if(!ambient_fel_layer_ratio(bl_coded,el_coded)||
            bl_origin>bl_coded||bl>bl_coded-bl_origin||
            el_origin>el_coded||el>el_coded-el_origin)return false;
        uint32_t scale=bl_coded==el_coded?1:2;
        uint64_t start=bl_origin,end=start+bl;
        uint64_t el_start=(uint64_t)el_origin*scale;
        uint64_t el_end=el_start+(uint64_t)el*scale;
        if(start<el_start||end>el_end)return false;
        m.ratio=1.f/scale;
        m.offset=(float)(start-el_start)/scale;
    }
    if(!ambient_fel_axis_bounds(bl,el,m))return false;
    *out=m;return true;
}
static inline bool ambient_fel_format_geometry(uint32_t coded_w,uint32_t coded_h,
    uint32_t visible_w,uint32_t visible_h,uint32_t x,uint32_t y)
{
    // MediaCodec may report only the visible extent in its width/height.
    // Validate the actual physical crop in the AImage path, not here.
    return !(x&1u)&&!(y&1u)&&visible_w&&visible_h&&
        visible_w<=coded_w&&visible_h<=coded_h;
}
