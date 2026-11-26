// SPDX-License-Identifier: GPL-3.0-or-later
// Production EGL import/server-wait bodies with mock dispatch and real host FDs.
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
constexpr int EGL_EXTENSIONS = 1, GL_EXTENSIONS = 2, EGL_SYNC_NATIVE_FENCE_FD_ANDROID = 3,
    EGL_NONE = 4, EGL_SYNC_NATIVE_FENCE_ANDROID = 5, EGL_NO_SYNC_KHR = 0, EGL_TRUE = 1;
using PFNEGLCREATESYNCKHRPROC = EGLSyncKHR(*)(EGLDisplay, int, const EGLint*);
using PFNEGLDESTROYSYNCKHRPROC = EGLBoolean(*)(EGLDisplay, EGLSyncKHR);
using PFNEGLWAITSYNCKHRPROC = EGLint(*)(EGLDisplay, EGLSyncKHR, EGLint);
struct Driver {
    int display = 7, context = 9, phase = 0;
    const char* eglExtensions = "EGL_KHR_fence_sync EGL_ANDROID_native_fence_sync EGL_KHR_wait_sync";
    const char* glExtensions = "GL_OES_EGL_sync";
    bool missingWait = false, createFails = false, waitFails = false, destroyFails = false;
    unsigned queries = 0, loads = 0, creates = 0, waits = 0, destroys = 0;
} driver;
EGLDisplay eglGetCurrentDisplay() { return driver.display; }
EGLContext eglGetCurrentContext() { return driver.context; }
const char* eglQueryString(EGLDisplay display, EGLint name) {
    assert(display == 7 && name == EGL_EXTENSIONS);
    ++driver.queries; return driver.eglExtensions;
}
const unsigned char* glGetString(int name) {
    assert(name == GL_EXTENSIONS);
    return reinterpret_cast<const unsigned char*>(driver.glExtensions);
}
EGLSyncKHR createSync(EGLDisplay display, int type, const EGLint* attrs) {
    assert(display == 7 && type == EGL_SYNC_NATIVE_FENCE_ANDROID && driver.phase == 0);
    assert(attrs[0] == EGL_SYNC_NATIVE_FENCE_FD_ANDROID && attrs[1] >= 0 && attrs[2] == EGL_NONE);
    ++driver.creates;
    assert(close(attrs[1]) == 0); // mock EGL ownership, including creation failure
    if (driver.createFails) return EGL_NO_SYNC_KHR;
    driver.phase = 1; return 11;
}
EGLint waitSync(EGLDisplay display, EGLSyncKHR sync, EGLint flags) {
    assert(display == 7 && sync == 11 && flags == 0 && driver.phase == 1);
    driver.phase = 2; ++driver.waits; return !driver.waitFails;
}
EGLBoolean destroySync(EGLDisplay display, EGLSyncKHR sync) {
    assert(display == 7 && sync == 11 && driver.phase == 2);
    driver.phase = 0; ++driver.destroys; return !driver.destroyFails;
}
using Proc = void(*)();
Proc eglGetProcAddress(const char* name) {
    ++driver.loads;
    if (!std::strcmp(name, "eglCreateSyncKHR")) return reinterpret_cast<Proc>(createSync);
    if (!std::strcmp(name, "eglDestroySyncKHR")) return reinterpret_cast<Proc>(destroySync);
    assert(!std::strcmp(name, "eglWaitSyncKHR"));
    return driver.missingWait ? nullptr : reinterpret_cast<Proc>(waitSync);
}
struct Video {
    unsigned hwRenderTexture = 1;
    struct Lease {
        bool ownsContext(EGLDisplay display, EGLContext context) const { return display == 7 && context == 9; }
    } externalFrame;
    bool writeFenceProbed = false;
    PFNEGLCREATESYNCKHRPROC createWriteSync = nullptr;
    PFNEGLDESTROYSYNCKHRPROC destroyWriteSync = nullptr;
    PFNEGLWAITSYNCKHRPROC waitWriteSync = nullptr;
    bool canImportExternalWriteFence();
    bool importExternalWriteFence(int fd);
};
#define __ANDROID__ 1
#include "write_fence_methods.h"
#undef __ANDROID__
static int newFd() {
    int fds[2]; assert(pipe(fds) == 0 && close(fds[1]) == 0); return fds[0];
}
static void closed(int fd) {
    errno = 0; assert(fcntl(fd, F_GETFD) == -1 && errno == EBADF);
}
int main() {
    const char* unsupported[] = {nullptr, "", "EGL_KHR_fence_sync EGL_ANDROID_native_fence_sync",
        "EGL_KHR_fence_sync EGL_ANDROID_native_fence_sync EGL_KHR_wait_sync_extra",
        "EGL_KHR_fence_sync EGL_ANDROID_native_fence_sync X_EGL_KHR_wait_sync"};
    for (const auto* extensions : unsupported) {
        driver = {}; driver.eglExtensions = extensions; Video video;
        int fd = newFd(); assert(!video.importExternalWriteFence(fd)); closed(fd);
        assert(!video.canImportExternalWriteFence() && driver.queries == 1 && driver.loads == 0);
    }
    driver = {}; driver.glExtensions = "GL_OES_EGL_sync_other"; Video wrongGl;
    int fd = newFd(); assert(!wrongGl.importExternalWriteFence(fd)); closed(fd);
    driver = {}; Video video;
    assert(video.importExternalWriteFence(-1) && driver.queries == 0);
    assert(!video.importExternalWriteFence(-2));
    driver.context = 8; fd = newFd(); assert(!video.importExternalWriteFence(fd)); closed(fd);
    assert(driver.queries == 0);
    driver.context = 9;
    for (unsigned i = 0; i < 10000; ++i) {
        fd = newFd(); assert(video.importExternalWriteFence(fd)); closed(fd);
    }
    assert(driver.queries == 1 && driver.loads == 3 && driver.creates == 10000);
    assert(driver.waits == 10000 && driver.destroys == 10000);
    driver.context = 0; fd = newFd(); assert(!video.importExternalWriteFence(fd)); closed(fd);
    for (unsigned error = 0; error < 4; ++error) {
        driver = {}; Video failure;
        driver.missingWait = error == 0; driver.createFails = error == 1;
        driver.waitFails = error == 2; driver.destroyFails = error == 3;
        fd = newFd(); assert(!failure.importExternalWriteFence(fd)); closed(fd);
        assert(!failure.canImportExternalWriteFence());
        const unsigned creates = driver.creates;
        fd = newFd(); assert(!failure.importExternalWriteFence(fd)); closed(fd);
        assert(driver.creates == creates);
        assert(driver.destroys == (error >= 2 ? 1u : 0u));
    }
    std::puts("PASS EGL reverse fence ownership, server wait, failures and 10000 FD transfers");
}
