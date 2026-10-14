#include <jni.h>
#include <projectM-4/projectM.h>
#include <algorithm>
#include <mutex>
#include <string>
#include <vector>
#include <array>
#include <atomic>
#include <cstring>
#include <cstdint>

#include "native_audio_tap.h"

struct AmbientProjectM {
    projectm_handle instance = nullptr;
    int audioSource = 0;
    std::mutex mutex;
    std::vector<float> pcm;
    std::string pendingPreset;
    bool pendingSmooth = false;
    std::string pendingTexturePath;
    bool pendingLocked = false;
    bool pendingLockedDirty = false;
};

static AmbientProjectM* ptr(jlong handle) {
    return reinterpret_cast<AmbientProjectM*>(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ambient_tv_ui_common_projectm_ProjectMNativeBridge_nativeCreate(JNIEnv*, jobject) {
    auto* state = new AmbientProjectM();
    state->instance = projectm_create();
    if (!state->instance) {
        delete state;
        return 0;
    }
    projectm_set_fps(state->instance, 60);
    projectm_set_aspect_correction(state->instance, true);
    projectm_set_hard_cut_enabled(state->instance, false);
    return reinterpret_cast<jlong>(state);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ambient_tv_ui_common_projectm_ProjectMNativeBridge_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    auto* state = ptr(handle);
    if (!state) return;
    if (state->audioSource) nativeAudio[state->audioSource].consumers.fetch_sub(1);
    if (state->instance) projectm_destroy(state->instance);
    delete state;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ambient_tv_ui_common_projectm_ProjectMNativeBridge_nativeSetAudioSource(JNIEnv*, jobject, jlong handle, jint source) {
    auto* state = ptr(handle);
    if (!state || source < 0 || source > 2 || state->audioSource == source) return;
    if (state->audioSource) nativeAudio[state->audioSource].consumers.fetch_sub(1);
    state->audioSource = source;
    if (source) {
        auto& tap = nativeAudio[source];
        std::lock_guard<std::mutex> lock(tap.mutex);
        tap.count = 0;
        tap.consumers.fetch_add(1);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_ambient_tv_ui_common_projectm_ProjectMNativeBridge_nativeResize(JNIEnv*, jobject, jlong handle, jint width, jint height, jint meshX, jint meshY) {
    auto* state = ptr(handle);
    if (!state || !state->instance) return;
    projectm_set_window_size(state->instance, std::max(1, width), std::max(1, height));
    projectm_set_mesh_size(state->instance, std::max(8, meshX), std::max(8, meshY));
}

extern "C" JNIEXPORT void JNICALL
Java_com_ambient_tv_ui_common_projectm_ProjectMNativeBridge_nativeFeedPcm(JNIEnv* env, jobject, jlong handle, jfloatArray samples) {
    auto* state = ptr(handle);
    if (!state || !state->instance || !samples) return;
    const jsize count = env->GetArrayLength(samples);
    if (count <= 0) return;
    std::vector<float> incoming(static_cast<size_t>(count));
    env->GetFloatArrayRegion(samples, 0, count, incoming.data());
    std::lock_guard<std::mutex> guard(state->mutex);
    const unsigned maxSamples = projectm_pcm_get_max_samples();
    if (incoming.size() > maxSamples) incoming.erase(incoming.begin(), incoming.end() - maxSamples);
    state->pcm = std::move(incoming);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ambient_tv_ui_common_projectm_ProjectMNativeBridge_nativeLoadPreset(JNIEnv* env, jobject, jlong handle, jstring presetData, jboolean smooth) {
    auto* state = ptr(handle);
    if (!state || !presetData) return;
    const char* raw = env->GetStringUTFChars(presetData, nullptr);
    if (!raw) return;
    {
        std::lock_guard<std::mutex> guard(state->mutex);
        state->pendingPreset = raw;
        state->pendingSmooth = smooth == JNI_TRUE;
    }
    env->ReleaseStringUTFChars(presetData, raw);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ambient_tv_ui_common_projectm_ProjectMNativeBridge_nativeSetTexturePath(JNIEnv* env, jobject, jlong handle, jstring path) {
    auto* state = ptr(handle);
    if (!state || !path) return;
    const char* raw = env->GetStringUTFChars(path, nullptr);
    if (!raw) return;
    {
        std::lock_guard<std::mutex> guard(state->mutex);
        state->pendingTexturePath = raw;
    }
    env->ReleaseStringUTFChars(path, raw);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ambient_tv_ui_common_projectm_ProjectMNativeBridge_nativeSetPresetDuration(JNIEnv*, jobject, jlong handle, jdouble seconds) {
    auto* state = ptr(handle);
    if (state && state->instance) projectm_set_preset_duration(state->instance, std::max(1.0, seconds));
}

extern "C" JNIEXPORT void JNICALL
Java_com_ambient_tv_ui_common_projectm_ProjectMNativeBridge_nativeSetSoftCutDuration(JNIEnv*, jobject, jlong handle, jdouble seconds) {
    auto* state = ptr(handle);
    if (state && state->instance) projectm_set_soft_cut_duration(state->instance, std::max(0.0, seconds));
}

extern "C" JNIEXPORT void JNICALL
Java_com_ambient_tv_ui_common_projectm_ProjectMNativeBridge_nativeSetBeatSensitivity(JNIEnv*, jobject, jlong handle, jfloat sensitivity) {
    auto* state = ptr(handle);
    if (state && state->instance) projectm_set_beat_sensitivity(state->instance, std::clamp(sensitivity, 0.0f, 5.0f));
}

extern "C" JNIEXPORT void JNICALL
Java_com_ambient_tv_ui_common_projectm_ProjectMNativeBridge_nativeSetLocked(JNIEnv*, jobject, jlong handle, jboolean locked) {
    auto* state = ptr(handle);
    if (!state || !state->instance) return;
    std::lock_guard<std::mutex> guard(state->mutex);
    state->pendingLocked = locked == JNI_TRUE;
    state->pendingLockedDirty = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ambient_tv_ui_common_projectm_ProjectMNativeBridge_nativeRender(JNIEnv*, jobject, jlong handle) {
    auto* state = ptr(handle);
    if (!state || !state->instance) return;
    std::vector<float> pcm;
    std::string preset;
    std::string texturePath;
    bool smooth = false;
    bool locked = false;
    bool lockedDirty = false;
    {
        std::lock_guard<std::mutex> guard(state->mutex);
        pcm.swap(state->pcm);
        preset.swap(state->pendingPreset);
        texturePath.swap(state->pendingTexturePath);
        smooth = state->pendingSmooth;
        locked = state->pendingLocked;
        lockedDirty = state->pendingLockedDirty;
        state->pendingLockedDirty = false;
    }
    if (state->audioSource) {
        auto& tap = nativeAudio[state->audioSource];
        std::lock_guard<std::mutex> lock(tap.mutex);
        pcm.assign(tap.samples.begin(), tap.samples.begin() + tap.count);
        tap.count = 0;
    }
    if (!texturePath.empty()) {
        const char* paths[] = { texturePath.c_str() };
        projectm_set_texture_search_paths(state->instance, paths, 1);
    }
    if (!preset.empty()) projectm_load_preset_data(state->instance, preset.c_str(), smooth);
    if (lockedDirty) projectm_set_preset_locked(state->instance, locked);
    if (!pcm.empty()) projectm_pcm_add_float(state->instance, pcm.data(), static_cast<unsigned>(pcm.size()), PROJECTM_MONO);
    projectm_opengl_render_frame(state->instance);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ambient_tv_ui_common_projectm_ProjectMNativeBridge_nativeVersion(JNIEnv* env, jobject) {
    char* version = projectm_get_version_string();
    if (!version) return env->NewStringUTF("");
    jstring result = env->NewStringUTF(version);
    projectm_free_string(version);
    return result;
}
