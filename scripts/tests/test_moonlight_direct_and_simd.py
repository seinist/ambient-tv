#!/usr/bin/env python3
"""Compile the actual patched native functions with host mocks and ARM64 NEON."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

PRELUDE = r"""
#include <assert.h>
#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#if defined(__aarch64__) && defined(__ARM_NEON)
#include <arm_neon.h>
#endif
#define BUFFER_TYPE_PICDATA 0
#define JNIEXPORT
#define JNICALL
#define JNI_TRUE 1
#define JNI_FALSE 0
typedef unsigned char jboolean;
typedef int jint;
typedef int64_t jlong;
typedef void *jobject;
typedef void *jclass;
typedef struct LENTRY {
    struct LENTRY *next;
    char *data;
    int length;
    int bufferType;
} *PLENTRY;
typedef struct { PLENTRY bufferList; } DECODE_UNIT, *PDECODE_UNIT;
typedef struct { unsigned char *address; jlong capacity; } Buffer;
typedef struct JNITable {
    void *(*GetDirectBufferAddress)(const struct JNITable **env, jobject obj);
    jlong (*GetDirectBufferCapacity)(const struct JNITable **env, jobject obj);
} const *JNIEnv;
static void *address(JNIEnv *env, jobject obj) { (void)env; return ((Buffer*)obj)->address; }
static jlong capacity(JNIEnv *env, jobject obj) { (void)env; return ((Buffer*)obj)->capacity; }
static struct JNITable table = { address, capacity };
static JNIEnv mock = &table;
#define MAX_RTP_HEADER_SIZE 32
"""
TEST = r"""
static void *otherThread(void *target) {
    assert(!Java_com_limelight_nvstream_jni_MoonBridge_copyActivePictureData(&mock, NULL, target, 5));
    return NULL;
}
static void testCopy(void) {
    struct LENTRY second = { NULL, "de", 2, BUFFER_TYPE_PICDATA };
    struct LENTRY config = { &second, "config", 6, 1 };
    struct LENTRY first = { &config, "abc", 3, BUFFER_TYPE_PICDATA };
    DECODE_UNIT unitStorage = { &first };
    unsigned char output[8] = {0};
    Buffer target = { output, sizeof(output) };
    assert(!Java_com_limelight_nvstream_jni_MoonBridge_copyActivePictureData(&mock, NULL, &target, 5));
    activePictureUnit = &unitStorage;
    activePictureLength = 5;
    assert(!Java_com_limelight_nvstream_jni_MoonBridge_copyActivePictureData(&mock, NULL, &target, 4));
    target.capacity = 4;
    assert(!Java_com_limelight_nvstream_jni_MoonBridge_copyActivePictureData(&mock, NULL, &target, 5));
    target.capacity = sizeof(output);
    target.address = NULL;
    assert(!Java_com_limelight_nvstream_jni_MoonBridge_copyActivePictureData(&mock, NULL, &target, 5));
    target.address = output;
    pthread_t thread;
    assert(!pthread_create(&thread, NULL, otherThread, &target));
    assert(!pthread_join(thread, NULL));
    assert(Java_com_limelight_nvstream_jni_MoonBridge_copyActivePictureData(&mock, NULL, &target, 5));
    assert(!memcmp(output, "abcde", 5) && output[5] == 0);
    second.length = 6;
    assert(!Java_com_limelight_nvstream_jni_MoonBridge_copyActivePictureData(&mock, NULL, &target, 5));
    activePictureUnit = NULL;
    assert(!Java_com_limelight_nvstream_jni_MoonBridge_copyActivePictureData(&mock, NULL, &target, 5));
}
static int16_t expected(float sample) {
    int32_t n = (int32_t)(sample * .5f);
    return n < -32768 ? -32768 : n > 32767 ? 32767 : (int16_t)n;
}
static void testDownmix(void) {
    uint32_t rng = 123456;
    int16_t input[67*8], output[67*2];
    for (int channels = 6; channels <= 8; channels += 2) {
        for (int rounds = 0; rounds < 1000; rounds++) {
            for (int i = 0; i < 67 * channels; i++) {
                rng = rng * 1664525u + 1013904223u;
                input[i] = (int16_t)(rng >> 16);
            }
            int frames = rounds % 67 + 1;
            downmixStereo(input, output, frames, channels);
            for (int f = 0; f < frames; f++) {
                const int16_t *p = input + f * channels;
                float center = p[2] * .707f, bass = p[3] * .25f;
                float l = p[0] + center + bass + p[4] * .707f;
                float r = p[1] + center + bass + p[5] * .707f;
                if (channels == 8) { l += p[6] * .5f; r += p[7] * .5f; }
                assert(abs((int)output[f*2] - expected(l)) <= 1);
                assert(abs((int)output[f*2+1] - expected(r)) <= 1);
            }
        }
        memset(input, 0, sizeof(input));
        for (int i = 0; i < 67 * channels; i++) input[i] = 32767;
        downmixStereo(input, output, 67, channels);
        for (int i = 0; i < 134; i++) assert(output[i] == 32767);
        for (int i = 0; i < 67 * channels; i++) input[i] = -32768;
        downmixStereo(input, output, 67, channels);
        for (int i = 0; i < 134; i++) assert(output[i] == -32768);
    }
}
static void testNetworkBounds(void) {
    int floor = 2048 * (1024 + MAX_RTP_HEADER_SIZE);
    int cap = 4096 * (1024 + MAX_RTP_HEADER_SIZE);
    assert(videoReceiveBufferBytes(20000, 60, 1024, 0) == floor);
    assert(videoReceiveBufferBytes(20000, 60, 1024, 1) == floor * 3 / 2);
    assert(videoReceiveBufferBytes(20000, 60, 1024, 2) == cap);
    assert(videoReceiveBufferBytes(150000, 24, 1024, 9) == cap);
    assert(videoReceiveBufferBytes(-1, 0, 0, 0) == floor);
    AmbientSetVideoBufferTier(9);
    assert(adaptiveVideoBufferTier == 2);
}
int main(void) {
    testCopy();
    testDownmix();
    testNetworkBounds();
    return 0;
}
"""

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path, help="patched moonlight-core directory")
    parser.add_argument("--arm64", action="store_true", help="also execute AArch64 SIMD using qemu")
    args = parser.parse_args()
    c = (args.root / "callbacks.c").read_text()
    v = (args.root / "moonlight-common-c/src/VideoStream.c").read_text()
    copy = c[c.index("static __thread PDECODE_UNIT activePictureUnit;"):c.index("static bool ensureFrameArray")]
    simd = c[c.index("// Keep scalar and SIMD operations"):c.index("int BridgeArInit(")]
    video = v[v.index("#define RTP_RECV_PACKETS_BUFFERED"):v.index("// Initialize the video stream")]
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        source = root / "native.c"
        source.write_text(PRELUDE + copy + simd + video + TEST)
        common = ["-std=gnu11", "-O2", "-ffp-contract=off", "-Wall", "-Wextra", "-Werror", "-pthread", str(source)]
        exe = root / "native"
        subprocess.run([os.environ.get("CC", "cc"), *common, "-o", str(exe)], check=True)
        subprocess.run([str(exe)], check=True, timeout=30)
        if args.arm64:
            compiler = shutil.which("aarch64-linux-gnu-gcc")
            emulator = shutil.which("qemu-aarch64")
            assert compiler and emulator, "ARM64 test toolchain missing"
            subprocess.run([compiler, *common, "-march=armv8-a+simd", "-static", "-o", str(exe)], check=True)
            subprocess.run([emulator, str(exe)], check=True, timeout=60)
    print("Native picture ownership, downmix parity and UDP capacity bounds passed")

if __name__ == "__main__":
    main()
