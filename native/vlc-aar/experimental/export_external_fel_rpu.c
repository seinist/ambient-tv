/* Read a real external Profile-7 RPU with the actual patched FFmpeg parser.
 * This is a local CI intermediate only, not a public binary format. */
#include "ambient_dovi_parser.h"
#include "dovi_rpu_au.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
int main(int argc,char **argv)
{
    assert(argc==3);
    FILE *f=fopen(argv[1],"rb");assert(f);
    uint8_t bytes[65536]={0},rbsp[65536+64]={0};
    size_t n=fread(bytes,1,sizeof(bytes),f);
    assert(n>5&&n<sizeof(bytes)&&feof(f)&&!ferror(f));fclose(f);
    size_t prefix=0,start=ambient_rpu_start_code(bytes,n,0,&prefix);
    assert(prefix&&start==0);
    size_t pos=start+prefix,next_prefix=0;
    size_t end=ambient_rpu_start_code(bytes,n,pos,&next_prefix);
    assert(end==n); /* pinned single-RPU fixture */
    if(end-pos>=2&&bytes[pos]==0x7c&&bytes[pos+1]==1)pos+=2;
    assert(bytes[pos]==25);
    size_t len=0;
    assert(ambient_rpu_unescape(bytes+pos,end-pos,rbsp,sizeof(rbsp),&len));
    struct ambient_dovi_parser *parser=av_ambient_dovi_alloc(7);
    assert(parser);
    struct ambient_dovi_metadata m={0};
    assert(av_ambient_dovi_parse(parser,rbsp,len,&m)==0);
    av_ambient_dovi_free(parser);
    assert(m.residual_present&&m.bl_bit_depth==10&&m.el_bit_depth==10&&m.vdr_bit_depth==12);
    bool active=false;
    for(unsigned k=0;k<3;k++)active|=m.nlq_maximum[k]&&(m.nlq_slope[k]||m.nlq_threshold[k]);
    assert(active);
    f=fopen(argv[2],"wb");assert(f);
    assert(fwrite(&m,sizeof(m),1,f)==1&&fclose(f)==0);
    printf("External Profile 7 FEL RPU parsed: coefficient denominator %u, pivots %u/%u/%u\n",
        m.coef_log2_denom,m.curves[0].num_pivots,m.curves[1].num_pivots,m.curves[2].num_pivots);
}
