#include "ambient_dovi_parser.h"
#include <assert.h>
#include <string.h>
#include "dovi_rpu.h"
#include "libavutil/mem.h"
#include "dovi_rpu_au.h"
// Generate a known, CRC-protected profile-8 identity curve using the actual
// pinned FFmpeg encoder. No copyrighted video asset or opaque binary fixture.
static uint8_t *fixture(unsigned max_pq, unsigned profile, int *size) {
    AVDOVIMetadata *m = av_dovi_metadata_alloc(NULL);
    assert(m);
    AVDOVIRpuDataHeader *h = av_dovi_get_header(m);
    *h = (AVDOVIRpuDataHeader) {
        .rpu_type=2, .vdr_rpu_profile=profile==8?1:0,
        .coef_data_type=RPU_COEFF_FIXED, .coef_log2_denom=13,
        .bl_video_full_range_flag=profile==5, .bl_bit_depth=10,
        .el_bit_depth=10, .vdr_bit_depth=12, .disable_residual_flag=1,
    };
    AVDOVIDataMapping *map = av_dovi_get_mapping(m);
    map->num_x_partitions=map->num_y_partitions=1;
    map->nlq_method_idc=AV_DOVI_NLQ_NONE;
    for (unsigned i=0;i<3;++i) {
        AVDOVIReshapingCurve *curve=&map->curves[i];
        curve->num_pivots=2; curve->pivots[0]=0; curve->pivots[1]=1023;
        curve->mapping_idc[0]=AV_DOVI_MAPPING_POLYNOMIAL;
        curve->poly_order[0]=1; curve->poly_coef[0][1]=8192;
    }
    *av_dovi_get_color(m)=ff_dovi_color_default;
    av_dovi_get_color(m)->source_max_pq=max_pq;
    DOVIContext enc={0};
    enc.cfg.dv_profile=profile;
    uint8_t *escaped=NULL;
    int escaped_size=0;
    assert(ff_dovi_rpu_generate(&enc,m,FF_DOVI_WRAP_NAL,&escaped,&escaped_size)==0);
    uint8_t *rbsp=av_malloc(escaped_size);
    size_t n=0;
    assert(rbsp && ambient_rpu_unescape(escaped,escaped_size,rbsp,escaped_size,&n));
    *size=(int)n;
    av_free(escaped);
    av_free(m);
    ff_dovi_ctx_unref(&enc);
    return rbsp;
}

int main(void) {
    assert(!av_ambient_dovi_alloc(7));
    struct ambient_dovi_parser *p=av_ambient_dovi_alloc(8);
    assert(p);
    struct ambient_dovi_metadata m, before;
    memset(&m,0x4a,sizeof m); before=m;
    const uint8_t invalid[]={25,8,0,0,0,0,0,0};
    assert(av_ambient_dovi_parse(p,invalid,sizeof invalid,&m)<0);
    assert(!memcmp(&m,&before,sizeof m));
    assert(av_ambient_dovi_parse(p,invalid,2,&m)<0);
    av_ambient_dovi_flush(p);
    assert(av_ambient_dovi_parse(p,invalid,sizeof invalid,&m)<0);
    // Exercise genuine accepted metadata, independent snapshots, CRC
    // rejection, recovery with a new complete RPU, and profile-5 rejection.
    int n=0;
    uint8_t *rpu=fixture(3000,8,&n);
    assert(av_ambient_dovi_parse(p,rpu,n,&m)==0);
    assert(m.source_max_pq==3000 && m.bl_bit_depth==10 && !m.full_range);
    assert(m.curves[0].num_pivots==2 && m.curves[0].poly_coef[0][1]==8192);
    before=m;
    rpu[n-2]^=1; // deliberately corrupt the CRC
    assert(av_ambient_dovi_parse(p,rpu,n,&m)<0);
    assert(!memcmp(&m,&before,sizeof m));
    av_free(rpu);
    rpu=fixture(3500,8,&n);
    assert(av_ambient_dovi_parse(p,rpu,n,&m)==0);
    assert(m.source_max_pq==3500 && before.source_max_pq==3000);
    av_free(rpu);
    av_ambient_dovi_flush(p);
    rpu=fixture(3000,5,&n);
    assert(av_ambient_dovi_parse(p,rpu,n,&m)<0);
    av_ambient_dovi_free(p);
    p=av_ambient_dovi_alloc(5);
    assert(p);
    assert(av_ambient_dovi_parse(p,rpu,n,&m)==0);
    assert(m.full_range && m.bl_bit_depth==10 && m.source_max_pq==3000);
    av_free(rpu);
    av_ambient_dovi_flush(p);
    rpu=fixture(3500,8,&n);
    assert(av_ambient_dovi_parse(p,rpu,n,&m)<0);
    av_free(rpu);
    av_ambient_dovi_free(p);
    av_ambient_dovi_free(NULL);
}
