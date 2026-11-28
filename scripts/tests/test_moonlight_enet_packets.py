#!/usr/bin/env python3
"""Exercise the pinned, patched ENet packet implementation with host C."""
import os
from pathlib import Path
import subprocess
import sys
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
int main(void) {
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
    enet = Path(sys.argv[1]).resolve()
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        source = root / "packet_test.c"
        source.write_text(HARNESS)
        exe = root / "packet_test"
        subprocess.run([os.environ.get("CC", "cc"), "-std=gnu11", "-O2",
                        "-DHAS_SOCKLEN_T=1", "-I", str(enet / "include"),
                        str(source), str(enet / "packet.c"), "-o", str(exe)], check=True)
        subprocess.run([str(exe)], check=True, timeout=30)
    print("ENet owned/external packet allocation, resize and teardown passed")

if __name__ == "__main__":
    main()
