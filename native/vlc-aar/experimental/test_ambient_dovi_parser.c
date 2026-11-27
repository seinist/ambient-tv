#include "ambient_dovi_parser.h"
#include <assert.h>
#include <string.h>
int main(void) {
    struct ambient_dovi_parser *p=av_ambient_dovi_alloc();
    assert(p);
    struct ambient_dovi_metadata m, before;
    memset(&m,0x4a,sizeof m); before=m;
    const uint8_t invalid[]={25,8,0,0,0,0,0,0};
    assert(av_ambient_dovi_parse(p,invalid,sizeof invalid,&m)<0);
    assert(!memcmp(&m,&before,sizeof m));
    assert(av_ambient_dovi_parse(p,invalid,2,&m)<0);
    av_ambient_dovi_flush(p);
    assert(av_ambient_dovi_parse(p,invalid,sizeof invalid,&m)<0);
    av_ambient_dovi_free(p);
    av_ambient_dovi_free(NULL);
}
