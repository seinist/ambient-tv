#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdbool.h>
#include <string.h>
#include <strings.h>
#include <mpv/client.h>
#include <libavcodec/jni.h>
#include <libavcodec/avcodec.h>
#include <android/log.h>
#include <sys/system_properties.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <unistd.h>

typedef struct {
    mpv_handle *mpv;
    jobject surface;
    int playback_error;
    int end_reason;
    /* Self-pipe written by mpv's wakeup callback; a Java thread waits on the read end so the
     * UI thread only drains events when mpv actually has some. -1 when unavailable. */
    int wakeup_pipe[2];
    /* Copy of the last MPV_EVENT_PROPERTY_CHANGE, read by Java right after nativePollEvent. */
    uint64_t prop_id;
    int prop_format;
    int prop_flag;
    double prop_double;
    char prop_string[512];
} ambient_mpv_context;

static ambient_mpv_context *ctx_from(jlong handle) {
    return (ambient_mpv_context *)(intptr_t)handle;
}

/*
 * libmpv log level forwarded to Logcat. Default "warn": verbose output floods the event queue
 * that is drained on the UI thread and prints stream URLs including X-Plex-Token/ApiKey.
 * Developers can restore it with: adb shell setprop debug.ambienttv.mpv.log v
 */
static const char *requested_log_level(void) {
    static char value[PROP_VALUE_MAX];
    static const char *allowed[] = { "no", "fatal", "error", "warn", "info", "status", "v", "debug", "trace" };
    if (__system_property_get("debug.ambienttv.mpv.log", value) > 0) {
        for (size_t i = 0; i < sizeof(allowed) / sizeof(allowed[0]); ++i)
            if (strcmp(value, allowed[i]) == 0) return allowed[i];
    }
    return "warn";
}

/* Replaces the values of credential query parameters in place, e.g. X-Plex-Token=abc -> X-Plex-Token=***. */
static void redact_credentials(char *text, size_t capacity) {
    static const char *keys[] = { "x-plex-token=", "apikey=", "api_key=", "access_token=" };
    for (size_t k = 0; k < sizeof(keys) / sizeof(keys[0]); ++k) {
        size_t key_len = strlen(keys[k]);
        for (char *p = text; *p; ++p) {
            if (strncasecmp(p, keys[k], key_len) != 0) continue;
            char *value = p + key_len;
            char *end = value;
            while (*end && *end != '&' && *end != ' ' && *end != '\n' && *end != '"' && *end != '\'') ++end;
            if (end == value) continue;
            size_t tail = strlen(end) + 1;
            if ((size_t)(value - text) + 3 + tail > capacity) continue;
            memmove(value + 3, end, tail);
            memcpy(value, "***", 3);
            p = value + 2;
        }
    }
}

/* Replaces "tail" (up to "end") with "***" in place and returns the new end. */
static char *redact_tail(char *tail, char *end) {
    if (end - tail <= 3) return end;
    memmove(tail + 3, end, strlen(end) + 1);
    memcpy(tail, "***", 3);
    return tail + 3;
}

/* Drops URL queries: signed stream URLs carry the client IP, PoTokens and signatures there.
 * googlevideo also puts them into path segments (/ip/.../expire/...), so its path goes too.
 * Same rules as String.withoutUrlQueries() on the Kotlin side. */
static void redact_url_queries(char *text) {
    for (char *p = strstr(text, "://"); p; p = strstr(p, "://")) {
        char *end = p + 3;
        while (*end && *end != ' ' && *end != '\n' && *end != '"' && *end != '\'') ++end;
        char *path = memchr(p + 3, '/', (size_t)(end - p - 3));
        char *query = memchr(p, '?', (size_t)(end - p));
        char *host_end = path ? path : (query ? query : end);
        size_t host_len = (size_t)(host_end - p - 3);
        bool googlevideo = host_len >= 15 && strncasecmp(host_end - 15, "googlevideo.com", 15) == 0;
        if (googlevideo && host_end < end) end = redact_tail(host_end + 1, end);
        else if (query) end = redact_tail(query + 1, end);
        p = end;
    }
}

static void on_mpv_wakeup(void *data) {
    /* Called from arbitrary mpv threads; must not call back into mpv. Never blocks (O_NONBLOCK). */
    ambient_mpv_context *ctx = data;
    if (ctx && ctx->wakeup_pipe[1] >= 0) {
        char byte = 1;
        ssize_t ignored = write(ctx->wakeup_pipe[1], &byte, 1);
        (void) ignored;
    }
}

static void close_wakeup_pipe(ambient_mpv_context *ctx) {
    for (int i = 0; i < 2; ++i) {
        if (ctx->wakeup_pipe[i] >= 0) close(ctx->wakeup_pipe[i]);
        ctx->wakeup_pipe[i] = -1;
    }
}

static jstring new_utf(JNIEnv *env, const char *value) {
    if (!value) return NULL;
    return (*env)->NewStringUTF(env, value);
}

JNIEXPORT jint JNICALL
Java_com_ambient_tv_ui_player_backend_MpvNativeBridge_nativeVulkanSupport(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    mpv_handle *mpv = mpv_create();
    if (!mpv) return 0;
    int result = 0;
    if (mpv_set_option_string(mpv, "gpu-context", "androidvk") >= 0 &&
        mpv_set_option_string(mpv, "vo", "gpu-next") >= 0) result |= 1;
    mpv_terminate_destroy(mpv);
    const enum AVCodecID ids[] = {AV_CODEC_ID_H264, AV_CODEC_ID_HEVC, AV_CODEC_ID_AV1, AV_CODEC_ID_VP9};
    for (size_t c = 0; c < sizeof(ids) / sizeof(ids[0]); ++c) {
        const AVCodec *codec = avcodec_find_decoder(ids[c]);
        if (!codec) continue;
        const AVCodecHWConfig *config;
        for (int i = 0; (config = avcodec_get_hw_config(codec, i)); ++i)
            if (config->device_type == AV_HWDEVICE_TYPE_VULKAN) result |= 2;
    }
    return result;
}

JNIEXPORT jint JNICALL
Java_com_ambient_tv_ui_player_backend_MpvNativeBridge_nativePlaybackError(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    ambient_mpv_context *ctx = ctx_from(handle);
    return ctx ? ctx->playback_error : 0;
}

JNIEXPORT jint JNICALL
Java_com_ambient_tv_ui_player_backend_MpvNativeBridge_nativeEndReason(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    ambient_mpv_context *ctx = ctx_from(handle);
    return ctx ? ctx->end_reason : -1;
}

JNIEXPORT jstring JNICALL
Java_com_ambient_tv_ui_player_backend_MpvNativeBridge_nativeVersion(JNIEnv *env, jobject thiz) {
    (void) thiz;
    unsigned long version = mpv_client_api_version();
    char buffer[64];
    snprintf(buffer, sizeof(buffer), "libmpv-client-api-%u.%u",
             (unsigned int)((version >> 16) & 0xffffu), (unsigned int)(version & 0xffffu));
    return new_utf(env, buffer);
}

JNIEXPORT jlong JNICALL
Java_com_ambient_tv_ui_player_backend_MpvNativeBridge_nativeCreate(JNIEnv *env, jobject thiz) {
    (void) thiz;
    // Both Android video output and MediaCodec obtain the VM through FFmpeg.
    JavaVM *vm = NULL;
    if ((*env)->GetJavaVM(env, &vm) != JNI_OK || av_jni_set_java_vm(vm, NULL) < 0)
        return 0;
    ambient_mpv_context *ctx = calloc(1, sizeof(*ctx));
    if (!ctx) return 0;
    ctx->wakeup_pipe[0] = ctx->wakeup_pipe[1] = -1;
    ctx->mpv = mpv_create();
    if (!ctx->mpv) { free(ctx); return 0; }
    int fds[2];
    if (pipe(fds) == 0) {
        for (int i = 0; i < 2; ++i) {
            fcntl(fds[i], F_SETFL, fcntl(fds[i], F_GETFL) | O_NONBLOCK);
            fcntl(fds[i], F_SETFD, FD_CLOEXEC);
        }
        ctx->wakeup_pipe[0] = fds[0];
        ctx->wakeup_pipe[1] = fds[1];
        mpv_set_wakeup_callback(ctx->mpv, on_mpv_wakeup, ctx);
    }
    // Level and redaction: see requested_log_level()/redact_credentials(). HDR/AV diagnostics
    // that matter in the field are logged by the Kotlin controller (HDR-AUDIT/AV-AUDIT).
    mpv_request_log_messages(ctx->mpv, requested_log_level());
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT jint JNICALL
Java_com_ambient_tv_ui_player_backend_MpvNativeBridge_nativeInitialize(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env; (void) thiz;
    ambient_mpv_context *ctx = ctx_from(handle);
    return ctx && ctx->mpv ? mpv_initialize(ctx->mpv) : MPV_ERROR_INVALID_PARAMETER;
}

JNIEXPORT void JNICALL
Java_com_ambient_tv_ui_player_backend_MpvNativeBridge_nativeDestroy(JNIEnv *env, jobject thiz, jlong handle) {
    (void) thiz;
    ambient_mpv_context *ctx = ctx_from(handle);
    if (!ctx) return;
    // Stop the renderer before releasing the Java Surface it may still use.
    if (ctx->mpv) {
        mpv_set_wakeup_callback(ctx->mpv, NULL, NULL);
        mpv_terminate_destroy(ctx->mpv);
    }
    // The Java waiter polls this fd directly (never ctx), so closing it ends its wait safely.
    close_wakeup_pipe(ctx);
    if (ctx->surface) {
        (*env)->DeleteGlobalRef(env, ctx->surface);
        ctx->surface = NULL;
    }
    free(ctx);
}

JNIEXPORT jint JNICALL
Java_com_ambient_tv_ui_player_backend_MpvNativeBridge_nativeSetOptionString(JNIEnv *env, jobject thiz, jlong handle, jstring name, jstring value) {
    (void) thiz;
    ambient_mpv_context *ctx = ctx_from(handle);
    if (!ctx || !ctx->mpv || !name || !value) return MPV_ERROR_INVALID_PARAMETER;
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    const char *v = (*env)->GetStringUTFChars(env, value, NULL);
    int result = mpv_set_option_string(ctx->mpv, n, v);
    (*env)->ReleaseStringUTFChars(env, name, n);
    (*env)->ReleaseStringUTFChars(env, value, v);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_ambient_tv_ui_player_backend_MpvNativeBridge_nativeSetPropertyString(JNIEnv *env, jobject thiz, jlong handle, jstring name, jstring value) {
    (void) thiz;
    ambient_mpv_context *ctx = ctx_from(handle);
    if (!ctx || !ctx->mpv || !name || !value) return MPV_ERROR_INVALID_PARAMETER;
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    const char *v = (*env)->GetStringUTFChars(env, value, NULL);
    int result = mpv_set_property_string(ctx->mpv, n, v);
    (*env)->ReleaseStringUTFChars(env, name, n);
    (*env)->ReleaseStringUTFChars(env, value, v);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_ambient_tv_ui_player_backend_MpvNativeBridge_nativeSetPropertyDouble(JNIEnv *env, jobject thiz, jlong handle, jstring name, jdouble value) {
    (void) thiz;
    ambient_mpv_context *ctx = ctx_from(handle);
    if (!ctx || !ctx->mpv || !name) return MPV_ERROR_INVALID_PARAMETER;
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    double v = value;
    int result = mpv_set_property(ctx->mpv, n, MPV_FORMAT_DOUBLE, &v);
    (*env)->ReleaseStringUTFChars(env, name, n);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_ambient_tv_ui_player_backend_MpvNativeBridge_nativeGetPropertyString(JNIEnv *env, jobject thiz, jlong handle, jstring name) {
    (void) thiz;
    ambient_mpv_context *ctx = ctx_from(handle);
    if (!ctx || !ctx->mpv || !name) return NULL;
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    char *value = mpv_get_property_string(ctx->mpv, n);
    (*env)->ReleaseStringUTFChars(env, name, n);
    if (!value) return NULL;
    jstring result = new_utf(env, value);
    mpv_free(value);
    return result;
}

JNIEXPORT jdouble JNICALL
Java_com_ambient_tv_ui_player_backend_MpvNativeBridge_nativeGetPropertyDouble(JNIEnv *env, jobject thiz, jlong handle, jstring name, jdouble fallback) {
    (void) thiz;
    ambient_mpv_context *ctx = ctx_from(handle);
    if (!ctx || !ctx->mpv || !name) return fallback;
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    double value = fallback;
    if (mpv_get_property(ctx->mpv, n, MPV_FORMAT_DOUBLE, &value) < 0) value = fallback;
    (*env)->ReleaseStringUTFChars(env, name, n);
    return value;
}

JNIEXPORT jint JNICALL
Java_com_ambient_tv_ui_player_backend_MpvNativeBridge_nativeCommand(JNIEnv *env, jobject thiz, jlong handle, jobjectArray args) {
    (void) thiz;
    ambient_mpv_context *ctx = ctx_from(handle);
    if (!ctx || !ctx->mpv || !args) return MPV_ERROR_INVALID_PARAMETER;
    jsize count = (*env)->GetArrayLength(env, args);
    const char **argv = calloc((size_t)count + 1, sizeof(char *));
    jstring *refs = calloc((size_t)count, sizeof(jstring));
    if (!argv || !refs) { free(argv); free(refs); return MPV_ERROR_NOMEM; }
    int result = 0;
    jsize filled = 0;
    for (; filled < count; ++filled) {
        refs[filled] = (jstring)(*env)->GetObjectArrayElement(env, args, filled);
        if (!refs[filled]) { argv[filled] = ""; continue; }
        argv[filled] = (*env)->GetStringUTFChars(env, refs[filled], NULL);
        // NULL means out of memory (an OutOfMemoryError is pending): a NULL entry would end
        // mpv's argument list early and must never reach ReleaseStringUTFChars.
        if (!argv[filled]) {
            (*env)->DeleteLocalRef(env, refs[filled]);
            refs[filled] = NULL;
            result = MPV_ERROR_NOMEM;
            break;
        }
    }
    if (result == 0) {
        argv[count] = NULL;
        result = mpv_command(ctx->mpv, argv);
    }
    for (jsize i = 0; i < filled; ++i) {
        if (refs[i]) {
            (*env)->ReleaseStringUTFChars(env, refs[i], argv[i]);
            (*env)->DeleteLocalRef(env, refs[i]);
        }
    }
    free(argv); free(refs);
    return result;
}

JNIEXPORT void JNICALL
Java_com_ambient_tv_ui_player_backend_MpvNativeBridge_nativeAttachSurface(JNIEnv *env, jobject thiz, jlong handle, jobject surface) {
    (void) thiz;
    ambient_mpv_context *ctx = ctx_from(handle);
    if (!ctx || !ctx->mpv || !surface) return;
    if (ctx->surface) (*env)->DeleteGlobalRef(env, ctx->surface);
    ctx->surface = (*env)->NewGlobalRef(env, surface);
    int64_t wid = (int64_t)(intptr_t)ctx->surface;
    mpv_set_option(ctx->mpv, "wid", MPV_FORMAT_INT64, &wid);
}

JNIEXPORT void JNICALL
Java_com_ambient_tv_ui_player_backend_MpvNativeBridge_nativeDetachSurface(JNIEnv *env, jobject thiz, jlong handle) {
    (void) thiz;
    ambient_mpv_context *ctx = ctx_from(handle);
    if (!ctx || !ctx->mpv) return;
    mpv_set_property_string(ctx->mpv, "force-window", "no");
    mpv_set_property_string(ctx->mpv, "vo", "null");
    if (ctx->surface) {
        (*env)->DeleteGlobalRef(env, ctx->surface);
        ctx->surface = NULL;
    }
    int64_t wid = 0;
    mpv_set_option(ctx->mpv, "wid", MPV_FORMAT_INT64, &wid);
}

JNIEXPORT jint JNICALL
Java_com_ambient_tv_ui_player_backend_MpvNativeBridge_nativePollEvent(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env; (void) thiz;
    ambient_mpv_context *ctx = ctx_from(handle);
    if (!ctx || !ctx->mpv) return MPV_EVENT_NONE;
    mpv_event *event = mpv_wait_event(ctx->mpv, 0.0);
    if (event && event->event_id == MPV_EVENT_PROPERTY_CHANGE) {
        mpv_event_property *property = event->data;
        ctx->prop_id = event->reply_userdata;
        ctx->prop_format = property ? property->format : MPV_FORMAT_NONE;
        ctx->prop_string[0] = '\0';
        if (property && property->data) {
            if (property->format == MPV_FORMAT_FLAG) ctx->prop_flag = *(int *) property->data;
            else if (property->format == MPV_FORMAT_DOUBLE) ctx->prop_double = *(double *) property->data;
            else if (property->format == MPV_FORMAT_STRING) {
                const char *text = *(char **) property->data;
                snprintf(ctx->prop_string, sizeof(ctx->prop_string), "%s", text ? text : "");
            }
        } else {
            ctx->prop_format = MPV_FORMAT_NONE;
        }
    }
    if (event && event->event_id == MPV_EVENT_START_FILE) ctx->playback_error = 0;
    if (event && event->event_id == MPV_EVENT_END_FILE) {
        mpv_event_end_file *end = event->data;
        ctx->end_reason = end->reason;
        ctx->playback_error = end->reason == MPV_END_FILE_REASON_ERROR ? end->error : 0;
    }
    if (event && event->event_id == MPV_EVENT_LOG_MESSAGE) {
        mpv_event_log_message *message = event->data;
        char text[2048];
        snprintf(text, sizeof(text), "%s", message->text ? message->text : "");
        redact_credentials(text, sizeof(text));
        redact_url_queries(text);
        __android_log_print(ANDROID_LOG_INFO, "AmbientMPV", "%s: %s", message->prefix, text);
    }
    return event ? event->event_id : MPV_EVENT_NONE;
}

/* --- Event-driven playback state ------------------------------------------------------------ */

JNIEXPORT jint JNICALL
Java_com_ambient_tv_ui_player_backend_MpvNativeBridge_nativeObserveProperty(JNIEnv *env, jobject thiz, jlong handle, jlong id, jstring name, jint format) {
    (void) thiz;
    ambient_mpv_context *ctx = ctx_from(handle);
    if (!ctx || !ctx->mpv || !name) return MPV_ERROR_INVALID_PARAMETER;
    if (format != MPV_FORMAT_FLAG && format != MPV_FORMAT_DOUBLE && format != MPV_FORMAT_STRING) return MPV_ERROR_INVALID_PARAMETER;
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    int result = mpv_observe_property(ctx->mpv, (uint64_t) id, n, (mpv_format) format);
    (*env)->ReleaseStringUTFChars(env, name, n);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_ambient_tv_ui_player_backend_MpvNativeBridge_nativeWakeupFd(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env; (void) thiz;
    ambient_mpv_context *ctx = ctx_from(handle);
    return ctx ? ctx->wakeup_pipe[0] : -1;
}

/* Blocks up to timeout_ms for mpv activity on [fd]. 1 = woken (pipe drained), 0 = timeout,
 * -1 = fd closed or invalid (the player was destroyed). Touches only the fd, never the context. */
JNIEXPORT jint JNICALL
Java_com_ambient_tv_ui_player_backend_MpvNativeBridge_nativeWaitWakeup(JNIEnv *env, jobject thiz, jint fd, jint timeout_ms) {
    (void) env; (void) thiz;
    if (fd < 0) return -1;
    struct pollfd pfd = { .fd = fd, .events = POLLIN, .revents = 0 };
    int ready = poll(&pfd, 1, timeout_ms);
    if (ready < 0) return errno == EINTR ? 0 : -1;
    if (ready == 0) return 0;
    if (pfd.revents & (POLLNVAL | POLLERR)) return -1;
    char buffer[64];
    while (read(fd, buffer, sizeof(buffer)) > 0) { }
    return 1;
}

/* Wakes the Java waiter immediately (used before destroy so it can exit). */
JNIEXPORT void JNICALL
Java_com_ambient_tv_ui_player_backend_MpvNativeBridge_nativeInterruptWakeup(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env; (void) thiz;
    on_mpv_wakeup(ctx_from(handle));
}

JNIEXPORT jlong JNICALL
Java_com_ambient_tv_ui_player_backend_MpvNativeBridge_nativeEventPropertyId(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env; (void) thiz;
    ambient_mpv_context *ctx = ctx_from(handle);
    return ctx ? (jlong) ctx->prop_id : 0;
}

JNIEXPORT jint JNICALL
Java_com_ambient_tv_ui_player_backend_MpvNativeBridge_nativeEventPropertyFormat(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env; (void) thiz;
    ambient_mpv_context *ctx = ctx_from(handle);
    return ctx ? ctx->prop_format : MPV_FORMAT_NONE;
}

JNIEXPORT jboolean JNICALL
Java_com_ambient_tv_ui_player_backend_MpvNativeBridge_nativeEventPropertyFlag(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env; (void) thiz;
    ambient_mpv_context *ctx = ctx_from(handle);
    return ctx && ctx->prop_flag ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jdouble JNICALL
Java_com_ambient_tv_ui_player_backend_MpvNativeBridge_nativeEventPropertyDouble(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env; (void) thiz;
    ambient_mpv_context *ctx = ctx_from(handle);
    return ctx ? ctx->prop_double : 0.0;
}

JNIEXPORT jstring JNICALL
Java_com_ambient_tv_ui_player_backend_MpvNativeBridge_nativeEventPropertyString(JNIEnv *env, jobject thiz, jlong handle) {
    (void) thiz;
    ambient_mpv_context *ctx = ctx_from(handle);
    return ctx ? new_utf(env, ctx->prop_string) : NULL;
}
