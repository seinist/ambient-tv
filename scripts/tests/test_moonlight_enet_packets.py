#!/usr/bin/env python3
"""Exercise the pinned, patched ENet packet implementation with host C."""
import os
from pathlib import Path
import subprocess
import argparse
import shutil
import tempfile

HARNESS = r"""
#include <assert.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "enet/enet.h"

static size_t allocs, frees;
static int failNext;
void *enet_malloc(size_t size) {
    if (failNext) { failNext = 0; return NULL; }
    void *p = malloc(size);
    if (p) allocs++;
    return p;
}
void enet_free(void *p) {
    if (p) { frees++; free(p); }
}
static int callbacks;
static void onFree(ENetPacket *p) {
    assert(p != NULL);
    callbacks++;
}
static uint32_t referenceCrc(const ENetBuffer *parts, size_t count) {
    uint32_t crc = 0xffffffffu;
    for (size_t i = 0; i < count; i++) {
        const unsigned char *p = parts[i].data;
        for (size_t j = 0; j < parts[i].dataLength; j++) {
            crc ^= p[j];
            for (int bit = 0; bit < 8; bit++)
                crc = (crc >> 1) ^ ((0u - (crc & 1u)) & 0xedb88320u);
        }
    }
    return ENET_HOST_TO_NET_32(~crc);
}
static void testCrc(void) {
    ENetBuffer known = { .data = (void*)"123456789", .dataLength = 9 };
    assert(enet_crc32(&known, 1) == ENET_HOST_TO_NET_32(0xcbf43926u));
    assert(enet_crc32(NULL, 0) == 0);
    unsigned char bytes[2050];
    uint32_t rng = 9;
    for (size_t i = 0; i < sizeof(bytes); i++) {
        rng = rng * 1664525u + 1013904223u;
        bytes[i] = (unsigned char)(rng >> 24);
    }
    for (size_t length = 0; length < 2048; length++) {
        size_t split = length / 3;
        ENetBuffer parts[] = {
            { .data = bytes + 1, .dataLength = split },
            { .data = bytes + 1 + split, .dataLength = 0 },
            { .data = bytes + 1 + split, .dataLength = length - split }
        };
        assert(enet_crc32(parts, 3) == referenceCrc(parts, 3));
    }
}
int main(void) {
    testCrc();
    unsigned char data[64];
    memset(data, 0x5a, sizeof(data));
    ENetPacket *p = enet_packet_create(data, sizeof(data), 0);
    assert(p && allocs == 1 && memcmp(p->data, data, sizeof(data)) == 0);
    p->freeCallback = onFree;
    assert(enet_packet_resize(p, 32) == 0 && allocs == 1);
    failNext = 1;
    assert(enet_packet_resize(p, 128) < 0 && p->dataLength == 32);
    assert(memcmp(p->data, data, 32) == 0);
    assert(enet_packet_resize(p, 128) == 0 && allocs == 2);
    assert(memcmp(p->data, data, 32) == 0);
    enet_packet_destroy(p);
    assert(allocs == frees && callbacks == 1);

    p = enet_packet_create(data, sizeof(data), ENET_PACKET_FLAG_NO_ALLOCATE);
    assert(p && p->data == data);
    assert(enet_packet_resize(p, 32) == 0 && p->data == data);
    enet_packet_destroy(p);
    assert(allocs == frees && data[0] == 0x5a);

    p = enet_packet_create(NULL, 0, 0);
    assert(p && p->data == NULL);
    assert(enet_packet_resize(p, 64) == 0 && p->data != NULL);
    enet_packet_destroy(p);
    assert(allocs == frees);
    assert(enet_packet_create(NULL, SIZE_MAX, 0) == NULL);

    for (size_t i = 1; i < 2048; i++) {
        p = enet_packet_create(NULL, i, 0);
        assert(p && p->data != NULL && p->dataLength == i);
        memset(p->data, 0x31, i);
        enet_packet_destroy(p);
    }
    assert(allocs == frees);
    return 0;
}
"""

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('enet', type=Path)
    parser.add_argument('--arm64', action='store_true')
    args = parser.parse_args()
    enet = args.enet.resolve()
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        source = root / "packet_test.c"
        source.write_text(HARNESS)
        exe = root / "packet_test"
        common = ["-std=gnu11", "-O2", "-DHAS_SOCKLEN_T=1", "-I", str(enet / "include"),
                  str(source), str(enet / "packet.c"), "-o", str(exe)]
        subprocess.run([os.environ.get("CC", "cc"), *common], check=True)
        subprocess.run([str(exe)], check=True, timeout=30)
        if args.arm64:
            compiler = shutil.which("aarch64-linux-gnu-gcc")
            emulator = shutil.which("qemu-aarch64")
            assert compiler and emulator
            subprocess.run([compiler, "-march=armv8-a", "-static", *common], check=True)
            # Cortex-A53 has the CRC extension; Cortex-A57 is the Shield class.
            subprocess.run([emulator, "-cpu", "cortex-a53", str(exe)], check=True, timeout=30)
            subprocess.run([emulator, "-cpu", "max", str(exe)], check=True, timeout=30)
    print("ENet owned/external packet allocation, resize and teardown passed")

if __name__ == "__main__":
    main()
