// SPDX-License-Identifier: GPL-3.0-or-later
// Exercise the actual Android export body with controlled EGL dispatch and
// real host file descriptors. No driver interoperability is claimed.
#include <cassert>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>
using EGLDisplay = int;
using EGLContext = int;
using EGLSyncKHR = int;
using EGLint = int;
using EGLBoolean = int;
constexpr int EGL_EXTENSIONS = 1, EGL_SYNC_NATIVE_FENCE_FD_ANDROID = 2,
    EGL_NO_NATIVE_FENCE_FD_ANDROID = -1, EGL_NONE = 3,
    EGL_SYNC_NATIVE_FENCE_ANDROID = 4, EGL_NO_SYNC_KHR = 0, EGL_TRUE = 1;
using PFNEGLCREATESYNCKHRPROC = EGLSyncKHR(*)(EGLDisplay, int, const EGLint*);
using PFNEGLDESTROYSYNCKHRPROC = EGLBoolean(*)(EGLDisplay, EGLSyncKHR);
using PFNEGLDUPNATIVEFENCEFDANDROIDPROC = int(*)(EGLDisplay, EGLSyncKHR);

struct Driver {
    int display = 7, context = 9;
    const char* extensions = "EGL_KHR_fence_sync EGL_ANDROID_native_fence_sync";
    bool missingProc = false, createFails = false, duplicateFails = false, destroyFails = false;
    unsigned queries = 0, loads = 0, creates = 0, flushes = 0, duplicates = 0, destroys = 0;
    int issuedFd = -1, phase = 0;
} driver;
EGLDisplay eglGetCurrentDisplay() { return driver.display; }
EGLContext eglGetCurrentContext() { return driver.context; }
const char* eglQueryString(EGLDisplay display, EGLint name) {
    assert(display == 7 && name == EGL_EXTENSIONS);
    ++driver.queries; return driver.extensions;
}
EGLSyncKHR createSync(EGLDisplay display, int type, const EGLint* attrs) {
    assert(display == 7 && type == EGL_SYNC_NATIVE_FENCE_ANDROID && driver.phase == 0);
    assert(attrs[0] == EGL_SYNC_NATIVE_FENCE_FD_ANDROID && attrs[1] == -1 && attrs[2] == EGL_NONE);
    ++driver.creates;
    if (driver.createFails) return 0;
    driver.phase = 1; return 11;
}
void glFlush() { assert(driver.phase == 1); driver.phase = 2; ++driver.flushes; }
int dupFence(EGLDisplay display, EGLSyncKHR sync) {
    assert(display == 7 && sync == 11 && driver.phase == 2);
    driver.phase = 3; ++driver.duplicates;
    if (driver.duplicateFails) return -1;
    int fds[2];
    assert(pipe(fds) == 0 && close(fds[1]) == 0);
    return driver.issuedFd = fds[0];
}
EGLBoolean destroySync(EGLDisplay display, EGLSyncKHR sync) {
    assert(display == 7 && sync == 11 && driver.phase == 3);
    driver.phase = 0; ++driver.destroys; return !driver.destroyFails;
}
using Proc = void(*)();
Proc eglGetProcAddress(const char* name) {
    ++driver.loads;
    if (!std::strcmp(name, "eglCreateSyncKHR")) return reinterpret_cast<Proc>(createSync);
    if (!std::strcmp(name, "eglDestroySyncKHR")) return reinterpret_cast<Proc>(destroySync);
    assert(!std::strcmp(name, "eglDupNativeFenceFDANDROID"));
    return driver.missingProc ? nullptr : reinterpret_cast<Proc>(dupFence);
}
struct Video {
    unsigned hwRenderTexture = 1;
    struct Lease {
        bool ownsContext(EGLDisplay display, EGLContext context) const { return display == 7 && context == 9; }
    } externalFrame;
    bool externalFenceProbed = false;
    PFNEGLCREATESYNCKHRPROC createExternalSync = nullptr;
    PFNEGLDESTROYSYNCKHRPROC destroyExternalSync = nullptr;
    PFNEGLDUPNATIVEFENCEFDANDROIDPROC duplicateExternalFence = nullptr;
    int exportExternalReadFence();
};
#define __ANDROID__ 1
#include "external_fence_export_under_test.h"
#undef __ANDROID__

int main() {
    // Exact extension tokens, including adjacent prefix/suffix lookalikes.
    const char* unsupported[] = {nullptr, "", "EGL_KHR_fence_sync",
        "EGL_ANDROID_native_fence_sync", "X_EGL_KHR_fence_sync EGL_ANDROID_native_fence_sync",
        "EGL_KHR_fence_sync EGL_ANDROID_native_fence_sync_extra"};
    for (const char* extensions : unsupported) {
        driver = {}; driver.extensions = extensions; Video video;
        assert(video.exportExternalReadFence() == -1 && video.exportExternalReadFence() == -1);
        assert(driver.queries == 1 && driver.loads == 0 && driver.creates == 0);
    }
    driver = {}; Video video;
    driver.context = 8;
    assert(video.exportExternalReadFence() == -1 && driver.queries == 0);
    driver.context = 9; driver.display = 8;
    assert(video.exportExternalReadFence() == -1 && driver.queries == 0);
    driver.display = 7; video.hwRenderTexture = 0;
    assert(video.exportExternalReadFence() == -1 && driver.queries == 0);
    video.hwRenderTexture = 1;
    for (unsigned i = 0; i < 1000; ++i) {
        int fd = video.exportExternalReadFence();
        assert(fd >= 0 && fd == driver.issuedFd && fcntl(fd, F_GETFD) >= 0);
        assert(close(fd) == 0);
    }
    assert(driver.queries == 1 && driver.loads == 3 && driver.creates == 1000);
    assert(driver.flushes == 1000 && driver.duplicates == 1000 && driver.destroys == 1000);
    driver.context = 0;
    assert(video.exportExternalReadFence() == -1 && driver.creates == 1000);
    driver = {}; driver.missingProc = true; Video missing;
    assert(missing.exportExternalReadFence() == -1 && driver.loads == 3 && driver.creates == 0);
    driver = {}; driver.createFails = true; Video createFailure;
    assert(createFailure.exportExternalReadFence() == -1 && driver.creates == 1 && driver.flushes == 0);
    driver = {}; driver.duplicateFails = true; Video duplicateFailure;
    assert(duplicateFailure.exportExternalReadFence() == -1 && driver.destroys == 1);
    driver = {}; driver.destroyFails = true; Video destroyFailure;
    assert(destroyFailure.exportExternalReadFence() == -1 && driver.destroys == 1);
    errno = 0;
    assert(driver.issuedFd >= 0 && fcntl(driver.issuedFd, F_GETFD) == -1 && errno == EBADF);
    std::puts("PASS Android EGL native fence export: context, extensions, dispatch, order, failures, 1000 FD transfers");
}
