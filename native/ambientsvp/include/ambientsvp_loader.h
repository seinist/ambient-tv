/* SPDX-License-Identifier: Apache-2.0. Optional per-filter dynamic dependency. */
#ifndef AMBIENTSVP_LOADER_H
#define AMBIENTSVP_LOADER_H
#include "ambientsvp.h"
#include <dlfcn.h>
#include <string.h>
typedef struct {
    void *lib;
    AmbientSvp *(*create)(const AmbientSvpConfig *);
    void (*destroy)(AmbientSvp *);
    int (*reset)(AmbientSvp *);
    int (*push)(AmbientSvp *, const uint8_t *, size_t, int64_t);
    int (*push_fill)(AmbientSvp *, int64_t, void *, AmbientSvpFill);
    int (*receive)(AmbientSvp *, uint8_t *, size_t, int64_t *, int);
    int (*ready)(AmbientSvp *, int);
    int (*receive_visit)(AmbientSvp *, void *, AmbientSvpVisit, int64_t *, int);
    int (*stats)(const AmbientSvp *, AmbientSvpStats *);
    void (*bypass)(AmbientSvp *, uint32_t);
    int (*enabled)(void);
} SvpApi;
static inline int svp_load(SvpApi *api) {
    api->lib = dlopen("libambientsvp.so", RTLD_NOW | RTLD_LOCAL);
    if (!api->lib) return 0;
    uint32_t (*abi)(void) = dlsym(api->lib, "ambient_svp_abi");
#define SVP_LOAD(name) api->name = dlsym(api->lib, "ambient_svp_" #name); if (!api->name) goto fail
    SVP_LOAD(create); SVP_LOAD(destroy); SVP_LOAD(push); SVP_LOAD(receive);
    SVP_LOAD(reset);
    SVP_LOAD(stats); SVP_LOAD(bypass); SVP_LOAD(enabled);
#undef SVP_LOAD
    if (!abi || abi() != AMBIENTSVP_ABI) goto fail;
    /* Optional on older ABI-compatible engines; retain legacy packed path. */
    api->push_fill = dlsym(api->lib, "ambient_svp_push_fill");
    api->ready = dlsym(api->lib, "ambient_svp_ready");
    api->receive_visit = dlsym(api->lib, "ambient_svp_receive_visit");
    return 1;
fail:
    dlclose(api->lib); memset(api, 0, sizeof(*api)); return 0;
}
#endif
