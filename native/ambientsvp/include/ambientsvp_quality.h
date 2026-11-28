/* SPDX-License-Identifier: Apache-2.0 */
#ifndef AMBIENTSVP_QUALITY_H
#define AMBIENTSVP_QUALITY_H
#include <stdint.h>
typedef struct {
    uint32_t preset,block,overlap,pel,search,artifacts,scene,algorithm;
} AmbientSvpQuality;
#endif
