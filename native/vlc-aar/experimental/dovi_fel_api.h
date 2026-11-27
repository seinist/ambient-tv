#pragma once
/* Private static FFmpeg interface. No AVFrame/AVPacket types cross modules.
 * Data is copied once at picture ownership transfer, allocated with libc
 * malloc and freed by VLC's standard ancillary destructor. */
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "ambient_dovi_parser.h"
#ifdef __cplusplus
extern "C" {
#endif
struct ambient_fel_session;
struct ambient_fel_picture {
    struct ambient_dovi_metadata rpu;
    int64_t pts;
    unsigned width,height;
    float chroma_x,chroma_y;
    // Tightly packed 16-bit Y, Cb, Cr codewords, ceil chroma dimensions.
    uint16_t samples[];
};
struct ambient_fel_session *av_ambient_fel_open(void);
void av_ambient_fel_close(struct ambient_fel_session *);
void av_ambient_fel_flush(struct ambient_fel_session *);
int av_ambient_fel_submit(struct ambient_fel_session *,const uint8_t *,size_t,int64_t,int64_t);
int av_ambient_fel_drain(struct ambient_fel_session *);
/* Return owned EL frame for exactly pts, never nearest. */
struct ambient_fel_picture *av_ambient_fel_take(struct ambient_fel_session *,int64_t);
void av_ambient_fel_discard(struct ambient_fel_session *,int64_t);
#ifdef __cplusplus
}
#endif
