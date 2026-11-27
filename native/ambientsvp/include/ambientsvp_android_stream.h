/* SPDX-License-Identifier: Apache-2.0
 * Android compositor stream for players with their own AImageReader.
 * No Vulkan handles need to cross the player's module boundary.
 */
#ifndef AMBIENTSVP_ANDROID_STREAM_H
#define AMBIENTSVP_ANDROID_STREAM_H
#include <stdint.h>
#include <android/hardware_buffer.h>
typedef struct Stream AmbientSvpAndroidStream;
typedef struct { AHardwareBuffer *buffer; int fence_fd; } AmbientSvpDisplayOutput;
typedef void (*AmbientSvpSourceRelease)(void *context,int release_fd);
/* Owns a compatible Vulkan compute device. Preflights compositor RGBA
 * storage image import. Only explicit SDR8, <=1080p, nonprotected. */
AmbientSvpAndroidStream *ambient_svp_android_stream_create(uint32_t,uint32_t);
/* Borrow the existing decoder buffer; acquire_fd remains caller-owned.
 * Transfer an ADDITIONAL source-picture token to the callback, even on
 * ordinary failure. Callback takes ownership of any returned sync_file.
 * The caller retains its original picture reference. First push has
 * count=0; later pushes have 1..8 phases for the previous/current pair.
 * Each successful output owns an independent AHB ref and acquire FD.
 * 0 success, -4 safe fallback (source handback fence published),
 * -5 source cannot safely be presented: drop original picture.
 * No cross-thread calls or callback reentry into the stream. */
int ambient_svp_android_stream_push(AmbientSvpAndroidStream *,AHardwareBuffer *,int,int64_t,
    uint32_t left,uint32_t top,uint32_t width,uint32_t height,void *,AmbientSvpSourceRelease,
    const float *phases,uint32_t count,AmbientSvpDisplayOutput *);
int ambient_svp_android_stream_drain(AmbientSvpAndroidStream *,const float *,uint32_t,AmbientSvpDisplayOutput *);
void ambient_svp_android_stream_reset(AmbientSvpAndroidStream *);
/* 1 destroyed, 0 still in flight: retain stream/library/callbacks. */
int ambient_svp_android_stream_destroy(AmbientSvpAndroidStream *);
#endif
