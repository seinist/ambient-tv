#include <assert.h>
#include <stdint.h>
#include <string.h>
#include "svp_planes.h"

int main(void)
{
    uint8_t uv[130], u[65], v[65], roundtrip[130];
    for (size_t i = 0; i < sizeof uv; ++i) uv[i] = (uint8_t)(i * 13 + 19);
    for (size_t n = 1; n <= 65; ++n) {
        memset(u, 0xee, sizeof u); memset(v, 0xee, sizeof v);
        memset(roundtrip, 0xee, sizeof roundtrip);
        svp_split_uv(u, v, uv, n);
        for (size_t i = 0; i < n; ++i) {
            assert(u[i] == uv[2*i]); assert(v[i] == uv[2*i+1]);
        }
        svp_join_uv(roundtrip, u, v, n);
        assert(memcmp(uv, roundtrip, n * 2) == 0);
        if (n < 65) {
            assert(u[n] == 0xee && v[n] == 0xee);
            assert(roundtrip[2*n] == 0xee);
        }
    }
    uint8_t src[24], dst[24];
    for (size_t i = 0; i < sizeof src; ++i) src[i] = (uint8_t)i;
    memset(dst, 0xee, sizeof dst);
    svp_copy_plane(dst, 6, src, 8, 4, 3);
    for (size_t y = 0; y < 3; ++y) {
        assert(memcmp(dst + y*6, src + y*8, 4) == 0);
        assert(dst[y*6 + 4] == 0xee);
    }
    svp_copy_plane(dst, 4, src + 16, -8, 4, 3);
    for (size_t y = 0; y < 3; ++y)
        assert(memcmp(dst + y*4, src + (2-y)*8, 4) == 0);
    svp_copy_plane(dst, 4, src, 4, 4, 3);
    assert(memcmp(dst, src, 12) == 0);
    return 0;
}
