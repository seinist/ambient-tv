/* Test-only adapter: parse one complete registered user-data payload.
 * Never infer conformance from successful syntax parsing. */
#include <stdio.h>
#include <math.h>
#include "../adapters/hdr10plus/hdr10plus_payload.h"

int main(void)
{
    uint8_t bytes[1025];
    size_t n=fread(bytes,1,sizeof(bytes),stdin);
    struct ambient_hdrplus m;
    if(ferror(stdin)||n>1024||!ambient_hdrplus_parse(bytes,n,&m)){
        puts("rejected");return 2;
    }
    printf("%u %u %u %u\n",m.version,m.window_count,
        (unsigned)lroundf(m.target_nits),(unsigned)m.metadata_constraints_ok);
    for(unsigned w=0;w<m.window_count;w++){
        const struct ambient_hdrplus_window *p=&m.windows[w];
        for(unsigned c=0;c<3;c++)printf("%ld ",lroundf(p->maxscl[c]*100000.f));
        printf("%ld %ld %u",lroundf(p->average*100000.f),lroundf(p->fraction*1000.f),p->histogram_count);
        for(unsigned j=0;j<p->histogram_count;j++)
            printf(" %u %ld",p->histogram[j].percentage,lroundf(p->histogram[j].percentile*100000.f));
        printf(" %u %ld %ld %u",(unsigned)p->tone_mapping,lroundf(p->knee_x*4095.f),
            lroundf(p->knee_y*4095.f),p->anchor_count);
        for(unsigned j=0;j<p->anchor_count;j++)printf(" %ld",lroundf(p->anchors[j]*1023.f));
        puts("");
    }
    return 0;
}
