// SPDX-License-Identifier: GPL-3.0-or-later
// Execute the complete production JNI step wrappers. The exception is created
// in a separately compiled translation unit with the real global C++ header,
// so a same-spelled nested namespace type cannot accidentally pass this test.
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <stdexcept>

#define JNIEXPORT
#define JNICALL
#define LOGE(...) ((void)0)
using jclass = void*;
using jobject = void*;
using jmethodID = void*;
using jlong = int64_t;
struct JNIEnv {
    unsigned managedErrors = 0, javaCalls = 0;
    int errorCode = 0;
    jclass GetObjectClass(jobject) { return nullptr; }
    jmethodID GetMethodID(jclass, const char*, const char*) { return nullptr; }
    template<class... Args> void CallVoidMethod(jobject, jmethodID, Args...) { ++javaCalls; }
};
void throwProductionVulkanError();

namespace libretrodroid {
struct LibretroDroid {
    bool fail = false;
    unsigned frames = 0, refreshChecks = 0;
    static LibretroDroid& getInstance() { static LibretroDroid host; return host; }
    void step() { ++frames; if (fail) throwProductionVulkanError(); }
    void stepForNetplay() { ++frames; if (fail) throwProductionVulkanError(); }
    bool requiresVideoRefresh() { ++refreshChecks; return true; }
    void clearRequiresVideoRefresh() {}
    template<class Callback> void handleCoreSensorRequests(Callback callback) { callback(0); }
    bool isRumbleEnabled() { return true; }
    template<class Callback> void handleRumbleUpdates(Callback callback) { callback(0,0.0f,0.0f); }
    template<class Callback> void handleAchievementUnlocks(Callback callback) { callback(0); }
};
struct JavaUtils {
    static void throwRetroException(JNIEnv* env, int code) {
        ++env->managedErrors;
        env->errorCode = code;
    }
};
}

// Preserves the real exception-header include scope and both unmodified JNI
// function bodies. Unrelated Android/emulator APIs above are controlled inputs.
#include "vulkan_jni_boundaries_under_test.h"

int main() {
    using Entry = void (*)(JNIEnv*, jclass, jobject);
    const Entry entries[] {
        libretrodroid::Java_com_swordfish_libretrodroid_LibretroDroid_step,
        libretrodroid::Java_com_swordfish_libretrodroid_LibretroDroid_stepForNetplay,
    };
    for (Entry entry : entries) {
        auto& host = libretrodroid::LibretroDroid::getInstance();
        host = {};
        JNIEnv env;
        entry(&env,nullptr,nullptr);
        assert(host.frames == 1 && host.refreshChecks == 1 && env.javaCalls == 4);
        assert(env.managedErrors == 0);
        host = {};
        host.fail = true;
        env = {};
        bool escaped = false;
        try { entry(&env,nullptr,nullptr); }
        catch (const std::exception&) { escaped = true; }
        assert(!escaped && host.frames == 1 && host.refreshChecks == 0);
        assert(env.managedErrors == 1 && env.errorCode == -1 && env.javaCalls == 0);
    }
    std::puts("PASS production JNI step/netplay catches match the real cross-TU exception type");
}
