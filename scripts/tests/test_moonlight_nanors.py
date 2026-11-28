#!/usr/bin/env python3
"""Exercise the pinned, patched NanoRS implementation, including ARM64 NEON."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

HARNESS = r"""
#include <assert.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "rs.h"

static uint32_t nextRandom(uint32_t *state) {
    *state = *state * 1664525u + 1013904223u;
    return *state;
}

static void exercise(int ds, int ps, int size, int custom, uint32_t seed) {
    reed_solomon *rs = reed_solomon_new(ds, ps);
    assert(rs);
    if (custom) {
        const uint8_t parity[] = {0x77,0x40,0x38,0x0e,0xc7,0xa7,0x0d,0x6c};
        assert(ds == 4 && ps == 2);
        memcpy(rs->p, parity, sizeof(parity));
    }
    uint8_t *shards[255], *storage[255], *original[255], marks[255] = {0};
    for (int i = 0; i < ds + ps; i++) {
        // Deliberately use unaligned packet payloads and non-vector-sized tails.
        storage[i] = malloc((size_t)size + 1);
        original[i] = malloc((size_t)size);
        assert(storage[i] && original[i]);
        shards[i] = storage[i] + 1;
        for (int j = 0; j < size; j++) shards[i][j] = (uint8_t)(nextRandom(&seed) >> 24);
    }
    assert(reed_solomon_encode(rs, shards, ds + ps, size) == 0);
    for (int i = 0; i < ds + ps; i++) memcpy(original[i], shards[i], size);
    assert(reed_solomon_decode(rs, shards, marks, ds + ps, size) == 0);

    // Every missing data shard reconstructed with every possible parity row.
    for (int missing = 0; missing < ds; missing++) {
        for (int row = 0; row < ps; row++) {
            memset(marks, 0, sizeof(marks));
            memset(marks + ds, 1, ps);
            marks[ds + row] = 0;
            marks[missing] = 1;
            memset(shards[missing], 0xA5, size);
            assert(reed_solomon_decode(rs, shards, marks, ds + ps, size) == 0);
            assert(memcmp(shards[missing], original[missing], size) == 0);
        }
    }

    // Multiple erasures use the existing general elimination path.
    memset(marks, 0, sizeof(marks));
    int losses = ds < ps ? ds : ps;
    for (int i = 0; i < losses; i++) {
        marks[i] = 1;
        memset(shards[i], 0xA5, size);
    }
    assert(reed_solomon_decode(rs, shards, marks, ds + ps, size) == 0);
    for (int i = 0; i < ds + ps; i++) assert(!memcmp(shards[i], original[i], size));

    // Insufficient parity must fail before modifying any missing data shard.
    memset(marks, 0, sizeof(marks));
    marks[0] = 1;
    memset(marks + ds, 1, ps);
    memset(shards[0], 0xA5, size);
    assert(reed_solomon_decode(rs, shards, marks, ds + ps, size) < 0);
    for (int i = 0; i < size; i++) assert(shards[0][i] == 0xA5);
    for (int i = 0; i < ds + ps; i++) {
        free(storage[i]);
        free(original[i]);
    }
    reed_solomon_release(rs);
}

static void *worker(void *unused) {
    (void)unused;
    exercise(4, 2, 67, 1, 123u);
    exercise(12, 4, 1401, 0, 456u);
    return NULL;
}
int main(void) {
    pthread_t workers[4];
    // Concurrent first use exercises the shared lookup-table initialization.
    for (int i = 0; i < 4; i++) assert(!pthread_create(&workers[i], NULL, worker, NULL));
    for (int i = 0; i < 4; i++) assert(!pthread_join(workers[i], NULL));
    exercise(4, 2, 1, 1, 12u);
    exercise(30, 8, 1024, 0, 33u);
    return 0;
}
"""

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("nanors", type=Path)
    parser.add_argument("--arm64", action="store_true")
    args = parser.parse_args()
    root = args.nanors.resolve()
    with tempfile.TemporaryDirectory() as tmp:
        source = Path(tmp) / "test.c"
        source.write_text(HARNESS)
        exe = Path(tmp) / "test"
        common = ["-std=gnu11", "-O2", "-pthread", "-I", str(root), "-I", str(root / "deps/obl"),
                  str(source), str(root / "rs.c"), str(root / "deps/obl/oblas_lite.c"),
                  str(root / "deps/obl/oblas_common.c"), "-o", str(exe)]
        subprocess.run([os.environ.get("CC", "cc"), *common], check=True)
        subprocess.run([str(exe)], check=True, timeout=30)
        if args.arm64:
            compiler = shutil.which("aarch64-linux-gnu-gcc")
            emulator = shutil.which("qemu-aarch64")
            assert compiler and emulator
            subprocess.run([compiler, "-march=armv8-a+simd", "-static", *common], check=True)
            subprocess.run([emulator, str(exe)], check=True, timeout=60)
    print("NanoRS single/multiple erasures, custom parity, unaligned tails and concurrent init passed")

if __name__ == "__main__":
    main()
