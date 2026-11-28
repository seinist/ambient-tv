/* Publicly reported numerical FFprobe metadata from Minions: The Rise of
 * Gru (2022), source described as UHD Blu-ray, dovi_tool issue #242.
 * https://github.com/quietvoid/dovi_tool/issues/242
 * Only numerical decoder parameters, not the movie or its original RPU.
 * The reported attribution is not independently authenticated. */
#include "ambient_dovi_parser.h"
#include <assert.h>
#include <stdio.h>
int main(int argc,char **argv)
{
    assert(argc==2);
    struct ambient_dovi_metadata m={
        .coef_log2_denom=23,.bl_bit_depth=10,.el_bit_depth=10,
        .vdr_bit_depth=12,.full_range=0,
        .residual_present=1,.bl_spatial_resampling=0,.el_spatial_resampling=1,
        .source_min_pq=62,.source_max_pq=3696,
        .nonlinear_offset={16777216.0f/268435456.0f,134217728.0f/268435456.0f,134217728.0f/268435456.0f},
        .nonlinear_matrix={9574.0f/8192.0f,0.0f/8192.0f,13802.0f/8192.0f,9574.0f/8192.0f,-1540.0f/8192.0f,-5348.0f/8192.0f,9574.0f/8192.0f,17610.0f/8192.0f,0.0f/8192.0f},
        .linear_matrix={7222.0f/16384.0f,8771.0f/16384.0f,390.0f/16384.0f,2654.0f/16384.0f,12430.0f/16384.0f,1300.0f/16384.0f,0.0f/16384.0f,422.0f/16384.0f,15962.0f/16384.0f},
        .nlq_pivots={0,1023},
        .nlq_offset={512,512,512},
        .nlq_maximum={1048576,1048576,1048576},
        .nlq_slope={2048,2048,2048},
        .nlq_threshold={0,0,0},
    };
    m.curves[0].num_pivots=9;
    m.curves[0].pivots[0]=0;
    m.curves[0].pivots[1]=128;
    m.curves[0].pivots[2]=256;
    m.curves[0].pivots[3]=384;
    m.curves[0].pivots[4]=512;
    m.curves[0].pivots[5]=640;
    m.curves[0].pivots[6]=768;
    m.curves[0].pivots[7]=896;
    m.curves[0].pivots[8]=1023;
    m.curves[0].poly_order[0]=1;
    m.curves[0].poly_coef[0][1]=8388608;
    m.curves[0].poly_order[1]=1;
    m.curves[0].poly_coef[1][1]=8388608;
    m.curves[0].poly_order[2]=1;
    m.curves[0].poly_coef[2][1]=8388608;
    m.curves[0].poly_order[3]=1;
    m.curves[0].poly_coef[3][1]=8388608;
    m.curves[0].poly_order[4]=1;
    m.curves[0].poly_coef[4][1]=8388608;
    m.curves[0].poly_order[5]=1;
    m.curves[0].poly_coef[5][1]=8388608;
    m.curves[0].poly_order[6]=1;
    m.curves[0].poly_coef[6][1]=8388608;
    m.curves[0].poly_order[7]=1;
    m.curves[0].poly_coef[7][1]=8388608;
    m.curves[1].num_pivots=2;
    m.curves[1].pivots[0]=0;
    m.curves[1].pivots[1]=1023;
    m.curves[1].mapping[0]=1;
    m.curves[1].mmr_order[0]=3;
    m.curves[1].mmr_constant[0]=0;
    m.curves[1].mmr_coef[0][0][1]=6391320;
    m.curves[1].mmr_coef[0][1][1]=3195660;
    m.curves[1].mmr_coef[0][2][1]=1597830;
    m.curves[2].num_pivots=2;
    m.curves[2].pivots[0]=0;
    m.curves[2].pivots[1]=1023;
    m.curves[2].mapping[0]=1;
    m.curves[2].mmr_order[0]=3;
    m.curves[2].mmr_constant[0]=0;
    m.curves[2].mmr_coef[0][0][2]=6391320;
    m.curves[2].mmr_coef[0][1][2]=3195660;
    m.curves[2].mmr_coef[0][2][2]=1597830;
    FILE *f=fopen(argv[1],"wb");assert(f);
    assert(fwrite(&m,sizeof(m),1,f)==1&&fclose(f)==0);
    puts("Published commercial-title coefficient snapshot (report, not original video)");
}
