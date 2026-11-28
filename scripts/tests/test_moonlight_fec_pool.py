#!/usr/bin/env python3
"""Exercise the actual patched Moonlight FEC pool with a host C compiler."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile

def main():
    source = Path(sys.argv[1]).read_text()
    start = source.index("#define FRAME_FEC_QUEUE_LIMIT")
    end = source.index("static LINKED_BLOCKING_QUEUE asyncCallbackQueue;", start)
    pool = source[start:end]
    harness = r"""
    #include <assert.h>
    #include <stdbool.h>
    #include <stddef.h>
    #include <stdint.h>
    #include <pthread.h>
    #include <sched.h>
    #define LC_ASSERT(x) assert(x)
    typedef struct {
        unsigned int fecStatus;
        void* entry[3];
    } QUEUED_FRAME_FEC_STATUS, *PQUEUED_FRAME_FEC_STATUS;
    """ + pool + r"""
    static unsigned int inUse[FRAME_FEC_POOL_LIMIT];
    
    static void* exercise(void* ignored) {
        (void)ignored;
        for (unsigned int i = 0; i < 50000; i++) {
            PQUEUED_FRAME_FEC_STATUS item = acquireFrameFecStatus();
            if (!item) {
                sched_yield();
                continue;
            }
            size_t slot = (size_t)(item - frameFecPool);
            assert(slot < FRAME_FEC_POOL_LIMIT);
            assert(__atomic_exchange_n(&inUse[slot], 1, __ATOMIC_RELAXED) == 0);
            item->fecStatus = i;
            assert(item->fecStatus == i);
            assert(__atomic_exchange_n(&inUse[slot], 0, __ATOMIC_RELAXED) == 1);
            releaseFrameFecStatus(item);
        }
        return NULL;
    }
    
    int main(void) {
        PQUEUED_FRAME_FEC_STATUS held[FRAME_FEC_POOL_LIMIT];
        for (unsigned int cycle = 0; cycle < 100; cycle++) {
            for (unsigned int i = 0; i < FRAME_FEC_POOL_LIMIT; i++) {
                held[i] = acquireFrameFecStatus();
                assert(held[i]);
                for (unsigned int j = 0; j < i; j++) assert(held[j] != held[i]);
            }
            assert(acquireFrameFecStatus() == NULL);
            releaseFrameFecStatus(held[3]);
            assert(acquireFrameFecStatus() == held[3]);
            for (unsigned int i = 0; i < FRAME_FEC_POOL_LIMIT; i++) {
                releaseFrameFecStatus(held[i]);
            }
            assert(__atomic_load_n(&frameFecPoolUsed, __ATOMIC_RELAXED) == 0);
        }
        pthread_t workers[4];
        for (unsigned int i = 0; i < 4; i++) assert(pthread_create(&workers[i], NULL, exercise, NULL) == 0);
        for (unsigned int i = 0; i < 4; i++) assert(pthread_join(workers[i], NULL) == 0);
        assert(__atomic_load_n(&frameFecPoolUsed, __ATOMIC_RELAXED) == 0);
        return 0;
    }
    """
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp)
        (path / "pool.c").write_text(harness)
        subprocess.run([os.environ.get("CC", "cc"), "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
                        "-pthread", str(path / "pool.c"), "-o", str(path / "pool")], check=True)
        subprocess.run([str(path / "pool")], check=True, timeout=30)
    print("Moonlight FEC pool exhaustion, reuse and concurrent ownership passed")


if __name__ == "__main__":
    main()
