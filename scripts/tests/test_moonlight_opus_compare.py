#!/usr/bin/env python3
"""Compare the pinned Opus decoder with a separate baseline source build.

Host comparison only: Android ABI builds are independently checked in CI.
No absolute timing gate on shared runners; compare correctness and report medians.
"""
import argparse
import json
import os
from pathlib import Path
import statistics
import subprocess
import tempfile

HARNESS = r"""
#include <opus.h>
#include <opus_multistream.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#define MAX_FRAMES 960
#define PACKETS 240
#define MAX_PACKET 8192
static unsigned char mapping[8] = {0, 1, 2, 3, 4, 5, 6, 7};
static int16_t pcm[MAX_FRAMES*8];
static unsigned char packet[MAX_PACKET];
static unsigned char packets[PACKETS][MAX_PACKET];
static uint32_t lengths[PACKETS];
static int16_t decoded[PACKETS*MAX_FRAMES*8];
static void require(int ok) { if (!ok) abort(); }
int main(int argc, char **argv) {
    require(argc == 6);
    int ch = atoi(argv[2]), err, streams = ch/2, coupled = ch/2;
    int frames = atoi(argv[4]), mode = atoi(argv[5]);
    require(ch == 2 || ch == 6 || ch == 8);
    require(frames == 240 || frames == 480 || frames == 960);
    // Exact legacy GameStream 5.1 fallback from pinned RtspConnection.c.
    if (ch == 6 && mode == 1) {
        const unsigned char fallback[] = {0,4,1,5,2,3};
        streams = 4; coupled = 2; memcpy(mapping, fallback, sizeof(fallback));
    } else if (ch == 8 && mode == 1) {
        // Representative negotiated normal-quality topology, in client channel order.
        // 7.1 has no fixed fallback in common-c; servers signal their own mapping.
        const unsigned char surround[] = {0,1,6,7,2,3,4,5};
        streams = 5; coupled = 3; memcpy(mapping, surround, sizeof(surround));
    } else if (mode == 2) {
        // High-quality independent channels: a valid negotiated topology.
        streams = ch; coupled = 0;
    }
    FILE *f = fopen(argv[3], argv[1][0] == 'e' ? "wb" : "rb");
    require(f != NULL);
    if (argv[1][0] == 'e') {
        OpusMSEncoder *enc = opus_multistream_encoder_create(48000,ch,streams,coupled,mapping,OPUS_APPLICATION_RESTRICTED_LOWDELAY,&err);
        require(enc && err == OPUS_OK);
        require(opus_multistream_encoder_ctl(enc,OPUS_SET_BITRATE(64000*ch)) == OPUS_OK);
        uint32_t rng=12345;
        for(int p=0;p<PACKETS;p++) {
            for(int i=0;i<frames*ch;i++) {
                rng=rng*1664525u+1013904223u;
                pcm[i]=(int16_t)((rng>>18)-8192);
            }
            int n=opus_multistream_encode(enc,pcm,frames,packet,sizeof(packet));
            require(n>0 && n<=sizeof(packet));
            uint32_t len=(uint32_t)n;
            require(fwrite(&len,4,1,f)==1 && fwrite(packet,1,n,f)==(size_t)n);
        }
        opus_multistream_encoder_destroy(enc);
    } else {
        FILE *out=fopen(argv[1],"wb"); require(out!=NULL);
        // Preload all input and touch output pages before the clock starts.
        for(int p=0;p<PACKETS;p++) {
            require(fread(&lengths[p],4,1,f)==1 && lengths[p]>0 && lengths[p]<=MAX_PACKET);
            require(fread(packets[p],1,lengths[p],f)==lengths[p]);
        }
        OpusMSDecoder *dec=opus_multistream_decoder_create(48000,ch,streams,coupled,mapping,&err);
        require(dec && err==OPUS_OK);
        memset(decoded,0,(size_t)PACKETS*frames*ch*sizeof(int16_t));
        clock_t begin=clock();
        for(int p=0;p<PACKETS;p++) {
            int lost = p%23==0;
            int n=opus_multistream_decode(dec,lost?NULL:packets[p],lost?0:lengths[p],
                                         decoded+(size_t)p*frames*ch,frames,0);
            require(n==frames);
        }
        clock_t end=clock();
        require(fwrite(decoded,sizeof(int16_t),(size_t)PACKETS*frames*ch,out)==(size_t)PACKETS*frames*ch);
        printf("%.9f\n",(double)(end-begin)/CLOCKS_PER_SEC);
        fclose(out); opus_multistream_decoder_destroy(dec);
    }
    fclose(f); return 0;
}
"""

def run(*args, **kw):
    return subprocess.run([str(x) for x in args], check=True, **kw)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    source = args.source.resolve()
    pin = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    assert pin == "22244de5a79bd1d6d623c32e72bf1954b56235be"
    report = {"source": pin, "host_only": True, "profiles": {}, "channels": {}}
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        harness = root / "probe.c"
        harness.write_text(HARNESS)
        for profile, flags in (("baseline", "-O2 -DNDEBUG"), ("optimized", "-O3 -DNDEBUG -flto=thin")):
            build = root / profile
            run("cmake", "-S", source, "-B", build, "-G", "Ninja",
                "-DCMAKE_C_COMPILER=clang", "-DCMAKE_BUILD_TYPE=Release",
                "-DCMAKE_C_FLAGS_RELEASE=" + flags, "-DCMAKE_EXE_LINKER_FLAGS=-fuse-ld=lld",
                "-DOPUS_PACKAGE_VERSION=1.6.1", "-DOPUS_BUILD_TESTING=ON",
                "-DOPUS_BUILD_SHARED_LIBRARY=OFF", "-DOPUS_BUILD_PROGRAMS=OFF",
                "-DOPUS_FAST_MATH=OFF", "-DOPUS_HARDENING=ON",
                "-DOPUS_X86_PRESUME_AVX2=OFF", "-DOPUS_X86_PRESUME_SSE4_1=OFF")
            run("cmake", "--build", build, "--parallel", "2")
            run("ctest", "--test-dir", build, "--output-on-failure", "-R", "test_opus_(api|decode)$", "--no-tests=error")
            exe = root / (profile + "-probe")
            run("clang", "-O2", "-fuse-ld=lld", "-I", source / "include", harness, build / "libopus.a", "-lm", "-o", exe)
            report["profiles"][profile] = {"flags": flags, "archive_bytes": (build / "libopus.a").stat().st_size}
        import array
        for channels, frames, mode in ((2,240,0),(2,480,0),(2,960,0),
                                        (6,240,1),(6,480,1),(6,960,1),(6,240,2),
                                        (8,240,1),(8,480,1),(8,960,1),(8,240,2)):
            packets = root / "packets.bin"
            run(root / "baseline-probe", "encode", channels, packets, frames, mode)
            timings = {}
            outputs = {}
            for profile in ("baseline", "optimized"):
                out = root / (profile + ".pcm")
                samples = []
                for _ in range(5):
                    result = run(root / (profile + "-probe"), out, channels, packets, frames, mode, capture_output=True, text=True)
                    samples.append(float(result.stdout.strip()))
                timings[profile] = statistics.median(samples)
                outputs[profile] = array.array("h", out.read_bytes())
            ref, opt = outputs["baseline"], outputs["optimized"]
            assert len(ref) == len(opt) == 240 * frames * channels
            peak = max(abs(a-b) for a,b in zip(ref,opt))
            # Both builds preserve float semantics. Permit only tiny rounding differences.
            assert peak <= 2, f"{channels} channels: decoder mismatch {peak}"
            report["channels"][f"{channels}ch-{frames}samples-mode{mode}"] = {
                "channels": channels, "frames": frames, "mode": mode,
                "max_sample_difference": peak, "median_decoder_cpu_seconds": timings,
                "optimized_over_baseline": timings["optimized"] / max(timings["baseline"], 1e-9)}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))

if __name__ == "__main__":
    main()
