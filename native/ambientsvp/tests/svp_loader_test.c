/* SPDX-License-Identifier: Apache-2.0
 * Compile as a mock shared runtime or as a loader regression executable. */
#include "ambientsvp.h"
#include <assert.h>
#include <stdlib.h>
#include <string.h>

#ifdef MOCK_RUNTIME
struct AmbientSvp { int value; };
uint32_t ambient_svp_abi(void) {
#ifdef WRONG_ABI
    return AMBIENTSVP_ABI + 1;
#else
    return AMBIENTSVP_ABI;
#endif
}
AmbientSvp *ambient_svp_create(const AmbientSvpConfig *c) {
    (void)c;
    return calloc(1,sizeof(AmbientSvp));
}
void ambient_svp_destroy(AmbientSvp *s) { free(s); }
int ambient_svp_reset(AmbientSvp *s) { s->value=0; return 0; }
int ambient_svp_push(AmbientSvp *s,const uint8_t *p,size_t n,int64_t pts) {
    (void)pts; if (!n) return -1; s->value=p[0]; return 0;
}
int ambient_svp_receive(AmbientSvp *s,uint8_t *p,size_t n,int64_t *pts,int eof) {
    (void)eof; if (!n) return -1; memset(p,s->value,n); *pts=42; return 1;
}
#ifndef MISSING_REQUIRED
int ambient_svp_stats(const AmbientSvp *s,AmbientSvpStats *stats) {
    (void)s; memset(stats,0,sizeof(*stats)); return 0;
}
#endif
void ambient_svp_bypass(AmbientSvp *s,uint32_t reason) { (void)s; (void)reason; }
int ambient_svp_enabled(void) { return 1; }
#ifdef DIRECT_APIS
int ambient_svp_push_fill(AmbientSvp *s,int64_t pts,void *context,AmbientSvpFill fill) {
    uint8_t data[8]; if (fill(context,data,sizeof(data))) return -1;
    return ambient_svp_push(s,data,sizeof(data),pts);
}
int ambient_svp_ready(AmbientSvp *s,int eof) { (void)s;(void)eof;return 1; }
int ambient_svp_receive_visit(AmbientSvp *s,void *context,AmbientSvpVisit visit,int64_t *pts,int eof) {
    uint8_t data[8];int r=ambient_svp_receive(s,data,sizeof(data),pts,eof);
    if (r==1) visit(context,data,sizeof(data));
    return r;
}
int ambient_svp_push_ahardwarebuffer(AmbientSvp *s,struct AHardwareBuffer *b,
    uint32_t x,uint32_t y,int fence,int64_t pts) {
    (void)s;(void)b;(void)x;(void)y;(void)fence;(void)pts;return -4;
}
int ambient_svp_receive_ahardwarebuffer(AmbientSvp *s,struct AHardwareBuffer *b,
    uint32_t x,uint32_t y,int fence,int *release,int64_t *pts,int eof) {
    (void)s;(void)b;(void)x;(void)y;(void)fence;(void)pts;(void)eof;
    if (release) *release=-1;
    return -4;
}
#endif
#else
#include "ambientsvp_loader.h"
static int fill(void *context,uint8_t *dst,size_t len) {
    memset(dst,*(const uint8_t *)context,len);return 0;
}
static void visit(void *context,const uint8_t *src,size_t len) {
    assert(len==8);
    memcpy(context,src,len);
}
int main(int argc,char **argv) {
    assert(argc==2);
    SvpApi api;
    memset(&api,0,sizeof(api));
    int loaded=svp_load(&api);
    if (!strcmp(argv[1],"reject")) {
        assert(!loaded && !api.lib && !api.create && !api.receive);
        return 0;
    }
    assert(loaded && api.enabled());
    AmbientSvpConfig config={0};
    AmbientSvp *s=api.create(&config);
    assert(s);
    uint8_t data[8]={0},value=37;
    int64_t pts=0;
    if (!strcmp(argv[1],"direct")) {
        assert(api.push_fill && api.ready && api.receive_visit);
        assert(api.push_ahardwarebuffer && api.receive_ahardwarebuffer);
        int release=99;
        assert(api.push_ahardwarebuffer(s,NULL,0,0,-1,0)==-4);
        assert(api.receive_ahardwarebuffer(s,NULL,0,0,-1,&release,&pts,0)==-4 && release==-1);
        assert(api.push_fill(s,0,&value,fill)==0);
        assert(api.ready(s,0)==1);
        assert(api.receive_visit(s,data,visit,&pts,0)==1);
    } else {
        assert(!api.push_fill && !api.ready && !api.receive_visit);
        assert(!api.push_ahardwarebuffer && !api.receive_ahardwarebuffer);
        assert(api.push(s,&value,1,0)==0);
        assert(api.receive(s,data,sizeof(data),&pts,0)==1);
    }
    for (size_t i=0;i<sizeof(data);i++) assert(data[i]==value);
    assert(pts==42);
    api.destroy(s);
    dlclose(api.lib);
    return 0;
}
#endif
