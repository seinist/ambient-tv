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
#define FRAMES 960
#define PACKETS 240
static unsigned char mapping[8] = {0, 1, 2, 3, 4, 5, 6, 7};
static int16_t pcm[FRAMES*8];
static unsigned char packet[8192];
static void require(int ok) { if (!ok) abort(); }
int main(int argc, char **argv) {
    require(argc == 4);
    int ch = atoi(argv[2]), err, streams = ch/2;
    require(ch == 2 || ch == 6 || ch == 8);
    FILE *f = fopen(argv[3], argv[1][0] == 'e' ? "wb" : "rb");
    require(f != NULL);
    if (argv[1][0] == 'e') {
        OpusMSEncoder *enc = opus_multistream_encoder_create(48000,ch,streams,streams,mapping,OPUS_APPLICATION_RESTRICTED_LOWDELAY,&err);
        require(enc && err == OPUS_OK);
        require(opus_multistream_encoder_ctl(enc,OPUS_SET_BITRATE(64000*ch)) == OPUS_OK);
        uint32_t rng=12345;
        for(int p=0;p<PACKETS;p++) {
            for(int i=0;i<FRAMES*ch;i++) {
                rng=rng*1664525u+1013904223u;
                pcm[i]=(int16_t)((rng>>18)-8192);
            }
            int n=opus_multistream_encode(enc,pcm,FRAMES,packet,sizeof(packet));
            require(n>0 && n<=sizeof(packet));
            uint32_t len=(uint32_t)n;
            require(fwrite(&len,4,1,f)==1 && fwrite(packet,1,n,f)==(size_t)n);
        }
        opus_multistream_encoder_destroy(enc);
    } else {
        FILE *out=fopen(argv[1],"wb"); require(out!=NULL);
        OpusMSDecoder *dec=opus_multistream_decoder_create(48000,ch,streams,streams,mapping,&err);
        require(dec && err==OPUS_OK);
        clock_t begin=clock();
        for(int p=0;p<PACKETS;p++) {
            uint32_t len; require(fread(&len,4,1,f)==1 && len>0 && len<=sizeof(packet));
            require(fread(packet,1,len,f)==len);
            // Exercise loss concealment too, with identical packet loss on both builds.
            int n=opus_multistream_decode(dec,p%23==0?NULL:packet,p%23==0?0:len,pcm,FRAMES,0);
            require(n==FRAMES);
            require(fwrite(pcm,sizeof(int16_t),n*ch,out)==(size_t)n*ch);
        }
        printf("%.9f\n",(double)(clock()-begin)/CLOCKS_PER_SEC);
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
        for channels in (2, 6, 8):
            packets = root / "packets.bin"
            run(root / "baseline-probe", "encode", channels, packets)
            timings = {}
            outputs = {}
            for profile in ("baseline", "optimized"):
                out = root / (profile + ".pcm")
                samples = []
                for _ in range(5):
                    result = run(root / (profile + "-probe"), out, channels, packets, capture_output=True, text=True)
                    samples.append(float(result.stdout.strip()))
                timings[profile] = statistics.median(samples)
                outputs[profile] = array.array("h", out.read_bytes())
            ref, opt = outputs["baseline"], outputs["optimized"]
            assert len(ref) == len(opt) == 240 * 960 * channels
            peak = max(abs(a-b) for a,b in zip(ref,opt))
            # Both builds preserve float semantics. Permit only tiny rounding differences.
            assert peak <= 2, f"{channels} channels: decoder mismatch {peak}"
            report["channels"][str(channels)] = {"max_sample_difference": peak, "median_cpu_seconds": timings}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))

if __name__ == "__main__":
    main()
