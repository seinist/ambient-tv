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
static inline bool ambient_fel_format_geometry(uint32_t coded_w,uint32_t coded_h,
    uint32_t visible_w,uint32_t visible_h,uint32_t x,uint32_t y)
{
    // MediaCodec may report only the visible extent in its width/height.
    // Validate the actual physical crop in the AImage path, not here.
    return !(x&1u)&&!(y&1u)&&visible_w&&visible_h&&
        visible_w<=coded_w&&visible_h<=coded_h;
}
