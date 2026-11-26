# Libretro core and native library performance — staged implementation

This branch is independent of PR #501, which already optimizes renderer, audio,
rewind and input paths. It does not change the active core or user options.

## Stage 1: reproducible build candidates

- Baseline, O3, ThinLTO and O3+ThinLTO profiles use the same pinned upstream
  sources, NDK and ABI. Candidate flags must be visible in verbose compile/link
  commands. The report includes the binary hash, size, pin, ABI, NDK and
  observed optimization levels. It is build evidence, not a speed benchmark.
- PR checks compare baseline and ThinLTO builds of Snes9x (ndk-build) and mGBA
  (CMake) on ARM32 and ARM64. Other cores and ABIs can be selected explicitly.
- All artifacts must contain the standard Libretro dynamic exports and satisfy
  the existing 16 KiB ELF-page gate. Selection no longer picks the first .so.
- Existing upstream Android.mk/Application.mk options are retained; the page
  alignment flag is appended rather than replacing APP_LDFLAGS.
- Publishing requires main, the complete baseline matrix, signing key and
  verified artifacts. Candidate binaries and reports are not in a signed
  release; they cannot be automatically selected by the app.
- The native LibretroDroid release wrapper accepts the same explicit candidate
  profiles. Debug and baseline retain their previous behavior.

Examples (after merging the workflow):
    ./build.sh cores snes9x,mgba --profile thinlto --abis arm64-v8a,armeabi-v7a
    ./build.sh cores pcsx_rearmed,gpsp --profile o3-thinlto --abis arm64-v8a
    LIBRETRO_NATIVE_PROFILE=thinlto ./build.sh arm64

## Acceptance and remaining stages

1. Build/test each candidate per core and ABI. Inspect JIT and SIMD macros,
   generated code and runtime feature selection, not only compiler flags.
2. Benchmark identical legally available ROMs, pinned settings, renderer and
   firmware; warm up each run, alternate baseline/candidate, record core-run
   median/p95/p99, deadline misses, audio underruns, CPU/GPU time, thermal
   throttling and save-state/input correctness. Use PR #501 native traces
   where available. Host CPU timings are not a substitute for Android devices.
3. Promote a candidate only with repeatable improvement and no regression.
   No blanket fast-math, unsupported ISA, unsafe JIT settings or global O3.
   PGO needs a representative per-core workload, compatible compiler profiles
   and reproducible CI inputs.
4. Verify pinned frontend source and implement only missing Libretro callback
   contracts (input bitmasks/snapshots, software framebuffer, perf interface,
   audio/video enable). Preserve short taps, frame/state determinism, netplay,
   pixel formats and buffer ownership.
5. Add Android performance-hint support where appropriate; compare sustained
   performance and power on actual hardware, including older API levels.
6. Evaluate pinned source recipes for PPSSPP, Flycast, Dolphin, melonDS, VICE
   and additional buildbot cores. Only advertise verified ABI combinations.
7. Device-specific binary variants require signed manifest schema evolution,
   actual process ABI and runtime ISA checks, rollback, state fingerprints and
   license compliance. Do not infer instruction support from a model name.

Status: initial code and regression tests; actual NDK builds, native wrapper
compilation, Android-device timings and further frontend changes are pending.

## Stage 2 — direct input masks and frontend performance interface

The exact Argosy pin 6a9c89e6dca8b21b954c70576e78e92bf4230523 was inspected.
GET_INPUT_BITMASKS already returns true; GET_PERF_INTERFACE and
GET_AUDIO_VIDEO_ENABLE explicitly return false in the upstream environment.
The existing 0028 patch provides a fixed buttonMask helper but the core's
bitmask callback still traverses 16 individual IDs. Patch 0200 uses the same
mask helper directly, under the existing mutex, and avoids six floating-point
conversions on the mask-only captured-input API. Core queries remain
non-consuming; the authoritative/captured separation and release semantics
remain unchanged.

The patch also implements GET_PERF_INTERFACE with cached runtime CPU feature
detection, monotonic nanosecond counters and microsecond timing. ARM uses
kernel HWCAP; x86 gates AVX and AVX2 on both CPUID and OS-enabled XCR0 state.
It never sets unsupported ISA flags or changes floating-point modes. Counter
diagnostics copy bounded names and totals, never retain a dereferenceable
core-owned pointer, and clear at environment teardown. Explicit profiling
callbacks have bounded storage; ordinary game frames do not take their lock.

The existing native host harness now exercises all 65536 button masks,
legacy motion and pending taps, netplay isolation, feature decoding with
missing OS support, timing, copied diagnostics and overflow. Full patch
application, all ABI builds and physical core timing remain CI/device gates.

### Build validation and contextual re-audit

Initial head ce2b702a82632d3e1b3985acf3b02e2acdc7cf59: [Cores run 45858807739](https://github.com/seinist/ambient-tv/actions/runs/45858807739) completed the plan and all eight builds successfully; publishing skipped as intended.
Each ThinLTO Snes9x build observed 58/58 C/C++ compile commands and one
shared link with ThinLTO; mGBA observed 246/246 and one link per ABI.

| Core | ABI | Baseline bytes | ThinLTO bytes |
|---|---|---:|---:|
| snes9x | arm64-v8a | 2922320 | 2954544 |
| snes9x | armeabi-v7a | 1734996 | 1719252 |
| mgba | arm64-v8a | 2073936 | 2117776 |
| mgba | armeabi-v7a | 1252288 | 1283428 |

These are stripped binary sizes, not gameplay timings. Baseline remains default.
The exact pinned input.cpp and environment.cpp were reconstructed against the
known relevant preceding patches. Both input hunks and two environment hunks
matched uniquely; the include hunk initially missed the contentaspect.h line
from patch 0035. It has been corrected to the post-0035 include context.
The complete fresh git apply, native host tests and NDK compilation for this
new head are still required; no prior green run validates these new changes.

## Stage 3 — acceleration evidence and focused Android candidates

Previous head b8adb0eee7fec0a87f07e525911c790e25da70fd: [PR checks](https://github.com/seinist/ambient-tv/actions/runs/45859405750) all ten successful; [Cores](https://github.com/seinist/ambient-tv/actions/runs/45859406049) plan plus all eight comparison builds successful, publication skipped. This validates Stage 2 on all four build ABIs, not Android performance.

Pinned source review:
- gpSP jni/Android.mk already sets HAVE_DYNAREC and MMAP_JIT_CACHE for all four ABIs; Makefile.common selects cpu_threaded.c plus an architecture stub.
- PCSX ReARMed jni/Android.mk already selects ARM ari64/new_dynarec with NDRC_THREAD, x86 Lightrec, the accelerated GPU backend, async GPU/CD and SPU. Do not blindly override these settings.
- Mupen64Plus Next libretro/jni/Android.mk selects four dynarec architectures, plus ARM parallel RSP/RDP; its ARM32 NEON recipe also includes ARM32 assembly, so do not force that entire recipe on ARM64.
- PicoDrive jni/Android.mk only turns on use_sh2drc for TARGET_ARCH=arm even though cpu/sh2/compiler.c has an AArch64 emitter and the generic upstream Makefile enables it for aarch*. The new artifact-only picodrive-sh2 candidate uses the upstream Make variable on ARM64, preserving the default Android recipe. It targets Sega 32X specifically. The upstream executable mapping path and runtime DRC behavior must be tested on Android, including page sizes, before promotion.
- tune-a57 tests instruction scheduling (not a higher ISA or CPU requirement) with -mtune=cortex-a57 on arm64-v8a. It is not automatically selected by device model. No unsupported instructions or new fast-math options are injected.

The build report checks named translation units and effective defines together; -U overrides count and compiler response files are expanded within the workspace. The audited recipes fail when required evidence is missing. The PR build matrix now includes 16 jobs: the original eight plus ARM gpSP/PCSX ReARMed, ARM64 Mupen64Plus, baseline/candidate PicoDrive ARM64 and tuned Snes9x ARM64. These are compile gates; DRC selection/execution, timing and save compatibility need physical-device evidence.

After merge, examples:
    ./build.sh cores picodrive --profile picodrive-sh2 --abis arm64-v8a
    ./build.sh cores snes9x --profile tune-a57 --abis arm64-v8a

### Stage 3 CI results — 98f57d492df7b421a9f5d3859b60fc0064665f2b

- [PR checks](https://github.com/seinist/ambient-tv/actions/runs/45861264732): all 10 jobs passed, including unit tests, native Vulkan ABI checks and Slang runtime checks.
- [Core matrix](https://github.com/seinist/ambient-tv/actions/runs/45861264735): plan and all 16 build jobs passed; publish was skipped as designed.
- PicoDrive ARM64 candidate compiled `cpu/sh2/compiler.c` with `DRC_SH2` effective and linked successfully. Baseline binary: 1,623,040 bytes (107 compile commands); candidate: 1,738,456 bytes (108 compile commands), +115,416 bytes (+7.11%). This confirms the extra code is built, not that 32X runtime selects or benefits from it.
- Snes9x ARM64 Cortex-A57 profile passed the audit with `-mtune=cortex-a57` on all 58/58 C/C++ compile commands. Candidate binary: 2,946,912 bytes vs baseline 2,922,320 bytes (+24,592 bytes, +0.84%). The option tunes scheduling and does not authorize A57-only instructions.
- Neither candidate is promoted. There are no measured frame-time, CPU, thermal, energy, audio-underrun or gameplay results yet. Device testing must compare identical ROM/BIOS, core options, renderer, resolution and frame pacing with repeated warm runs; include 1%/0.1% frame-time percentiles and thermal steady state.

### Stage 4 — runtime evidence required before further promotion

The Libretro performance interface now exposes counters and CPU feature information to cores, but the current frontend records and logs registered core counters only when a core calls its log callback. It is not yet a frontend-owned per-frame profiler. Do not interpret build success or a binary-size delta as a performance win.

Next work should add bounded, low-overhead frontend timing around the serialized `retro_run` boundary (excluding pacing sleep and rewind serialization), report rolling p50/p95/p99 frame execution time and deadline misses, and correlate thermal status. Any Android Performance Hint Manager session must be scoped to the actual emulation worker and target work duration; it should remain optional on unsupported devices and must not be mistaken for a substitute for measuring or fixing slow cores. Keep logging off the hot path and aggregate off-thread. Physical-device validation is required before enabling ADPF or selecting experimental core variants by default.

No candidate is publishable through the signed baseline catalog.
