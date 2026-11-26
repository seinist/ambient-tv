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
    ./gradlew :libretrodroid:assembleRelease -PAPK_ABI=arm64 -PLIBRETRO_NATIVE_PROFILE=thinlto -PLIBRETRO_NATIVE_EXPERIMENT=true

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

### Stage 4a — native core execution tracepoints

Added `scripts/patches/libretrodroid/0210-libretro-perfetto-core-run-tracing.patch`.
It wraps every frontend call to `retro_run()` (normal forward emulation,
rewind preview, and netplay) in Android native trace sections named
`AmbientTV:Libretro/retro_run`. These markers can be captured with Perfetto
or Android system tracing and isolate the synchronous core call from the
frontend's surrounding frame work. They are not per-frame log messages and
do not add a Kotlin coroutine or allocation to the render loop.

This is a measurement aid, not a profiler result: trace collection must be
enabled by the operator, and trace instrumentation itself can perturb very
short frames. Compare captures with tracing disabled/enabled and use repeated
warm runs. The markers do not measure asynchronous audio callbacks, GPU
completion after submission, or presentation latency. The pinned native patch
must still pass a clean apply and Android compile in CI before the tracepoints
are considered validated.

## Stage 5 — corrective audit work (current candidate branch)

- The PR smoke matrix currently has 45 jobs. The complete comparison matrices are
  explicit, artifact-only dispatches (208 jobs each for 26 cores × 4 ABIs
  × 2 profiles); release publication remains full baseline only.
  `./build.sh cores --comparison baseline-thinlto --ref perf/libretro-core-builds`
  and `./build.sh cores --comparison o3-o3-thinlto --ref perf/libretro-core-builds`.
  Compiler-object cache keys include pin, ABI, NDK, profile and recipe hashes;
  the selected ELF is still checked for identity, alignment, exports and
  dynamic dependencies after every build.
- The selected-library audit follows the final link command through objects and
  locally produced archives, reports unrelated and unresolved inputs, and
  fails closed on unaudited candidate-local inputs. SwanStation's upstream
  automatic IPO remains unchanged in baseline builds; explicit experiments
  disable the automatic block and report effective LTO modes.
- Experimental native Gradle profiles require the explicit
  `-PLIBRETRO_NATIVE_EXPERIMENT=true` opt-in. The app release variant uses a
  separate application ID, version name suffix and debug signing for these
  experiments. The independent native-profile workflow builds the actual
  Gradle release library and audits the Ninja-expanded command database.
- The trace transformation runs after the complete pinned patch series and
  requires exactly two step calls, one netplay call and one rewind-seek call.
  Trace section names distinguish all four paths. The registry mutex is no
  longer acquired by every core perf_stop; counter objects remain core-owned
  and concurrent access to the same counter is outside the callback contract.
- Six additional pinned core recipes now have effective macro checks:
  Gambatte, Genesis Plus GX, Beetle PCE Fast, Beetle SuperGrafx, Beetle NGP and
  Beetle WonderSwan. These preserve existing upstream RGB565, ARM assembly,
  fixed-point or in-memory loading paths. This is build evidence, not a
  claim of new runtime acceleration.
- Off-device Perfetto analysis: export CSV using
  `SELECT name, ts, dur FROM slice WHERE name GLOB 'AmbientTV:Libretro/*'`.
  Run `python3 scripts/analyze_libretro_trace.py slices.csv --fps 60` using
  the actual content frame rate. Optionally add `--thermal thermal.csv` with
  timestamps on the same nanosecond clock and Android status (0..6), when
  real thermal samples are available. The analysis reports nearest-rank
  p50/p95/p99, max, core-budget overruns and thermal-status groupings. This
  is synchronous core execution, not display latency or full-frame deadlines.
  No percentile computation runs on the Android emulation thread.

All Stage 5 changes require fresh CI. Earlier green runs do not validate
this new code. No runtime improvement, full catalog completion, or native
candidate promotion is claimed before measured Android results.

## Stage 6 — full catalog path contracts and optional threaded SNES renderer

All 26 pinned recipes now have core-specific compiled source/macro contracts.
These are evaluated on the selected ELF's link graph, not unrelated compilation.
The catalog regression test checks every declared core and ABI; PicoDrive's
non-SH2 baseline variants and SwanStation's 32-bit x86 interpreter are explicit
exceptions to a mandatory dynarec contract.

The additional twelve recipes cover:
- Nestopia: CPU, PPU, APU and video renderer.
- Snes9x: CPU dispatch/execution and DSP.
- mGBA: ARM CPU, GB/GBA software rendering and VFS.
- Handy: Blip/Stereo audio and CRC-enabled ROM loader.
- Stella2014: M6502, TIA and TIA sound.
- Beetle VB: V810, GLES2/VR renderer option and Blip audio.
- FCEUmm: CPU, NTSC filter and sound.
- Mesen: CPU, PPU, video renderer and Blip audio.
- bsnes: CPU, fast PPU, DSP and integrated Game Boy.
- SameBoy: SM83 and internal GB path.
- RACE: portable CZ80 path and Blip audio.
- Beetle Lynx: RGB565 option, CRC loader and Blip/Stereo audio.

At Snes9x2010 pin 82904a95e9bab2c3e63395e4b9ece8fdca8b785d,
build/Makefile.common includes the upstream threaded PPU support but puts
HAVE_THREADS in CFLAGS; jni/Android.mk forwards COREFLAGS instead.
The artifact-only snes9x2010-threaded profile explicitly enables the build
macro and verifies the linked PPU, libretro entry point, rthreads and eventcount
sources. The upstream option snes9x_2010_threaded_ppu remains disabled by
default. Upstream drains the renderer before snapshot/reset and joins it on
unload. The option needs a core reload. Actual timing, determinism and thermal
behavior must still be compared with the same ROM on hardware.
Example: `./build.sh cores snes9x2010 --profile snes9x2010-threaded --abis arm64-v8a --ref perf/libretro-core-builds`.

The complete catalog audit does not establish that all cores have received
new source-level runtime optimizations. Existing fast paths are preserved,
and optimization candidates remain unpublished pending compile and runtime
evidence. Fresh CI for Stage 5/6 is required.

## Stage 7 — source-level core hotpath candidates

The artifact-only `core-hotpaths` profile builds nine exact-pinned source
transformations, with baseline/candidate ARM64 smoke builds (45 total PR jobs)
and manual selection across all four Android ABIs. The source transformations
require unique upstream anchors and the pinned fetch is verified before
application. Ordinary baseline builds do not apply these transformations.

- bsnes: negotiate the input bitmask capability and cache each of five
  digital pads lazily for one retro_run, after the existing input_poll.
  Preserve scalar fallback, other devices, port mapping and run-ahead scope.
- Mesen: copy only the actual serialized bytes and zero only the remaining
  padding, avoiding the redundant first write to the populated part.
- SameBoy: hoist constant dimensions and row sizes from the horizontal
  dual-screen copy loop; preserve pixel layout and video callback geometry.
- Beetle PCE Fast: reuse the already sampled bitmask in the turbo toggle
  path, preserving the old scalar fallback.
- Beetle SuperGrafx: combine digital select/start polling in the mouse
  mapping without changing mouse button short-circuit behavior.
- Beetle NGP: remove an erroneous all-zero AV reconfiguration before the
  actual initialized AV info.
- Beetle NGP, WonderSwan, VB and Lynx: write savestates directly into
  frontend-owned storage; do not realloc caller memory, report short writes
  even if upstream ignores them, retain existing state format, zero padding,
  check capacity and make dynamic writer growth overflow/OOM-safe.

Regression harnesses cover source-anchor drift, exhaustive digital masks,
save buffer equivalence, dual-screen pixel equality, bounded fixed writes,
canaries, padding and failure behavior. A standalone reconstruction of the
fixed-state writer harness passed C99 and C++11 with UBSan (145 capacities
per language). This is an isolated host test, not a full pinned Android build
or a real-game state comparison.

Examples:
    ./build.sh cores --profile core-hotpaths --abis arm64-v8a --ref perf/libretro-core-builds
    ./build.sh cores bsnes,mesen,sameboy --profile core-hotpaths --ref perf/libretro-core-builds

Actual Android CI and ROM-specific timing and compatibility remain required.
No source change is claimed for a core merely because it has an audit
contract or a compiler experiment. Do not equate the nine source-level
candidates (plus the earlier Snes9x2010 and PicoDrive focused candidates)
with proven optimization of all 26 core implementations.

## Stage 8 — individual candidate coverage for all 26 cores

The remaining fifteen pinned core recipes now have 24 unique, exact source
replacements in `scripts/core_hotpath_remaining.py`. Together with the nine
Stage 7 transformations and the two earlier dedicated profiles, every catalog
core has a concrete individual optimization candidate. This is coverage of
implemented candidates, not a measured ranking or a proof that every candidate
is faster on Android.

| Core | Candidate |
|---|---|
| Nestopia | Read the existing savestate stream directly into the frontend buffer; retain footer and padding. |
| Snes9x | AArch64 NEON deinterleaved loads and exact floor RGB565 average for hi-res merge. |
| Gambatte | AArch64 NEON rounded RGB565 two-frame mixing, preserving history writes and pitch. |
| mGBA | Separate the no-colour-correction blend loop from the LUT loop. |
| Genesis Plus GX | Specialize both FM quality paths for unity gain, avoiding per-sample multiply/divide. |
| Handy | AArch64 NEON exact RGB555/RGB565 two-frame LCD ghosting, preserving history. |
| Stella2014 | Read existing Serializer stream bytes without constructing a duplicate string. |
| Mupen64Plus Next | Skip sqrt/atan2 in the stick deadzone and widen squared coordinates before summing. |
| PCSX ReARMed | Do not query digital joypad state for mouse and lightgun handlers that never consume it. |
| FCEUmm | Reuse the already sampled digital mask for turbo buttons. |
| gpSP | AArch64 NEON rounded RGB565 blend; preserve the upstream buffer swap. |
| RACE | AArch64 NEON interleaved stereo stores for mono sample duplication. |
| ParaLLEl N64 | Exact squared-radius early-out for the integer-sqrt deadzone. |
| SwanStation | Reference existing disk path rather than allocating a duplicate string on snapshot. |
| Beetle PSX HW | One digital input bitmask for the ten VCD menu buttons, with legacy fallback. |

The ARM64 paths keep scalar fallbacks on other ABIs and for remaining tail
elements. The source-only profile preserves SwanStation's upstream IPO settings,
so the comparison does not accidentally change LTO at the same time. The
deduplicated PR matrix has 67 build jobs. The manual source profile covers 24
cores on four ABIs (96 candidate builds); the remaining two use
`picodrive-sh2` and `snes9x2010-threaded`.

Added tests exercise unique anchors, rejection of source drift without partial
writes, ARM64 kernel compilation, packed-pixel arithmetic, deadzone boundaries,
FM sample events and savestate-copy equality. A standalone C++11/UBSan
reconstruction of the arithmetic checks completed successfully locally:
2,655,360 pixel pairs (each checked for floor and ceil mixing) and 2,293,760
axis/deadzone combinations (each checked against real and floored radii).
A standalone Clang freestanding AArch64 compilation of the merge, rounded
mix and interleaved stereo kernels also succeeded. These are isolated checks,
not full pinned-core Android builds. The actual repository test suite and
complete ABI matrix remain unverified.

At this stage GitHub reports the feature branch 126 commits behind main and
the draft PR as non-mergeable, with no fresh PR workflow runs returned for
the tested head. The execution container cannot resolve github.com for a
checkout. Resolve integration conflicts and obtain fresh CI before promoting
any candidate; no production profile was changed or merged.
