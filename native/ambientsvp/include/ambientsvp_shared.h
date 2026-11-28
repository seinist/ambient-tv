/* SPDX-License-Identifier: Apache-2.0 */
#ifndef AMBIENT_SHARED_SVP_H
#define AMBIENT_SHARED_SVP_H
#include "ambientsvp.h"

#define AMBIENT_SVP_SHARED_ABI 1u
/* All pointers are borrowed from the same process-lifetime host library.
 * No object, allocator, C++ type or JNI reference crosses the ABI boundary.
 */
typedef struct {
    uint32_t version, size, engine_abi;
    AmbientSvp *(*create)(const AmbientSvpConfig *);
    void (*destroy)(AmbientSvp *);
    int (*reset)(AmbientSvp *);
    int (*push)(AmbientSvp *, const uint8_t *, size_t, int64_t);
    int (*receive)(AmbientSvp *, uint8_t *, size_t, int64_t *, int);
    int (*stats)(const AmbientSvp *, AmbientSvpStats *);
    void (*bypass)(AmbientSvp *, uint32_t);
    int (*enabled)(void);
} AmbientSharedSvp;
static inline int ambient_shared_svp_valid(const AmbientSharedSvp *api) {
    return api && api->version == AMBIENT_SVP_SHARED_ABI && api->size == sizeof(*api) &&
        api->engine_abi == AMBIENTSVP_ABI && api->create && api->destroy &&
        api->reset && api->push && api->receive && api->stats && api->bypass && api->enabled;
}
#endif
