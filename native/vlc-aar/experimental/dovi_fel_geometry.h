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
