Open-SVPFlow integration (experimental)
======================================

User contract
-------------
Settings > Appearance > Maximum performance is REQUIRED. Auto, Balanced and
Low always disable SVP, independently of hardware. In Maximum, choose SVP
separately in MPV/VLC picture settings (default: Off).
VLC offers SVP only when the app's own VLC 4 build (app/libs/libvlc-vulkan.aar)
is packaged; the Maven libVLC fallback has no ambientsvp filter and hides it
(BuildConfig.VLC_SVP). The saved choice is
retained when changing performance mode. MPV's older frameInterpolation
option is temporal resampling, not Open-SVPFlow; the two are not stacked.

Player UI (2026-10-18)
----------------------
Video panel > Motion > "SVP motion smoothing" is its own level; playback speed
stays directly under Motion. The level offers Off / Light / Balanced / Smooth /
Custom (shown when analysis values deviate from the preset; stored "auto" was
identical to Light and is migrated to it), target rate (display, half display,
at most 60 fps), a resolution limit (720p / 1080p default / 4K), a source filter
(up to 30 fps default / all), artifact suppression and scene-cut sensitivity.
"SVP - Advanced" is a further level with algorithm, block, overlap, precision,
search, synthesis backend and threads. Reset is a button. The entry row and a
status line name the reason when SVP is not interpolating (player, performance
mode, HDR, resolution, source rate, format/timing passthrough, overload).

Adapter metadata: "bypass" is the engine's bypass_reason only (1 overload,
2 runtime gate, 3 engine error); "passthrough" is the MPV adapter's reason for
handing frames through (1 format, 2 unavailable, 3 timing, 4 error). Only
bypass 1 or the native cost budget triggers the overload dialog. An explicitly
selected but unavailable Vulkan backend now falls back to CPU synthesis.
VLC has no filter metadata; its status uses the process-wide synthesized-frame
counter (no new frame for 4 s while playing = passthrough).

Live configuration (ABI 2)
--------------------------
The video panel exposes display-rate / half-display-rate targets, artifact
suppression and scene-cut sensitivity. Advanced controls override algorithm
(13/21/23), analysis block size, overlap, subpixel precision and search effort,
plus synthesis backend and CPU threads. Zero-valued native overrides inherit
the preset; overlap 1..4 maps to upstream overlap 0..3. Reset clears overrides.
The shared serialized SvpOptions is retained across performance gates.
MPV replaces the active filter when its full parameter string changes. VLC
recreates its native instance even when only an override changes, using the
existing position, track and play/pause restoration path. Live streams reopen
at the live edge. These transitions can briefly interrupt output.
Algorithm lookahead follows the actual algorithm, independently of preset.
The native engine, both adapters and JNI ABI check must be rebuilt together;
ABI 1 adapters must not load the ABI 2 configuration structure. The ABI lives in
include/ambientsvp.h (AMBIENTSVP_ABI), src/lib.rs (AMBIENTSVP_ABI) and
SvpNative.kt (SVP_NATIVE_ABI); SvpNativeAbiTest fails the unit tests when they
differ. At runtime the adapter loader rejects an engine with another ABI and
SvpNative logs the mismatch and keeps SVP unavailable. build.sh hashes the
adapter sources (prepare_svp_sources.sh --hash) and rebuilds mpv/VLC when they
change, so libmpv, libvlc and libambientsvp are rebuilt in one run.

Timing gaps (2026-10-19)
------------------------
A push rejected as discontinuous (-3) no longer passes frames through for the
rest of the file. Both adapters pass through for a cool-down (25 source frames,
doubling per further gap up to 32x) and then start SVP again; 750 clean frames
forget earlier gaps (include/svp_retry.h, tests/svp_retry_test.c). VLC still
tries its five quick history resets first. Format, unavailable and engine
errors stay sticky.

Runtime
-------
The pinned Open-SVPFlow portable Rust analysis/synthesis API runs without
VapourSynth, Python, proprietary SVP binaries, or OpenCL. Analysis is CPU;
synthesis runs the original WGSL kernel through wgpu's native Vulkan backend.
This compute device is separate from the player's output renderer. In
particular, lack of libplacebo Vulkan-output support does NOT prohibit SVP
Vulkan compute. Probe actual capabilities, not just the Vulkan version.

Automatic backend selection falls back to CPU if Vulkan is unavailable or
fails. This is copy-back + upload/readback, NOT a zero-copy hardware-decoder
pipeline. Do not advertise the motion analysis as GPU-accelerated.

Supported input: progressive 8-bit SDR I420/NV12, even dimensions, constant
source rate 5..120 fps, target greater than source, at most 120 fps / 5x.
PQ, HLG, Dolby Vision, high-bit-depth and opaque decoder pictures are rejected
without an intentional downconversion. MPV direct output and Media3 do not
use this filter. An unsupported stream/device continues ordinary playback.
VLC's MediaCodec output may omit its nominal FPS: it then probes twelve
consistent source-PTS intervals before enabling interpolation.

Safety
------
A process-wide native gate defaults OFF and is updated by the performance
resolver. Both adapters and FFI processing check it. CPU workers and retained
frames are bounded. Rational timestamps avoid 23.976/59.94 drift. Scene cuts
are copied rather than interpolated; perfect motion matches remain eligible.
Seek/discontinuity resets temporal history. No synthesis crosses a gap.
VLC clock/late-frame flushes retain compiled Vulkan resources and a validated
source rate. Safety budgets and bypass remain sticky for the filter instance,
so repeated flushes cannot defeat overload protection.

Every preset stops interpolation after sustained excessive compute cost;
grossly slow analysis is rejected after two frames (or a >500 ms analysis).
MPV also continually checks both decoder and renderer frame-drop counters
while SVP is attached. Overload pauses playback through the existing dialog
and removes SVP for that session. VLC currently uses the native compute-cost
protection, not MPV's decoder-drop/pause dialog.

MPV requests readable frames before plex-svp is added. The copy-back latch
is cleared once that filter leaves the vf chain, so steady-state playback
can return to opaque pictures. Copy-back stays only while deinterlace, a
CPU filter, or a loaded shader still needs readable frames. A previous
build kept copy-back for the whole session after a Tegra samplerExternalOES
failure on the mid-stream return; that latch is no longer held. No SVP
compute runs in the disabled state. A new session selects afresh.

VLC integration must be registered as a STATIC (pre-clock) filter in the
pinned VLC core. The interactive chain forbids timestamp changes and can
submit the same picture repeatedly. Instance-level filter options are used;
VLC 4 ignores a media-only video-filter setting. Switching SVP configuration
recreates that player's native instance with position/tracks preserved.
VLC upstream has no video-filter EOF-drain callback: the short look-ahead
tail may be omitted at end of a clip; MPV drains the finite tail explicitly.

Build
-----
Requires the existing Android SDK/NDK setup, stable Rust with Android targets,
cargo-ndk, jq, curl, and the existing native MPV/VLC host build dependencies.

  bash scripts/build_svp_android.sh
  bash scripts/build_mpv_android.sh
  bash scripts/build_vlc_vulkan.sh

Ordinary build.sh now prepares the shared engine and checks adapter hashes.
SVPFLOW_VERSION / SVPFLOW_REF in gradle/external-versions.properties manage
the upstream revision. Requires Python 3.11+ for the update/consistency tools:

  ./build.sh libs-check svp       # read-only upstream check
  ./build.sh libs-update svp      # update pins, Cargo.lock, shader and notices
  ./build.sh svp                  # tests + native build only, no APK

Unqualified libs-check/libs-update also include SVP; lib-check/lib-update are
aliases. Published semver releases are preferred, otherwise explicitly named
nightlies; no moving main-branch dependency. Updates stage all source assets
before replacing project files. API/GPU compatibility must still be tested.
Ordinary builds validate provenance, use locked dependencies, run CPU tests
before a changed build and cache each ABI by source/toolchain fingerprint.
REBUILD_SVP=1 reruns preparation. The Maximum-only runtime gate is unaffected.
The MPV Vulkan build explicitly preserves -Dvulkan=enabled on reconfiguration.
All four app ABIs are supported: arm64-v8a, armeabi-v7a, x86, x86_64. Set
SVP_ABIS to restrict local engine builds. CPU-only source verification:

  cargo test --manifest-path native/ambientsvp/Cargo.toml --release --no-default-features

Native Vulkan test (requires hardware Vulkan; not a software adapter):

  cargo test --manifest-path native/ambientsvp/Cargo.toml --release \
    --test stream vulkan_synthesizes -- --ignored

On hosts with unrelated implicit Vulkan injection layers, run that test with
VK_LOADER_LAYERS_DISABLE=~implicit~ to isolate the compute implementation.

scripts/vendor_svp_licenses.sh packages dependency notices from Cargo's locked
sources; missing workspace licenses are fetched from exact publish commits.
The generated assets/licenses/open-svpflow files are intentionally build
artifacts under the repository's existing ignored assets directory.

Device verification
-------------------
PlaybackAuditInstrumentation has an opt-in "svp=true" audit. It requires a
separate AUDIT_APP_SUFFIX=.svpaudit build, never the user's real installation.
Use a local progressive SDR motion clip with audio and >60 seconds duration.
It verifies novel-picture counters, Vulkan dispatch counters, rendered image,
audio pipeline, all non-Maximum gates, preference retention, reactivation and
seek. Native counters do not count mere fractional timestamps as interpolation.

Tests are not a promise of real-time 1080p/4K or HDR support. Performance and
thermal limits must be measured per device/content. This remains experimental
until a broader device/driver matrix and long-session testing are complete.

Verified 2026-10-02
-------------------
Focused Android/JVM suite: 52 tests passed. Rust CPU tests and explicit native
Vulkan moving-edge synthesis test passed. Engine built for all four Android
ABIs with 16 KB ELF load alignment. Builds are not device compatibility proof.

NVIDIA Shield (Android 11, Tegra X1 rev B, Vulkan 1.1.178), isolated .svpaudit
installation, progressive 320x180/24 fps testsrc2 + audio -> 60 Hz:
  MPV: 667 novel pictures / 667 Vulkan jobs in the final audit.
  VLC: 428 novel pictures / 428 Vulkan jobs in the final audit.
Both passed sustained interpolation, rendered-picture and audio-pipeline
checks, disabling in Balanced/Auto/Low while playback continues, saved-option
retention, Maximum reactivation and seeking. No physical-listening or remote
D-pad UX test is implied. The user's regular app installation was not replaced.

The Shield's am-instrument shell exits 139 AFTER reporting SVP AUDIT PASSED.
The crash buffer identifies shell app_process UID 2000, in NVIDIA EGL shutdown;
this is not the tested application process. Preserve that distinction in CI.

Limitation measured with the moving-checkerboard CPU-analysis benchmark:
640x360, 1280x720 and 1920x1080 all trigger protective bypass on this Shield;
the latter produces no novel pictures before bypass. The small video audit is
NOT evidence of real-time HD/4K interpolation. GPU motion analysis and a
zero-copy frame path remain future work, not features of this integration.

The broader 557-test JVM run had three failures outside the modified SVP files:
Nnedi3QualityCatalogTest.qualityLadderCoversEveryUpstreamNeuronTierForWin8x4,
SidebarNavigationPolicyTestKt.initializationError,
PlaybackPerformanceTest.clockRestartsAfterASeek. No clean-baseline rerun was
performed to attribute those failures; they were not changed as part of SVP.

Verified 2026-10-17: live SVP controls
------------------------------------
52 focused Android/JVM tests passed. Portable native tests and hardware Vulkan
tests cover custom algorithms, additional SAD masks, rational timestamps,
lookahead, reset and actual novel pixels. Engine and MPV/VLC adapters rebuilt
for all four ABIs; ARM64 Debug APK built.
An isolated .svpaudit installation on the emulator passed MPV and VLC live
configuration changes, same-preset parameter changes, position preservation,
paused changes, reset, performance gates, reactivation, seek and rendered video
plus audio-pipeline checks. Production panel screenshots were visually checked.
The clip was progressive 128x72 SDR at 24 fps with audio; emulator output was
temporarily 1280x720. This verifies behavior, not HD/4K or physical-TV speed.
That run did not exercise D-pad interaction or physical-device playback.

Crash and safety regression audit, 2026-10-17
--------------------------------------------
The production emulator crash in build 272 was reproduced with the current
engine in a standalone Android benchmark at 2560x1440. wgpu's transient mapped
texture staging path faulted in memmove; mapped-at-creation COPY_SRC alone also
faulted at 4K. Uploads now use explicit MAP_WRITE | COPY_SRC buffers and aligned
rows, avoiding both transient allocation paths. The regression test reads back
every luma/chroma byte, including unaligned small-image rows and repeated device
creation. Host Vulkan passed through 4K; Android passed through 1440p. The added
4K texture readback test aborted the host emulator in gfxstream, so full 4K
emulator validation remains open. The 4K Android streaming benchmark survived
and used the existing overload bypass when analysis exceeded its time budget.

8x8 blocks with 12.5% overlap are normalized to 25% at both Kotlin and native
boundaries, keeping CPU rendering and GPU fallback valid. The panel omits the
unsupported combination. MPV's Quality probe and recovery clear costly analysis
overrides while preserving the requested frame-rate divisor, backend and thread
count. Later overloads are still handled, and a manual edit starts a fresh probe.

46 focused JVM tests passed. All 15 host native/Vulkan tests passed, including
the pixel-exact upload regression. Native libraries rebuilt for all four ABIs.
Android reproduction/verification (benchmark arguments: width height frames
backend source-fps cycles; cycles rotate algorithms 13/21/23):

  benchmark 2560 1440 6 2 50 3 --verify
  benchmark 3840 2160 4 2 50 3

The first command requires novel Vulkan frames without bypass. The second also
allows the real overload protection to engage; it is not a 4K speed claim.
The isolated Android app passed MPV and VLC live CPU/Vulkan parameter changes,
paused changes, reset, position preservation, performance gates, reactivation,
seek, rendered video and audio checks. Both production panels also passed a
D-pad target-rate change and restore. These interaction tests use the small
128x72 clip; physical-TV playback and sustained HD/4K speed remain unverified.


Native performance work (PR #528)
---------------------------------
The current native pipeline changes batch source/field uploads and synthesis
into one submission, pool mapped staging buffers with a 48 MiB retained
budget, cache motion fields by exact contents and cache bind groups by texture
identity. Linear-luma textures are created and filled only when needed.
The CPU spare-frame pool has a separate 32 MiB retained budget. Automatic
worker counts scale with the frame size and CPU availability, reserving CPU
capacity for playback; explicitly chosen thread counts remain authoritative.

Driver pipeline caches are reused across engine instances in the same process
when the adapter supports PIPELINE_CACHE. The bounded cache is keyed by the
wgpu adapter cache key and driver identity. On Android it can also persist
in the app's private cache directory, with owner/mode, format, driver key
and corruption checks. Arbitrary imported cache blobs are not accepted.

Use the native benchmark with its existing positional arguments plus:
  benchmark 1280 720 240 2 24 1 --threads=3 --preset=1 --no-checksum
  benchmark 1280 720 240 2 24 1 --threads=0 --preset=1 --no-checksum --require-realtime
  benchmark 1920 1080 240 2 24 1 --target=48 --no-checksum

Compare identical source clips, settings and device thermal conditions.
Processing time excludes synthetic source generation; checksum is optional
because it adds a full CPU pass per output. A protective bypass is not an
SVP speed success. --require-realtime rejects bypass, absent novel images or
processing below the source cadence. Use --verify when the exact backend
must remain active. Neither flag constitutes a display or audio-sync test.

Remaining architecture work: GPU motion analysis, CPU/GPU overlap across
source pairs, and cross-renderer hardware-buffer zero-copy. At the pinned
upstream revision, svpflow1's analyse/mod.rs has a native OpenCL path, but the
portable Analyser::analyse wrapper calls it with keys=None, disabling the
keyed GPU branches. The Android portable path therefore remains CPU analysis.
A Vulkan implementation must preserve the upstream vector header, multi-stage
search semantics, forward/backward regions, scene-cut decisions and CPU
fallback; a standalone block-matching demo is not an equivalent replacement.
Do not enable a new analysis backend before motion-vector and output parity
tests. Physical-device benchmarks and native integration remain required.


Further native optimizations (PR #528, continued)
-------------------------------------------------
Exact repeated source images reuse the previous superframe and stationary
analysis payload. The comparison covers all three planes; cache invalidation
occurs on a changed source, seek/reset, adaptive analysis change and bypass.

For <=1080p, consecutive GPU outputs from the same ready source pair can
use two independent readback buffers. Both ordered queue submissions are
issued before waiting for either result. The second mapped readback is held
until the next receive and copied directly to the caller's output; reset,
safety bypass and GPU fallback discard it.
The existing timestamp plan, queue bounds and CPU fallback remain in use.
This is not yet a multi-source CPU/GPU pipeline or a zero-copy renderer path.

Optional RGBA16Unorm motion fields halve the base/extended texture and upload
size on adapters supporting the required format feature/usages. The runtime
variant rescales RAW16 before rounding; the managed upstream WGSL stays
byte-identical and the float path remains available. The portable unit test
checks all 65536 u16 values; the ignored hardware test compares full output
pixels against the original float path. Physical driver parity is pending.

The new explicit Adaptive preset (native number 4, existing ABI layout) is
available in MPV and VLC. Old stored "auto" still means Light. Adaptive
starts with light geometry, and may switch upstream analysis fast levels
0 -> 1 -> 2 under load without changing the configured frame-rate target,
block layout, pel, timestamps or queued history. The upstream vector header
must remain identical before replacing the analyzer. The absolute 500ms
safety ceiling and overload bypass still apply. The selected adaptive
preset remains saved; a seek preserves the session's reduced effort.
Analysis quality can decrease in this opt-in mode. Fixed presets never
participate. MPV retains its dropped-frame and native bypass watchdogs
but does not issue a separate cost-only verdict for the adaptive preset.


Direct transfers and overlapping source upload (PR #528)
-------------------------------------------------------
The optional ABI-2 push_fill entry point validates capacity and timestamps
before calling a synchronous adapter writer. MPV and VLC pack decoded
I420/NV12 directly into an owned/recycled SVP source buffer, eliminating the
adapter scratch-to-engine copy. Old libraries use the original push API.
The writer must not retain the pointer or re-enter the engine. A rejected
writer does not advance the timeline. No decoder hardware buffer is imported.

For 720p through 1080p (by pixel count), Vulkan can stage a new packed
source before CPU superframe construction and motion analysis. The source
cache is bounded to three speculative entries; queue ordering guarantees
later synthesis sees the upload. A rejected input discards its staged
index, and validation failure falls back to CPU. The additional submission
may not benefit every driver: benchmark on actual devices.

MPV reuses up to six output images with a 32 MiB retained backing budget,
using the pinned MPV image-pool ownership API. A direct output slice is
used only with tight I420 strides and proven bounds inside a single
writable AVBufferRef; padded layouts still use the existing transfer.
A normal seek preserves the warm engine and discards temporal history;
a bypassed engine keeps the previous explicit-seek retry behavior.
VLC continues to use its normal picture allocator and output conversion.
None of these changes constitute decoder-to-renderer GPU zero-copy.

Optional profile-guided Android build:
  SVP_ABIS=arm64-v8a SVP_PGO_PROFILE_DIR=/absolute/profiles ./scripts/build_svp_android.sh
The directory must contain arm64-v8a.profdata and arm64-v8a.meta (and one
matching pair for every requested ABI). To collect a synthetic training
profile on an attached device without replacing the app runtime:
  bash scripts/profile_svp_android.sh arm64-v8a /absolute/profiles DEVICE_SERIAL
The metadata must match source fingerprint, Rust toolchain, ABI and profile
digest. Profile generation and performance measurement are separate from
release packaging. Synthetic training is not a real playback benchmark.
The profile digest and flags are included in the per-ABI build key.
No profile is generated or applied by default; no PGO gain is asserted.


Vulkan motion estimation primitives (PR #528)
--------------------------------------------
The Vulkan-only motion module implements batched integer SAD and 4x4 tiled
SATD on the same packed R8Unorm I420 source texture layout used by synthesis.
Each workgroup evaluates a candidate, reduces per-plane costs and writes
the resulting Y/U/V values to a storage buffer. The CPU reference includes
the upstream I420 chroma weight (4x). Supported interior integer-pel blocks
are 8/16/32 pixels per dimension, with both directions and positive or
negative candidate displacements. Invalid geometry, texture layout, batch
size and storage/dispatch limits are rejected.

The new Vulkan test compares 540 candidate geometry/offset/direction/metric
combinations to the scalar reference. Native CI explicitly runs this test
using software Vulkan, plus the paired-output and compact-field synthesis
tests. Software adapters remain rejected in production. These tests are
correctness checks, not throughput measurements.

The candidate kernel does not yet replace the portable upstream Analyser.
Predictor-dependent search ordering, pyramids, border extension, subpixel
sampling, SATD gating, penalty/recalculation stages and vector serialization
still need integration and parity tests. Do not select this module as a
production motion-analysis backend until those stages are complete.


Final optimization and validation scope
---------------------------------------
A private Android pipeline cache is configured only when SVP is enabled.
It retains one driver-qualified entry, at most 8 MiB of payload, and one
bounded temporary file. The native implementation checks UID, private
permissions, regular-file type, link count, size, driver key and envelope
checksum. A cross-process lock and atomic rename prevent concurrent partial
writes. The checksum is for corruption detection, not authentication:
trust is the app UID sandbox; never use external/imported cache files.
Missing JNI support, unsupported driver, or failed I/O simply leave the
in-memory cache/normal pipeline compilation in use. Unit tests cover
envelope corruption and private-file permissions/symlink rejection.
Android-only JNI compilation is included in CI.

The synthesis dispatch now overwrites every output word without a separate
full-buffer clear. A/B tests retain the previous clear path and compare
unaligned Y/U/V boundaries and output reuse across seeks under Vulkan.

The benchmark reports p50/p95/p99 push+drain latency, output cadence and
throughput, and verifies monotonic timestamps, the exact output count,
no bypass, actual requested backend and a minimum novel-frame ratio
when --require-realtime is supplied. --algo=13/21/23 fixes the algorithm
across repeated cycles; --min-novel-percent= sets the threshold (50 default).

The native SVP CI at fbb38f12 passed CPU, Vulkan and C tests, including
the software Vulkan motion and synthesis tests. The full Android suite
reported 11 focus/navigation failures; attribution has not been verified.
New cache, PGO, and clear-elision changes require current-head CI and
physical device validation. The experimental candidate metric is not a
complete GPU motion analyser. Decoder-to-renderer hardware zero-copy
and full cross-source compute overlap remain separate architectural work.


Direct output visitor and allocation preflight
-----------------------------------------------
The optional ABI-2 receive_visit and ready entry points extend direct
input fill. The engine can deliver borrowed source-frame bytes or mapped
Vulkan readback bytes synchronously to an already allocated player picture.
MPV copies directly into padded planar I420; VLC copies directly into
cropped I420 or interleaves NV12. Neither adapter allocates its former
packed scratch frame when both optional direct APIs are present. Old
ABI-compatible libraries retain the original buffer path. CPU synthesis
into a contiguous destination remains direct; a padded destination uses
one engine-owned recyclable scratch buffer for CPU synthesis only.

The callback receives read-only bytes valid for the duration of the call.
Output readiness avoids acquiring player pictures when more input is
required; it conservatively allows stale bypass frames to be retired.
Timestamps are validated before delivery. CPU and Vulkan tests compare
the visitor against the original contiguous interface, including seeks,
EOF, bypass, prefetch and exactly-once callback delivery.

This removes intermediate CPU copies/allocations, not the physical
GPU-to-CPU readback or decoder copyback. Hardware-buffer sharing across
decoder, analysis and renderer remains unfinished.

Validation: 268d97eb1bbc820f0b65154016552c9dec8285b0 passed native CI,
Mesa Vulkan parity, Android JNI cross-check and private cache tests.
The 11 unrelated focus/navigation test failures also occurred in PR #524
on the same main base 30095218ec87b6213eab030916d8db0f974f44c7 without
these SVP changes (run 46004250901, unit-tests job 140523738465).
Current-head native/Android checks and physical MPV/VLC playback still
need validation.


Subpixel candidate costs and cross-pair synthesis scheduling
-----------------------------------------------------------
The experimental motion cost kernel now accepts 1, 4 or 16 texture-array
layers, corresponding to exact integer, half- or quarter-pixel phases.
SuperLevel borrows the pinned SuperBuilder's actual Y/U/V phase planes
and reduced pyramid levels, respecting each plane's original row pitch.
It uploads directly to a bounded mapped staging buffer and compacts only
the selected level, not the entire superframe. Both CPU and Vulkan metric
references shift signed I420 chroma motion before splitting integer and
fractional coordinates. Negative offsets, pel=1/2/4, pyramid dimensions,
both vector directions and SAD/SATD are covered by parity tests.
Unsupported geometry and budget limits reject the batch; no approximate
edge sampling is silently introduced.

Paired synthesis now permits adjacent source pairs when both are already
available, never requiring additional input or extending the latency
window. The second queue submission follows the first readback copy;
texture reuse remains ordered. The extra readback is still bounded to
1080p and a single future output. A 24->36 fps batched-input regression
requires actual cross-pair scheduling and compares serial output bytes.

Native loader fixtures also exercise old ABI-2 runtimes without optional
symbols, new runtimes with direct input/output symbols, ABI mismatch and
missing required symbols.

The previous direct-output head 37023d7c had passing CPU tests, but its
Vulkan check was canceled by newer commits. New-head parity, Android
checks and renderer builds are required. The motion metric still does not
replace the upstream predictor-dependent search pipeline; complete GPU
motion estimation and decoder-to-renderer hardware-buffer zero-copy remain
unfinished. No device performance gain has been measured.


Ordered candidate selection (experimental Vulkan motion stage)
-------------------------------------------------------------
The selection kernel accepts independent blocks with explicit initial
winner, predictor, lambda, new-vector penalty, chroma and SAD-gating
settings, and ordered candidate ranges. It implements the pinned
upstream try_candidate_at arithmetic and strict less-than replacement,
including wrapping i32 penalties, evolving raw-SAD gates and stable
ties. Raw luma SAD is computed alongside SATD from the same pixels
in the metric kernel and stored in the fourth cost-buffer word.
The selection stage consumes that GPU storage directly in the same
command encoder, with only winning vector records read back. No extra
raw-SAD dispatch or per-candidate host readback is needed.

CPU reference tests cover gate updates, ties, invalid ranges, and
overflow-sensitive penalty arithmetic. Mesa tests compare synthetic
cost lists and a chained real superframe upload -> pel=1/2/4 SAD/SATD
-> ordered selection -> vector readback against CPU references.

The caller still supplies upstream candidate generation, predictor
dependencies, bounds and initial winner. This is not a complete
production GPU analyser: wavefront/pyramid search orchestration,
adaptive search/early exits, border and differing luma-offset semantics,
refinement and vector serialization still require integration with the
pinned upstream library. The CPU analysis path remains active.

The exact previous head 11f31a8155a9e6fe91a04c85c4503c8abd6a924f passed
all native tests, including actual Mesa subpixel/pyramid metrics,
adjacent-source synthesis, direct output visitor and C loader fixtures
(run 46034930487). The Android test run had the same 11 pre-existing
navigation failures among 6495 tests. Current-head selection shader
and chained tests still need CI confirmation; no device speed claim.


Native borrowed-source smoothing
--------------------------------
The runtime now adapts the pinned upstream Smoother wrapper in
src/smoother.rs; the upstream Engine and Job implementation and exact
band-rendering code remain in use. The wrapper borrows original I420
planes from the existing bounded native source queue instead of copying
every picture into another map. The original wrapper is retained as a
test-only dependency. Motion-vector payloads move into shared ownership
without copying their allocation; exactly repeated inputs share payloads
and CPU-expanded subpixel planes.

When Vulkan is active, the CPU-only interleaved pel buffers are no
longer eagerly generated or stored. If CPU fallback is required, the
smoother reconstructs the needed superframes from retained source data
with the same upstream SuperBuilder and caches the interleaved planes.
Normal CPU sessions expand once as before, with HD plane expansion
distributed across the existing session pool. Forgetting old entries
releases shared references without creating a second source map.

Reference tests compare output bytes and plans against the pinned
original Smoother for pel=1/2/4 and algorithms 13/21/23, including
GPU planning followed by lazy CPU rendering and repeated-frame
allocation sharing. These are native correctness tests, not device
throughput measurements.

Exact previous head e1c967ede57e9d9d3b72db3eda05b2b396547c29 passed the
entire SVP native workflow 46037094296, including the fused SAD/SATD
and ordered candidate-selection shader and chained GPU parity tests.
The borrowed-source wrapper now changes the actual production path;
its current-head CPU/Vulkan/Android checks remain to be verified.
Full GPU search orchestration and hardware-buffer zero-copy remain
unfinished; the experimental motion stage is not enabled in playback.


Prepared-field vector payload bridge and update integration
-----------------------------------------------------------
PacketLayout validates the full upstream analysis header, supported
full-resolution layout, grid geometry and complete bidirectional or
backward-only regions before packing selected vectors. It applies the
upstream packed_score distance weighting to unpenalized SAD (not the
motion-selection cost), retains exact signed component encoding and
restores the original header including pyramid levels. Reduced-level
GPU mode, truncated fields, unsupported headers and oversized grids
are rejected. Exact block luma and raster order are supplied by the
caller; the bridge does not fabricate predictor-dependent search data.

Tests compare complete static packets against the real pinned analyser
at pel=1/2/4, exercise signed component packing and distance weighting,
and run GPU-selected complete prepared fields through the upstream
decoder and smoother against independently selected CPU reference
fields. This validates the packet interface, not parity of an entire
upstream dependent search.

The managed dependency verifier now checks svpflow-wasm in
dev-dependencies, with explicit missing/duplicate/stale-pin regressions.
Native CI runs the source verification and updater tests before the
Rust tests; the adapted source retains its fixed original attribution
even when the upstream pin is updated.

Exact previous head 8fd9ed28a55086c32480621153b999af1be4c920 passed
CPU and portable Vulkan regressions, original-vs-borrowed smoother
pixel parity, and all executed Mesa Vulkan tests in run 46038094757.
The remaining JNI/C steps were canceled by superseding commits.
The general CI failed early in the updater tests due to the relocated
test-only dependency; fixed in this revision, awaiting CI confirmation.
Full GPU predictor-dependent orchestration, hardware-buffer zero-copy,
actual renderer builds and device performance validation remain open.


Executable experimental hierarchical Vulkan motion search
---------------------------------------------------------
The explicit Rust-only benchmark flag --experimental-motion now
selects a real bidirectional coarse-to-fine search, rather than only
testing individual cost and selection kernels. Example (on a device
with a supported Vulkan GPU; built with the default Vulkan feature):

  benchmark 320 180 48 2 24 1 --experimental-motion --no-checksum --verify

Use the same arguments without --experimental-motion for the pinned
CPU analyser plus Vulkan synthesis comparison. Experimental mode is
not exposed by the Android C ABI or enabled in any player preset. It
rejects adaptive mode, unsupported layouts and GPU-unavailable
sessions. The initial limits are 720p, four pyramid levels, 16384 blocks
per field, one million candidate metrics per pair, bounded batches
and a 250ms deadline. The original source frames are reused as
SuperBuilder inputs; each level's phase textures are uploaded once for
both directions. The coarse winner is propagated to the next grid,
where a bounded local window and zero-vector window are evaluated.
The cost and selection stages are chained on GPU, only winning
vectors are read back; valid final fields are packed and fed into
the normal smoother. Failure retires the experimental path and
falls back to the pinned CPU analyser and CPU synthesis; the benchmark
asserts no failures, full changing-pair coverage, correct cadence,
actual Vulkan synthesis and novel images, and reports experimental
pair counts separately.

This is a distinct, experimental search policy, not a port of the
upstream predictor-dependent search. It does NOT implement identical
neighbor processing, adaptive radii, search ranking, refinement or
quality equivalence. Do not select it automatically or claim speed or
quality gains before physical-device comparisons. The tests exercise
multi-level search, pel=1/2/4, two directions, deterministic complete
packets, bounded timeouts and actual streaming output.

Exact preceding head 3cdf5799df1f4132236f07ae9d3d4dddbe463ef8 passed all
native checks (46039008235), including updater verification, the
prepared-field serialization and upstream smoother tests, Android JNI
and C loader checks. SmartTube integration also passed. Current-head
experimental search and streaming tests still need CI confirmation.
Hardware-buffer zero-copy and full upstream search parity remain open.


Adjacent-pair motion texture reuse and translation quality comparison
--------------------------------------------------------------------
The experimental search now retains up to 32 MiB of next-picture
pyramid textures under their exact session source index. The adjacent
pair reuses those textures rather than uploading the current picture
again; an index mismatch, seek, rejected input or failed search
invalidates cached state. Cache-vs-uncached packet parity is tested.
For pel=2/4, local fractional candidates are supplemented by whole
pixel anchors within +/-2 pixels of zero, with bounded candidate
counts. This is a search policy change, not upstream vector parity.

For quality, run two independent otherwise identical benchmark
sessions on supported hardware (24 -> 48 fps, four-pixel source motion):
  benchmark 320 180 48 2 24 1 --target=48 --quality-translation --no-checksum
  benchmark 320 180 48 2 24 1 --target=48 --quality-translation --no-checksum --experimental-motion

The quality fixture generates exact halfway images, excludes edge
pixels, source-copy positions and final EOF clamping, and reports
luma PSNR/MAE and the better nearest-source baseline. Throughput
includes quality evaluation in this mode, so use a separate ordinary
benchmark run for performance. The same fixture is exercised in Mesa
CI with both analysers sharing the Vulkan synthesis backend, and
quality statistics are printed without asserting an unsupported
general quality equivalence. Natural video, occlusion, scene changes,
fast motion and physical-device profiling are still needed.

Exact prior head f67cdbc89caf938bdb58615cfd2e5b7df550c64d passed CPU,
portable Vulkan and actual Mesa tests (run 46040903207), including
hierarchical fields and streaming interpolation. The remaining JNI/C
checks were canceled by superseding commits. Current-head texture
reuse, integer-anchor and quality-comparison tests await validation.


Measured translation quality and batched direction execution
------------------------------------------------------------
Native CI run 46045383181, exact head
64bdf8c5a16289132d5f17eb5ca3ab8bb04ebdca, passed every native check
including actual Mesa execution, Android JNI and C adapter helpers.
On the unchanged 96x66 24-to-48 fps translation fixture the experimental
search improved from 33.357 dB PSNR / 1.846 luma MAE to 38.280 dB /
0.742 MAE; upstream CPU analysis remained exact (infinite PSNR,
zero MAE). The nearest-source baseline is 17.692 dB. The cumulative
analysis time was 55640 us for software Vulkan and 1626 us for CPU.
These are correctness/quality measurements, not physical GPU speedups.

The subsequent revision combines independent forward/backward search
jobs within bounded GPU batches, preserving per-block candidate order,
and retains a single unmapped winner-readback buffer for reuse. Map
timeouts/errors retire the buffer; only a successfully unmapped buffer
is returned to the reuse slot. Tests compare serialized packets against
separately submitted directions, and assert fewer batch readbacks
on the small fixtures. The measured improved quality becomes a
regression gate (>=38 dB, <=0.8 MAE for this fixture). New diagnostic
logs compare decoded motion vectors, scores and luma against the
upstream first-pair packet to localize remaining quality differences.
Actual hardware performance, broader video quality and full upstream
search parity still need validation; regular player presets remain
unchanged.


Android hardware-buffer integration (API 29+)
---------------------------------------------
Two optional ABI-2 symbols, ambient_svp_push_ahardwarebuffer and
ambient_svp_receive_ahardwarebuffer, accept actual CPU-mappable
AHardwareBuffer references (Y8Cb8Cr8_420, one layer). Input is mapped
only inside the validated direct-fill callback, copied into the existing
engine I420 allocation, and unlocked before motion analysis. Output
preflights readiness and maps a caller-owned buffer before consuming
a tick. Planar and interleaved chroma, padded rows and even crop offsets
are checked. Protected, opaque, unsupported, or unready buffers return
-4 without consuming a frame. A borrowed acquire fence is checked
without consuming or closing its FD; -1 requires caller synchronization.
A returned release fence belongs to the caller, including on failure.
A NULL release-fence pointer requests synchronous unlock. The driver
can still block inside a CPU lock/unlock; this is not a real-time
external-memory import. See include/ambientsvp.h for the API contract.

MPV now accepts transferable Vulkan AVHWFramesContext decoder images
when MPV's pinned transfer helper reports I420/NV12. The adapter reuses a
software image pool and does not release the original reference until
a successful download. Unsupported/opaque hardware frames pass through.
This is a hardware-to-host transfer, not zero-copy. The VLC filter
continues to accept its existing CPU-accessible I420/NV12 pictures.
Neither player supplies a native AHardwareBuffer to the filter yet:
MediaCodec surface handles are not AHardwareBuffer pointers. MPV's
AImageReader obtains its buffer later, in the hardware renderer.
Do not cast opaque MediaCodec handles, release a buffer to two consumers,
or enable the hardware-buffer API for unsupported formats.

Host layout roundtrip and dynamic-loader optional symbol tests run in
native CI. The Android-only ignored test
hardware_buffer::android::device_tests::hardware_buffer_roundtrip_matches_native_i420_stream
allocates real hardware buffers and compares crop, fences, timestamps
and output against the native I420 stream. Cross-check compilation is
part of native CI; run the test executable on an API29+ physical device
that supports CPU-mappable YUV420 (isolated exact ignored test).
Actual device execution and full player build validation are pending.

A full zero-copy decoder-to-synthesis-to-renderer path still requires
a producer that exposes owned YUV hardware buffers, imported Vulkan
external memory on a compatible shared device, explicit acquire/release
synchronization and compatible output presentation. The upstream CPU
motion analyser needs host I420 data, so a GPU-only path also needs a
validated hardware motion-analysis implementation. No such path is
silently enabled by these optional APIs.


Experimental native GPU-resident Vulkan transport
--------------------------------------------------
The resident module records bidirectional block motion estimation and
multi-phase SDR synthesis directly on renderer-owned GPU images. The
native ash/Naga implementation (ambientsvp_vulkan.h) borrows the actual
VkInstance, VkPhysicalDevice and VkDevice and records into an already
recording caller command buffer. No pixel upload, CPU mapping, motion
readback, or CPU synthesis is performed. Motion fields are device-local
storage buffers; the caller supplies R8/RG8 planar/NV12 image views and
a distinct RGBA8 storage output. Visible dimensions, padding, color
matrix/range and supported chroma siting are explicit. Up to eight
phases share one motion field; four pairs may be outstanding. Each
pair's resources survive session destruction and are retired only
after its submitted fence signals or the device is confirmed lost.

The MPV filter now has an EXPLICIT EXPERIMENTAL hwresident=1 option:
    --vf=ambientsvp=source=24:fps=60:hwresident=1
With the pinned libavutil 61 and Vulkan-enabled MPV, eligible actual
AV_PIX_FMT_VULKAN decoder frames are processed on the decoder/renderer's
same VkDevice. FFmpeg frame locks and unique timeline semaphore waits
and signals coordinate access, including the output AVVkFramesContext
pool. Inputs, image views, command pools, descriptors and output frame
references are retained until the job fence retires. No queue-wide
idle wait or CPU pixel transfer on the normal resident path. Queue
family ownership, sync2/timeline features, format/usage, color, cadence
and in-flight bounds are checked before submission. Unsupported input
falls back to the existing software transfer/passthrough. Seek and EOF
are handled separately; the last source is preserved at fallback.
If driver retirement fails at teardown, the adapter retains the state
and library rather than freeing in-flight resources. That emergency
path needs physical-device testing.

This is a distinct integer-motion experimental algorithm, not the
pinned upstream SVP search/synthesis parity path. Regular presets and
app defaults remain unchanged; hardware-resident output ticks are not
reported as proven novel frames. No automatic enablement. The native
Vulkan CI test creates GPU images on a borrowed device, records three
phases, verifies the fence lifetime and reads the output only in the
test. The MPV helper is syntax-checked against the pinned FFmpeg public
ABI; full player builds and physical playback/quality tests remain
required.

Android MediaCodec/VLC zero-copy is NOT complete. The current MPV
MediaCodec AImageReader is owned by a GLES renderer and acquires its
PRIVATE hardware buffer only after releasing AVMediaCodecBuffer at the
mapper; an opaque AVMediaCodecBuffer is not an AVVkFrame or AHB. A
producer/renderer bridge must preserve one-time release and acquire
semantics, imported AHardwareBuffer memory/YCbCr external-format
sampling, foreign queue ownership and acquire/release sync_file
semaphores. The output must then be presented through a compatible
Android renderer. The separate CPU-mappable AHB ABI remains available,
but is not the native GPU-only path. VLC's software I420/NV12 filter
also needs a hardware producer/renderer integration. wgpu 24's
create_texture_from_hal tracks imported images as UNINITIALIZED; the
native Vulkan implementation avoids guessing external image layouts
through that API. These boundaries prevent claiming complete Android
decoder-to-display zero-copy from the new MPV Vulkan transport alone.


Android MediaCodec Vulkan external-memory bridge (experimental)
---------------------------------------------------------------
The new native Android ABI (include/ambientsvp_android_vulkan.h)
provides a PRIVATE AImageReader with GPU_SAMPLED_IMAGE usage,
maxImages=4, a borrowed ANativeWindow and a Java Surface local ref.
Configure the MediaCodec output with that Surface BEFORE decoding.
Release each codec output to it exactly once; acquireNextImageAsync
preserves frame order and the actual decoder acquire sync_file.
The lease owns AImage and retains its parent reader. Dropping an
unsubmitted lease returns the original producer fence.

The importer borrows a Vulkan 1.1+ device/compute queue, requiring
VK_ANDROID_external_memory_android_hardware_buffer,
VK_EXT_queue_family_foreign, VK_KHR_external_semaphore_fd and
enabled samplerYcbcrConversion. It imports the ORIGINAL AHardwareBuffer
allocation using dedicated VkDeviceMemory and its actual reported
externalFormat, rather than inventing a Vulkan YUV format or CPU mapping
the opaque image. It uses the driver's suggested immutable YCbCr
conversion sampler and a compiled combined-sampler compute shader,
which samples the visible crop into a GPU-local RGBA8 image. No host
pixel copies or GPU readback in this path. This GPU color conversion
does write an intermediate image; the original buffer is imported
without copying its backing memory. Only explicit SDR8 metadata from
the decoder is accepted, not protected, HDR or unknown streams.

A duplicate of the acquire FD is temporarily imported into a binary
Vulkan semaphore; the original remains with the AImage lease. The
command buffer acquires/relinquishes FOREIGN_EXT queue-family ownership
with GENERAL layout, and exports a release SYNC_FD after submission.
AImage_deleteAsync returns the buffer to the producer with that FD.
If export fails, hold the image until the conversion fence completes.
Device loss preserves the original producer fence if still needed.
The caller externally synchronizes the borrowed compute queue.
There are at most four outstanding conversion jobs. The output remains
on that same queue/device; cross-queue presentation needs explicit
additional synchronization and is not implicit.

ambient_svp_vk_begin_ahb connects two such GPU RGB textures directly
to the existing resident motion/synthesis path. It checks matching
device, queue and geometry and pins the import jobs until pair
retirement. RGB motion uses a luma proxy; synthesis does not apply
YUV conversion a second time. No production Open-SVPFlow parity or
hardware speed claim is made for this distinct resident algorithm.
The ordinary YUV/CPU fallback is unchanged.

Build: the Android-only combined immutable sampler shader is compiled
with NDK glslc (or SVP_GLSLC override; glslangValidator is the CI
fallback). Shader and build.rs are part of the engine source/PGO
fingerprint. CI validates SPIR-V, cross-checks the Android API and
device test, and runs an RGB-resident GPU test on Mesa.

The ignored Android device test
ahb_vulkan::device_tests::mediacodec_surface_external_memory_reaches_resident_synthesis
configures an actual MediaCodec with the new PRIVATE Surface, decodes
an original synthetic AVC IDR, acquires/imports it and runs resident
synthesis, then verifies a final test-only readback. Set
SVP_TEST_MEDIACODEC_NAME to select the specific hardware decoder on
the target device. This test needs a real Android device with the
required Vulkan extensions; cross-compilation is not execution.

Remaining player integration: the current MPV Android MediaCodec
renderer still owns its existing GLES AImageReader. The new Surface
producer must be selected by a Vulkan-aware MPV decoder/renderer
bridge, with actual decoder timestamp matching and compatible output
presentation; VLC needs its own integration. Neither currently calls
the new Android ABI in normal playback. Do not release one MediaCodec
buffer to two Surface consumers or reinterpret AVMediaCodecBuffer as
AHardwareBuffer. Regular presets and application defaults remain
unchanged until end-to-end player/device validation.


Experimental actual player MediaCodec Surface selection
--------------------------------------------------------
The native source integration scripts now patch the pinned MPV and VLC
sources during their regular Android library build. No changes to the
ordinary default paths.

MPV (Vulkan/gpu-next, explicit test flags):
    --vo=gpu-next --gpu-api=vulkan
    --gpu-hwdec-interop=mediacodec-svp-vulkan
    --hwdec=mediacodec,mediacodec-copy
    --vf=ambientsvp=source=24:fps=60:hwresident=1

The explicit-only interop creates the decoder's actual PRIVATE Surface
before configuring MediaCodec and registers the matching AVHWDevice.
The decoder hook checks 8-bit SDR metadata and matches the AImage's
timestamp to the released MediaCodec output. The converter writes
directly into the player's allocated RGBA AVVkFrame on the same Vulkan
device, with FFmpeg's frame lock, queue lock and timeline semaphore
handoff. The resident filter accepts those RGB frames and sends its
own Vulkan frames to the existing renderer mapper. Ineligible or
failed imports trigger MPV's normal decoder fallback (never release a
MediaCodec output twice). The device extensions/YCbCr feature are
requested opportunistically; no implicit enablement of the interop.

VLC 4 custom Android build (explicit test flags):
    --vout=android-display --mediacodec-dr
    --mediacodec-svp-vulkan
    --video-filter=ambientsvp{fps=60,hwresident=1}

VLC already has an ordered PRIVATE AImageReader and SurfaceControl
producer/consumer path. Select its GPU_SAMPLED_IMAGE usage for
explicit verified 8-bit AVC profiles and ordinary rectangular output,
rather than creating another Surface. The pre-clock SVP filter uses
the actual AImage picture, borrows a hardware buffer and producer
fence and retains a source-picture token until a GPU handback fence
is published. A bounded owned Vulkan compute device imports and
samples the AHB; resident motion/synthesis writes directly into fresh
compositor-capable RGBA AHardwareBuffers. SurfaceControl receives
the actual output buffer and exported sync_file at the regular
presentation timestamp, with full-range RGB dataspace and fallback
to source dataspace. Source frames retain their handback fence;
unsupported formats, missing features, timing discontinuities and
backpressure fall back to original pictures. A failed fence export
that cannot retire safely drops the affected original rather than
presenting an unsynchronized buffer. The output buffers are fresh
allocations, not pooled across compositor reads. The existing GLES
AImage interop is not selected for this explicitly opted-in ASC path.

These are explicit native player options for validation, not default
app settings. The Android UI/backend policy currently retains its
existing copy-back behavior and does not expose a new resident toggle.
VLC's initial profile gate is deliberately limited to AVC baseline,
main and high (8-bit) and known SDR color metadata; MPV also rejects
unknown decoder bit depth. Real device playback, Vulkan validation
layers, full MPV/VLC builds, quality, cadence and throughput checks
remain required. The source-patch anchors were checked against both
exact pins and CI exercises patch application/idempotence. The
previous revision's native Mesa YUV/RGB resident tests passed;
Android cross-compilation caught a test-only signed/unsigned format
field mismatch, corrected in this revision. New code still needs
current-head CI. Do not equate patch applicability with a compiled
player or completed hardware qualification.


Resident pair reuse and bounded motion cost
-------------------------------------------
The latest experimental resident transport retains up to four fully
initialized Vulkan motion-field/descriptor bundles per session. A
bundle returns to the idle pool only after the actual borrowed
submission fence signals or the caller cancels an unsubmitted,
discarded command buffer. Confirmed device loss prevents reuse.
Every active descriptor set is rewritten before recording the next
pair; unused sets are not bound. Input and output picture ownership
and the existing fence contract do not change. Descriptor writes are
batched into one stack-backed driver call per active set.

The shared resident WGSL kernel evaluates zero displacement once per
block/direction. Its absolute minimum score skips unnecessary motion
candidates; other candidates may stop once their partial raw SAD
plus displacement penalty cannot beat the bound. The normalized SAD
cannot be smaller than the partial raw sum (at most 64 samples);
global bounds retain equal-cost candidates, and local bounds preserve
the existing strict winner ordering. NV12 synthesis uses one UV
bilinear lookup rather than sampling the identical V view twice.
The native Vulkan test now verifies actual handle reuse after fence
retirement and stationary RGB output on a subsequent submission.

The Android app now exposes the explicit experimental hardware-
resident transport selector (off by default), with the MPV/VLC
Surface integrations described above and CPU/HDR/size/feature
fallbacks. The older statements that the application cannot select
this transport describe earlier revisions only. The new path remains
SDR8/limited geometry and a distinct motion policy. Full upstream
motion-search parity, HDR/high-bit-depth processing, additional
decoder formats, compositor-consumer release synchronization for
output-buffer pooling, full player builds and physical-device
validation are still outstanding. Do not infer a measured speedup
from reduced work or from Mesa software Vulkan correctness checks.


Shared source samples and Android command reuse
----------------------------------------------
The resident motion workgroup now loads the invariant 2x2-spaced
source block samples into 64 shared integers once per direction,
instead of sampling the source again for every candidate. Candidate
order, displacement penalty, SAD normalization, and clipped border
handling are unchanged. Integer-coordinate synthesis bypasses
bilinear reads; exact source phases and hard-cut fallback sample
only the contributing picture. An independent scalar oracle
recreates the original unpruned candidate search including its
lane/tree tie order. Mesa CI compares every field entry on stationary,
four-pixel and one-pixel translations, unrelated patterns, flat
pictures and partial edge blocks.

The VLC Android compositor stream now keeps up to three retired
command-pool/command-buffer/fence bundles, reset only after the
actual GPU fence is signaled and the resident pair has retired.
Device-loss/error retirement cannot return a bundle to the cache.
The first output can use the unused preflight allocation. Previously
presented AHardwareBuffers are never reused: the compositor may still
read them after the producer fence signals. A separate consumer
release protocol is needed before recycling those images. An ignored
Android regression exercises two real submissions using the same
command bundle and rejects unsignaled-fence reset. It is compiled by
the Android CI cross-check but requires physical device execution.

Prior head 390ad9cd6683c28cd332aac1592371318f06aefd passed the complete
SVP native workflow 46064619928; the general and SmartTube checks
were canceled by later commits. Fresh exact-head CI is required.
No hardware speed, upstream GPU parity, or HDR support claim.


Explicit SurfaceControl consumer-fence allocation reuse
-------------------------------------------------------
The optional Android stream output lease ABI retains an independent
AHardwareBuffer reference and duplicate producer sync_file. VLC's
picture context holds the lease and a shared dynamic-library reference
until its final destruction, including callbacks after filter close.
The pinned SurfaceControl replacement transaction supplies the actual
previous-buffer release fence. Only an output prepared once with an
explicit consumer FD qualifies; repeated prepares, failed/unconfirmed
presentation, a negative release FD, and missing optional symbols
retain the safe discard/fresh-allocation path.

The stream's bounded pool retains at most eight candidate allocations.
Before reimport, both producer and consumer sync_files must report
successful completion (poll and SYNC_IOC_FILE_INFO), and the previous
Vulkan image must have retired and been destroyed. Reimport creates
fresh VkImage/memory/view/semaphore objects, so this saves the AHB
allocation, not every Vulkan import operation. No compositor callback
accesses the stream or owns Vulkan objects. A closed pool drops late
leases safely and never calls into an unloaded engine. Old ABI
structures remain unchanged. The last visible frame on surface teardown
is deliberately not recycled without explicit release evidence.

Android tests cover dual readiness, rejection of non-sync-file
descriptors, closed-pool late release, actual GPU sync-file export and
AHB reimport (device-only). Full native VLC build and a real
SurfaceControl replacement/presentation/consumer-fence device test
are still required. No measured reuse rate or hardware speed claim.


Extended explicit SDR8 decoder profiles
--------------------------------------
VLC's opt-in PRIVATE Surface now accepts explicit AVC baseline/main/
high, HEVC Main (profile 1) and VP9 profile 0 elementary-stream
profiles. Unknown profiles, HEVC Main 10 and VP9 profiles 1..3 remain
excluded. This does not add AV1 decoding to the pinned VLC MediaCodec
module, whose MIME selection currently does not include AV1.
The profile allowlist is shared with a host C regression and included
in the player source fingerprint. Actual device support and correct
external-format decoding still require physical tests.

The VLC filter validates SDR transfer/primaries and geometry on each
input and breaks the history at color or size changes. MPV's
MediaCodec conversion checks all three component depths, 4:2:0,
explicit supported SDR transfer, primaries, YUV matrix and range
before releasing the opaque decoder picture to the Surface. Unknown
or HDR input takes the existing fallback, rather than silently
converting to RGBA8. MPV's decoder selection remains codec-agnostic.
The previous CI head 8c4a7c25 passed source patch application and
CPU fallback before cancellation by newer commits. Fresh native
and full player validation are required. No 10-bit/HDR claim.


Opt-in AV1 Main8 VLC MediaCodec transport
----------------------------------------
The pinned VLC MediaCodec module now also recognizes video/av01,
only when explicit experimental SVP Surface and direct rendering
are requested. Require an av1C version 1 record with Main profile,
8-bit non-monochrome 4:2:0, one bounded sequence OBU, and known
SDR container metadata. VLC's existing AV1 syntax parser is linked
into the module and independently confirms profile, I420 chroma
and compatible sequence color metadata. Feed the configuration
OBUs, not the four-byte MP4 record header, as MediaCodec CSD.
In-band sequence headers must match the validated configuration;
malformed framing or a changed header triggers existing decoder
fallback. Raw/missing configuration, Main10, HDR and other profiles
remain excluded. The earlier statement that the MIME switch lacks
AV1 applies only to the prior revision. Host C tests exercise CSD
framing, truncation, invalid profile flags and sequence changes.
A full VLC Android build, actual AV1 MediaCodec/SurfaceControl
playback and exact-head CI remain unverified. No HDR/10-bit path or
measured hardware throughput is claimed.


Native high-depth Vulkan transport (experimental)
------------------------------------------------
Optional ambient_svp_vk_create_format sessions support P010 with
MSB-aligned ten-bit codes in R16/RG16 UNORM and I420 10-bit with
LSB-aligned codes in R16 UNORM. Both retain all 1024 luma levels
during motion search; the residual reported to the synthesis cut
threshold is scaled back to approximate eight-bit SAD units.
Synthesis converts limited/full-range BT.601, BT.709 or BT.2020 NCL
to encoded RGBA16F without tone mapping or clipping. Input and output
remain GPU resident. The old SDR8 entry point and ABI remain intact.
The native Vulkan test now submits both actual high-depth layouts and
reads FP16 output in the test only, verifying adjacent 10-bit source
codes remain distinct.

MPV direct Vulkan frames can select the optional high-depth session
when their actual format and separate R16 plane views match. It
creates a RGBAF16 AVVkFrame output pool and preserves the output
format's bit representation while copying transfer, primaries and
compatible static HDR metadata. PQ/HLG require BT.2020 NCL and matching
HDR metadata across the pair; HDR10+ and Dolby Vision side data are
rejected rather than copied blindly. This does not yet enable HDR in
the Android app's resident selection, or change the MediaCodec
PRIVATE/AHB conversion or VLC SurfaceControl RGBA8 output. Multiplane
high-depth Vulkan images are conservatively rejected. No end-to-end
HDR validation, HDR10+ interpolation, Dolby Vision composition or
hardware speed claim. Fresh CI and full MPV build required.


Explicit Android P010 -> FP16 import (experimental)
---------------------------------------------------
The optional PRIVATE reader accepts explicitly identified P010
leases without changing the existing SDR8 reader ABI. The importer
now builds separate RGBA8 and RGBA16F immutable-sampler shaders.
High-depth submit_into_format accepts only P010 confirmed by both
AHardwareBuffer and Vulkan format properties, with matching suggested
BT.2020 NCL sampler model and limited/full range. Vendor-private
unproven layouts and mismatched metadata are rejected before submit.
Known P010 is also rejected by the old RGBA8 converter. The original
producer/consumer fences and direct player image submission remain
unchanged. No CPU pixel mapping or readback. Driver YCbCr conversion
precision and range behavior remain device dependent.

The MPV MediaCodec adapter negotiates optional symbols, checks actual
10-bit 4:2:0 and PQ/HLG BT.2020 metadata, and creates a RGBAF16 AVVkFrame
pool for compatible P010 sources. Dynamic HDR10+/Dolby side data is
rejected. Native resident mode 3 accepts those encoded FP16 RGB views,
avoids repeating YUV conversion, and uses a ten-bit luma proxy for
motion. The native GPU precision fixture covers this format, and
Android cross-compilation includes strict source-format rejection
tests and both SPIR-V variants. No app HDR selection change or VLC
high-depth compositor output yet. Actual ten-bit PRIVATE decoder
buffers, driver conversion fidelity and end-to-end HDR playback
require physical tests. Prior c28f022d CI was superseded; fresh CI
must complete. No measured performance or full HDR claim.


VLC high-depth SurfaceControl and MPV import completion
--------------------------------------------------------
The optional Android stream format 4/6 selects fixed BT.2020
limited/full P010 input and RGBA16F compositor output. It imports the
retained decoder AHB into a GPU-local FP16 image and uses resident
mode 3 to synthesize directly into compositor AHBs with format
R16G16B16A16_FLOAT. Allocation, storage image features and external
memory importability are preflighted before accepting source frames.
The existing bounded pool remains per stream, so eight-bit and FP16
allocations cannot mix. Existing producer and consumer sync_file
retirement and source ownership rules are unchanged.

The VLC decoder enables the experimental high-depth PRIVATE reader
for explicit HEVC Main10 BT.2020 PQ/HLG and known range (no declared
Dolby Vision). The pre-clock filter validates matching color
metadata per picture and resets history at format/metadata changes.
SurfaceControl receives full-range RGB BT.2020 PQ/HLG for synthesized
pictures; original source pictures retain their range. SMPTE ST 2086
and CTA-861.3 static metadata are set or cleared per transaction.
HDR10+ dynamic metadata is not implemented or independently detected
in the VLC opaque path; do not treat this as full HDR10+ support.

MPV's optional P010 reader/importer symbols are negotiated together;
failure retains the old SDR reader. Explicit standard-format P010
import is also supported when the driver supplies no external token.
Unknown/private layouts without affirmative P010 proof still fall
back rather than risk truncation or incorrect color conversion.
Android FP16 compositor allocation/preflight device fixture added.
The previous de4b909e CI passed source patch, CPU and portable
Vulkan checks before supersession. Exact-head full player builds,
device HDR playback, real compositor metadata/fence validation and
app HDR selection remain pending; no claim of hardware validation.


App opt-in and metadata-aware HDR resident transport
---------------------------------------------------
The existing hardware-resident SVP preference (default off) now allows
HDR10/HLG when MPV has an actual Vulkan output plan, or VLC has a
native HDR SurfaceControl plan with a verified compute device. The
existing native HDR presentation route is not overridden merely to
enable SVP. Incompatible dimensions, CPU filters, old libraries and
rejected hardware fall back; HDR never requests the SDR CPU filter.
The MPV filter-chain planner no longer mistakes a resident SVP filter
for a CPU filter requiring decoder copy-back. Dynamic HDR is selected
only with MPV's native Vulkan decoder, not opaque MediaCodec.

HDR10+: retain the original FFmpeg side data and libplacebo mapping
for compatible identical single-window metadata, excluding peak
luminance grids. A change/missing or unsupported payload (including
spatial windows) repeats the previous unmodified source at output
ticks rather than blending across incompatible metadata. This is
conservative scene-metadata reuse, not new normative ST 2094-40
metadata generation or spatial HDR10+ interpolation.

Dolby Vision: modes 4/5 synthesize raw P010/I420 10-bit YUV on the
existing Vulkan device, quantizing back to the original bit packing
without RGB conversion, then retain MPV's mapped Dolby representation
and RPU side data for libplacebo's normal reshaping. Require enabled
shaderStorageImageExtendedFormats, actual separate storage planes,
matching chroma siting, bounded valid FFmpeg metadata offsets and
no required residual/EL reconstruction. Exact identical metadata and
mapping are required; on changed metadata repeat original frames.
This is compatible single-layer processing, not Profile 7 FEL
composition or native Dolby Vision passthrough. Unsupported inputs
retain the original renderer and metadata.

VLC's static HDR SurfaceControl output cannot carry per-frame HDR10+
or Dolby RPU metadata. Known dynamic formats are kept out of this
route. The HEVC access-unit parser also latches in-band HDR10+ SEI,
RPU/EL or malformed NAL/SEI framing; the filter rejects ancillary
HDR10+/Dolby metadata and stops interpolation, allowing the app's
resident-output watchdog to restore the normal playback path. The
separate experimental metadata renderer and FEL composition in
PR #510 are not yet integrated with this SVP output path.

Added actual raw Vulkan endpoint/midpoint plane-code tests, FFmpeg
HDR10+/Dolby metadata contract fixtures, HEVC dynamic guards and
Android routing tests. Earlier 8b9a953e native CI passed source pin,
patch application and CPU checks before supersession. Exact-head
CI, full player builds, real HDR rendering and device performance
remain unverified. No full HDR10+/Dolby Vision standard-conformance
or hardware validation claim.


Dynamic HDR synthesis and PR510 renderer bridge
------------------------------------------------
MPV generates phase-specific HDR10+ FFmpeg side data for matching
one-to-three-window topology, ellipse geometry, percentile indices,
tone-curve anchors, saturation and peak luminance grids. The values
are bounded temporal estimates, not measured synthetic-image
statistics. The current MPV libplacebo mapping remains global (window
zero); full local-window metadata is retained in the side data.
Unsupported transitions preserve the original source pictures.

The isolated experimental VLC build can stage the new SVP source-pair
scheduler and display bridge on top of PR510's renderer. Original
picture timestamps and matching FEL ancillary data are retained until
the display reconstructs each source. GPU-resident FP16 copies of
the reconstructed images then undergo bidirectional motion estimation
and synthesis; the resulting HDR10+ spatial metadata is passed to the
existing renderer. FEL residual composition precedes interpolation,
never reapplied to interpolated RGB. Existing PR510 EL upload is not
a fully zero-copy decoder path. The ordinary SurfaceControl build
does not use this bridge. Combined app selection, the combined
PR510 build, and physical Profile 7 FEL/HDR playback are not yet
validated or completed. All fourteen staging anchors were checked
against the pinned VLC source plus PR510 display patch.

Native resident motion now performs GPU red-black adaptive neighbor
refinement with explicit compute barriers. The experimental
hierarchical analyser additionally schedules causal x+2*y wavefronts
with left/top/top-right predictors, bounded adaptive search radii and
GPU cost/selection. It still reads winners between waves. This is
not full upstream Open-SVPFlow orchestration parity: weighted
multilevel predictors, all search modes/penalties and refinement
semantics require further work and reference comparisons. No
hardware speedup or formal HDR/FEL conformance claim.


Measured HDR10+ statistics, MPV FEL reconstruction and causal refinement
-------------------------------------------------------------------------
Native Vulkan phases can optionally record PQ-to-linear statistics on
their actual RGBA16F output. Per-window GPU histograms have 100001
quantized MaxRGB bins, component maxima, mean, selected percentile
ranks and brightest-bin counts. A GPU reduction publishes only compact
statistics; no pixel or histogram readback. The pair owns allocations
and descriptors until its exact submission fence retires. MPV uses the
measured results for matching one-to-three-window HDR10+ intermediate
frames, with a bounded 250 ms fence wait after releasing FFmpeg locks.
Missing or unsupported metadata/statistics retains the original
source pictures. Statistics use rectangular processing windows;
curve parameters, saturation, geometry and display grids remain
temporally estimated, not freshly authored curves or motion-tracked
ellipses. Global MPV projection still maps window zero. The separate
VLC HDR renderer has not yet been connected to this measured path.

MPV's pinned gpu-next renderer now accepts an opt-in private
reference-counted source-pair attachment. The filter schedules exact
BL/EL PTS-matched inputs. gpu-next uses its existing source mappers and
libplacebo FEL composition to reconstruct each source into a PQ BT2020
FP16 texture, checks renderer sampling/FBO errors, caches adjacent
sources and motion fields, and synthesizes into an independent texture
owned by the queued output frame. The final display renderer receives
RGB without an attached EL or RPU. The original source remains the
fallback. Software EL upload remains possible; only supported geometry
and original timestamps are accepted. This code requires the pinned
Dolby-enabled Vulkan libplacebo and full MPV build/device validation.

The hierarchical search now uses four overlap-weighted coarse
predictors rather than nearest-neighbour scaling. Native Vulkan adds
compact x+2*y causal diagonals with explicit compute barriers and
device-local left/top/top-right median predictors. Bounded uneven
multi-hexagon cross/HEX4, iterative HEX2 and final exhaustive
neighbourhood refinement retain the best candidate. This is not a
bit-exact port of the upstream search: chroma/SATD, predictor penalties,
adaptive per-stage search, subpixel and border/recalculation semantics
and the hierarchical host winner readbacks still require further work.
No measured speedup or full SVP parity is claimed.

Added native shader compilation, three-window actual Mesa Vulkan
statistics fixture and overlap-weighted predictor regressions.
All eleven MPV FEL staging anchors checked against the pinned source.
Earlier e6c7f2d passed patch, CPU and portable Vulkan checks before
supersession; new head CI, linked player builds, real FEL playback and
reference measurements remain pending.


Configured resident quality and spatial HDR10+ in MPV
------------------------------------------------------
An optional native Vulkan create_config ABI accepts 8/16/32-pixel
blocks, supported even pixel overlaps, pel 1/2/4, local search
radius 1..4, SATD/chroma flags, bounded lambda/pnew, scene percentage
and artifact policy. The MPV filter derives these from its existing
options. Legacy creation functions remain unchanged. The configured
path seeds full-block luma/chroma SAD and 4x4 SATD searches from the
coarse causal GPU fields, evaluates fractional candidates using
integer bilinear weights, and records the actual pel units.
A separate GPU scene reduction gates synthesis. RGB8/FP16 and raw
10-bit YUV synthesis blend independently warped overlapping blocks
using raised-cosine weights. The configured grid is bounded to 32768
blocks; unsupported configurations fall back rather than silently
creating an incompatible session. VLC/MPV FEL renderer-side
independent search and the experimental hierarchical wgpu analyser
are not yet migrated to this configured quality path.
This is a substantial independent implementation, not bit-exact
Open-SVPFlow parity. Exact upstream superframe filtering, predictor
and stage penalties, search ordering/adaptive modes, all synthesis
algorithms and border/recalculation behavior still differ. The
hierarchical path still reads winners between waves.

MPV measured HDR10+ now derives a monotone nine-anchor shoulder from
actual peak/mean/upper-percentile statistics and authored target
peak, with no compression when the scene fits. This is a new
content-dependent renderer policy, not normative metadata authoring.
The optional tracking ABI uses reciprocal motion support and adjusts
each local window rectangle and ellipse center by at most 16 pixels
before GPU histogram collection; original shape/angle remain authored.
Only metadata/geometry is read back, never pixels or full vectors.
A private measured-frame marker activates the PR510 spatial
libplacebo mapper on MPV gpu-next; per-queue-frame GPU output
textures and target-color cache keys prevent overwriting queued
frames. SDR targets and explicitly characterized PQ/HLG BT2020
targets are supported; uncharacterized HDR, invalid metadata, LUT/
ICC conflicts and unsupported source geometry retain global mapping.
The PR510 headers are staged in a separate directory until the
two PRs are combined. Original non-SVP frames are unchanged.

Added portable shader compilation for all six configured formats,
actual Mesa configured quarter-pel/overlap and tracked HDR fixtures,
and content-dependent curve assertions. Corrected the previous
half-overlap predictor regression expectation using the upstream
weight formula. Earlier CI passed source staging/idempotence and
CPU tests; exact-head native GPU execution, full MPV/VLC builds,
physical hardware quality/performance and normative conformance
remain pending.


Upstream subpixel phases and weighted resident search
---------------------------------------------------
The optional create_weighted ABI preserves the existing tuning
structure and accepts all five relative predictor/new penalties,
lambda, lsad, Q16 plevel, stage/level count, optional SAD-gated SATD
and bilinear/Catmull-Rom/Wiener upscale selection. MPV chooses the
pinned default GPU=1 bilinear, fast=0 gate and block/level-scaled
lambda/lsad/default penalties. The configured resident kernel
implements the upstream rounded half phases, including the unusual
bilinear diagonal and first row, and builds quarter phases by
averaging rounded half phases in the same order. Four-tap Catmull-
Rom and six-tap Wiener use the upstream signed integer coefficients,
rounding and clipping. The filter parity contract is interior
8-bit samples; the 10-bit modes extend arithmetic to their code
range. Visible image borders are clamped for safety, not treated
as the original packed canvas's cross-row/phase SIMD spill bytes.
The new filters are used in motion evaluation; the separate warped
pixel synthesis still uses its existing continuous sampling.

Quality search now snapshots the coarse device fields, computes
per-direction global mode and consensus on the GPU, then processes
causal full-quality waves. Reciprocal/global/zero/top-left/top/
top-right/left predictors use separate signed relative penalties.
Candidate SAD/SATD and chroma costs are parallel, while ordered
selection preserves the current winner on ties and updates the
optional raw SAD gate. Level/pel scaling and per-block LSAD
adaptation use reference formulas. A bounded integer-mantissa
implementation emulates the reference f32 ratio and f64 rounding/
truncation without requiring shaderFloat64 or shaderInt64.
Extreme squared-motion penalties saturate rather than wrapping
into false winning costs. All data remains device-resident.
These are the reference weighting terms, not full algorithm parity:
the resident global/reciprocal seed derivation, coarse stage,
border/recalc processing, try-many orchestration and synthesis
remain independent implementations.

Added SPIR-V compile checks for each filter and snapshot pipeline,
a Mesa GPU comparison against pinned SuperBuilder half/quarter
phase planes for interior pixels, and 4096 adaptive-lambda/signed
penalty reference comparisons. Fixed the ST2084 c3 coefficient
(18.6875, not 19.6875) identified by the previous GPU HDR
statistics regression. Exact-head CI and device tests pending.


Reference synthesis family and GPU-resident masks
-------------------------------------------------
The optional native synthesis selector supports algorithms
1, 2, 11, 13, 21, 22 and 23. Configured MPV sessions honor the
existing algorithm setting and use the upstream 1/256 phase
quantization, biased integer motion interpolation, UNORM8 mask
filtering, reference directional warp, phase-dependent SAD blend
and fixed 8-bit source-filter weights. SDR YUV luma is linearized
before synthesis and re-encoded afterward; native FP16 HDR and raw
10-bit YUV retain their existing transfer/representation. Endpoint
pictures retain the direct source path.

Coverage uses the upstream signed truncation, floor division,
four-cell area scatter and 3x3 sum/8 coverage transform entirely
in device-local storage. Selected total luma/chroma costs are
retained for directional SAD masks with app artifact policies
0/50/100. Per-phase mask scratch is protected by explicit compute
barriers. Both raw YUV and encoded RGB resident outputs use the
same synthesis family.

Algorithm 23 uses a real four-picture MPV lookahead queue and
optional begin_extended ABI. Two auxiliary adjacent analyses are
recorded in the same submission, their directional fields and
scene flags copied to the center pair. All image refs and GPU
resources survive its exact fence; separate bounded auxiliary
budget permits up to four center jobs and eight adjacent analyses.
Invalid/missing adjacent fields or neighbor scene cuts use 21.
EOF and disabled/failure paths drain buffered originals without
discarding the lookahead picture. Adjacent fields are recomputed
for each center pair; no measured performance claim. The separate
VLC/FEL renderer-side shaders have not migrated to this family.

Added direct Mesa comparisons with pinned VectorField coverage
and SAD masks across block/pel/overlap/phase policies, isolated
all-seven synthesis equation probes across 257 phases, configured
SPIR-V compilation, and actual four-picture native execution
with fence lifetime assertions. These fixtures have not yet
completed on the new head. The reference equations and mask
construction are implemented, but end-to-end bit-exact parity
still depends on matching upstream field generation/geometry,
border and chroma reconstruction, scene classification, exact
source sampling and complete player/device validation.


Shared quality core in VLC and reconstructed FEL renderers
---------------------------------------------------------
The optional create_quality ABI resolves existing player preset,
block, overlap, pel, search, artifact, scene and algorithm
settings centrally. Direct MPV Vulkan and Android SurfaceControl
sessions now use that same weighted motion/synthesis setup.
Legacy create/create_format and explicit tuning APIs remain
available.

Both independent libplacebo motion/synthesis implementations
(MPV FEL and VLC metadata-aware HDR/FEL) have been removed.
The existing complete BL/EL and Dolby reshaping/reconstruction
runs first, producing canonical FP16 RGB. A shared bridge unwraps
actual RGBA16F images, obtains libplacebo holds with binary
semaphores, calls the common native Vulkan kernel on the same
device, and releases the images with a timeline semaphore value.
No CPU pixel copies. Only a common unprotected graphics/compute
queue family is accepted. A native fence is waited synchronously
before releasing the images; this is a correctness-first bridge
and does not yet cache analysis across renderer phases or pipeline
the submissions asynchronously. Unsupported devices/formats
retain the original reconstructed source or normal FEL mapping.
The generated RGB frame never reapplies RPU/EL metadata.

For algorithm 23, MPV FEL and the experimental VLC HDR scheduler
retain actual past/current/next/future source references and
monotonic IDs. Their four-texture reconstruction caches reuse
matching source IDs and pass real adjacent pictures to the common
extended analysis; unavailable neighbors downgrade to algorithm 21.
The SurfaceControl path likewise primes two input jobs, queues
one future picture, retains the four imported source conversions
through the output fence, and uses an independently bounded
eight-job import budget only for extended sessions. At EOF or
transport failure the remaining delayed originals are preserved
in timestamp order; the last incomplete pair is not interpolated
in these three lookahead schedulers. HDR/FEL metadata rules and
output lease/fence handling are retained.

Build staging and fingerprints include the common quality ABI
and libplacebo bridge. Added preset resolution regression tests,
a Mesa binary-hold/timeline-release held-image execution fixture,
a pinned libplacebo public-header syntax check and a configured
Android physical-device preflight test. Full linked MPV/VLC builds,
actual Android/FEL playback, synchronization validation on physical
drivers, performance and exact-head CI remain pending. The shared
RGB search derives luma/chroma from reconstructed RGB, not the
original YUV planes; bit-exact reference equivalence is not claimed.


HDR10+ explicit two-pass player integration
-------------------------------------------
The optional native scene-pass API now has capture/replay entry
points in the MPV Vulkan filter and the Android VLC HDR scheduler/
libplacebo display. Default playback is unchanged (hdr-pass=0).
This is an explicit, externally controlled two-pass workflow, NOT
an automatic content scanner or an app-level restart controller.

An authoring/controller process must provide:
  - a trusted 64-hex SHA256 identity for the exact media/video track;
  - a private scene plan and a new private cache path;
  - authoritative scene cuts mapped to the FINAL output tick grid;
  - the same stream, source/target frame rate, quality settings,
    renderer, device, decoder and shader build for both passes.

Scene plan (UTF-8, no comments, each cut is an exclusive output
frame index, first scene starts at index 0):
    AMBIENTSVP_SCENE_PLAN_1
    <64 hexadecimal characters of the trusted source identity>
    120
    240
    480

In this example 480 is the expected output-frame count, not the
source-frame count. The caller must compute real cut positions and
the real frame count; these numbers are illustrative only.

MPV filter parameters:
    hwresident=1:hdr-pass=1:hdr-plan=<private plan path>:hdr-cache=<new cache path>:hdr-key=<SHA256>
Repeat from the start with hdr-pass=2 and the same paths and
configuration. Use MPV's documented filter-option escaping for
paths with separators. The first pass finalizes at drained EOF.

VLC filter parameters (the existing HDR/AHB transport must be enabled):
    ambientsvp-hdr-pass=1
    ambientsvp-hdr-plan=<private plan path>
    ambientsvp-hdr-cache=<new cache path>
    ambientsvp-hdr-key=<SHA256>
The Android scheduler carries the plan identity and output index
in each picture ancillary; the display owns the native session
and measures the actual output texture. The final scheduled picture
publishes the cache only after successful capture of every tick.
Repeat with ambientsvp-hdr-pass=2. A skipped/dropped render tick,
seek, flush, incompatible metadata or incomplete final interval
invalidates the pass. The renderer then preserves the source picture
instead of attaching a partial scene result.

The version-1 single-global-window path uses a shared nine-bin
percentile specification, scene histogram, brightest-frame reduction
and FBP. At a supplied scene cut, both passes hold the preceding
source until the next source tick, rather than mixing two scenes.
An interval that straddles a planned cut is rejected. Phase-zero
source pictures are included in capture; VLC measures a GPU copy of
the source texture. Tone curves are renderer policy, not normative
authoring output. Version 0, multi-window metadata, SDR, HLG and
Dolby Vision are outside this two-pass contract.

The cache contains one quantized scene result per planned interval.
It is identity-, profile- and geometry-bound, bounded in size,
validated before replay and published with a create-only atomic
hard link (use app-private storage supporting hard links). No partial
or overwritten cache is exposed. The identity is provided by the
trusted controller; neither player independently hashes the media.
Different renderer/driver configurations intentionally require
different caches. Do not treat the cache as an authenticated file
or accept it from untrusted sources.

Native tests cover incomplete sessions, output order, cache identity,
scene intervals and replay. MPV C fixtures test metadata application.
Full linked MPV/VLC builds, physical-device runs, exact-head CI,
automatic scene-plan creation/app orchestration, and independent
ST 2094-40 normative conformance are separate validation work.


Ambient TV automatic scene-pass controller
-----------------------------------------
The SVP panel now exposes a default-off experimental HDR10+ two-pass
choice under the resident transport. The app scans readable local
HEVC files on an IO dispatcher, hashes the complete source and
extracts registered version-1 HDR10+ SEI metadata in presentation
order. It maps stable metadata runs to the exact player output
cadence. Missing, ambiguous, per-frame-varying, discontinuous,
encrypted and multi-video-track inputs are rejected. This is a
conservative metadata-derived scene estimate, not authoritative
editorial scene-cut detection or ST 2094 certification.

MPV stops the old decoder, installs the capture filter and reloads
from the start. VLC recreates its filter/renderer instance with
scene options. The first run is visible normal-speed playback;
after a successful native cache publication, EOF triggers one
automatic replay from the beginning with measured scene metadata.
Seeking, changing tracks/settings, surface loss or incomplete
capture cancels the experimental workflow. Private session files
are removed on abort or final completion. Ordinary playback remains
the default; no remote HTTP/NAS content hashing or hidden headless
renderer is attempted.

VLC requires the extended AHB/libplacebo renderer from PR #510,
the extended AAR asset marker, Android API 34+ and an HDR10-capable
display with usable luminance capabilities. Its scene route must
not select the competing Android SurfaceControl SVP transport.
Normal builds lacking the extended marker retain normal playback.
Full linked player/device and CI validation remains necessary.


Extended automatic HDR10+ analysis
---------------------------------
The experimental app controller now attempts a separate, unpaced
MediaCodec content scan with a bounded 16x9 luma signature and flash
suppression, combining detected cuts with stable metadata transitions.
Every decoded PTS must correspond to an extracted source sample.
This is content-based scene estimation, not authoritative edit data.

Local files, readable content URIs, and finite HTTP(S) VOD including
the existing NAS file bridge are supported by the scanner. Network
and SAF sources are staged as a private immutable snapshot with a
known length, a 64 GiB limit and a 512 MiB free-space reserve. Both
passes use the same bytes. Redirects, unknown lengths, adaptive/live
manifests, incomplete transfers and insufficient storage fail closed.

The app attempts a separate drained ImageReader output with MPV's
untimed, no-audio Vulkan renderer, or a VLC AHB/libplacebo player
at requested 4x speed with frame dropping disabled. No offscreen
run is accepted without the native complete cache. Unsupported
devices fall back to the original visible first-pass workflow.
The main player may have started while the initial source scan
runs; capture itself uses the private offscreen surface.

UHD HDR geometry up to 3840x2160 is gated by the explicit 4K
two-pass selection, Vulkan descriptor/image limits and a bounded
motion-grid budget. Automatic quality presets choose 32-pixel
blocks at UHD; incompatible explicit block/overlap choices are
rejected rather than silently altered. The 5:1 proxy stays on GPU.
Scene average MaxRGB is now accumulated at 1e-9 fixed-point
precision before quantization to the metadata unit, using
portable two-word GPU sums and u128 scene accumulation. Both
adapters reject invalid scene values and reserved markers.

Scope: version 1, one global window, recommended nine percentiles;
device/linked build/CI, exact numerical conformance against
independent reference vectors and formal standard certification
are still required. Estimated scene boundaries, finite precision
PQ/FP16 shader inputs, and unsupported version-0/multi-window
two-pass authoring are not claimed to be fully normative.


Versioned HDR10+ two-pass scene statistics
-----------------------------------------
ST 2094-40:2020 sections 6, 8, 9 and 10 distinguish application
versions 0 and 1. The Android scanner now recognizes both, refuses
mixed-version sessions, and validates the corresponding cache type.
The native session retains a separate result schema for each scene.

Version 0 accepts one to three processing windows and up to fifteen
actual distribution entries per window. It measures the selected
rectangle/outer-ellipse intersection for every output frame, aggregates
independent per-window histograms and linear sums, and writes newly
generated FractionBrightPixels as zero. The current two-pass policy
holds the authored geometry of the preceding source picture for the
output phases; both passes use the same geometry. It does not apply
the single-pass motion-affine window deformation in legacy two-pass
mode. Optional authored peak grids and saturation are retained.
Window topology and percentile indices must remain stable within
each declared scene. An inconsistent scene invalidates the run.
The cache stores version-zero window count, indices and values; it
cannot be loaded as a version-one cache.

Version 1 accepts any ascending nine-entry distribution vector, not
just the recommended table. The special V1/V2 marker values are
applied only when their matching indices are 5/10. The final index
99 uses 99.98%, and v0 uses 99.98% at every index 99. Canonical
v1 cache rows remain readable; noncanonical rows append their exact
schema, verified in both player adapters before application.

The native PQ statistic path uses a shared high-precision CPU lookup
for every representable nonnegative FP16 code through 1.0, followed
by GPU integer histogram quantization, selected-pixel 1e-9 sums,
rational area weighting on the 5:1 proxy grid, and portable two-word
integer bright-band accumulation. Local legacy ellipse scanlines
are computed in f64 with exact integer cardinal-angle boundaries.
The GPU result is still a measurement of FP16 reconstructed frames,
not a promise to recover pre-decoder mastering precision.

Independent Python Decimal/Fraction fixtures cover PQ, distribution,
ellipse membership, proxy resize, scene tie selection, FBP weights
and the portable wide-integer arithmetic. Native Vulkan tests compare
actual FP16 output samples against the independent PQ vectors and
exercise legacy selected pixel counts, and CPU fixtures test complete
multi-window cache capture/replay and v1 schema rejection. CI runs
the reference script independently before Rust and Vulkan tests.

This implements a versioned measurement/replay contract, not formal
certification. Full linked MPV/VLC builds, physical devices, varied
commercial and synthetic HDR10+ streams, arbitrary-angle boundary
tolerances, nonuniform real GPU proxy fixtures and authoritative
scene boundaries remain necessary for a complete conformance claim.
The generated tone-mapping curve and display adaptation are renderer
policy within the standard's allowed metadata structure.


Motion-affine legacy two-pass replay
------------------------------------
Version-zero capture and replay now interpolate compatible source
windows at synthesized phases and use the resident reciprocal motion
field to update translation, rectangle, ellipse axes and orientation.
Source ticks and authoritative scene holds keep authored geometry.
Both passes measure the same quantized selector. Legacy cache records
include a bounded per-output geometry fingerprint (rectangle, center,
axes, angle, percentile schema and selected pixel count); a mismatch
aborts before cache lookup. This is a consistency check, not a
cryptographic content authentication mechanism. The revised cache
requires these records and is limited to 1,000,000 output frames
and 32 MB; obsolete version-zero caches must be regenerated.
At replay, the measured geometry is applied separately from scene
statistics. Dynamic cardinal-angle membership uses integer products;
arbitrary angles share a floating-point selector. The scene-sum
words no longer overlap the local affine-gradient words.
Nonuniform 66x34 two-frame RGB fixtures compare actual Vulkan
MaxSCL, percentiles, mean, brightest frame and FBP with a separate
Decimal/Fraction oracle, including a partial proxy cell and fractional
bright-band membership. These are synthetic conformance checks, not
validation on a commercial bitstream or physical display.


Deterministic arbitrary-angle selector and external corpus
--------------------------------------------------------
The authored and motion-quantized ellipse angles use a common Q30
sine/cosine table. CPU i128 and GPU portable four-word integer
arithmetic evaluate the same ellipse quadratic. A convex discrete
search finds exact inclusive-exclusive scanline spans once per
output window after motion tracking; both the histogram and the
linear MaxRGB reducer consume these spans. Orthogonal boundaries
are exact; other angles are exact relative to the Q30 coefficients.
An independent 70-digit decimal oracle checks coefficient error
bounds, 8,000 sampled positions across eight non-cardinal angles,
and the actual GPU fixture counts at 45 and 73 degrees. Per-frame
average MaxRGB now uses the same pre-quantization sum as the
scene path rather than averaging rounded histogram bins.

The native CI includes a real FFmpeg FATE Annex-B HEVC sample:
https://fate-suite.ffmpeg.org/hevc/hdr10_plus_h265_sample.hevc
It compares the production C payload parser against FFmpeg's
tests/ref/fate/hevc-hdr10-plus-metadata numeric reference, including
an authored nonzero reserved version-one entry. Successful syntax
parsing is deliberately distinguished from strict current authoring
constraints. The sample is fetched at test time, not redistributed.
For manually acquired files, convert an appropriate video track
to Annex B without reencoding, for example:
    ffmpeg -i input.ts -map 0:v:0 -c copy -bsf:v hevc_mp4toannexb -f hevc input.hevc
    python3 native/ambientsvp/tests/hdr10plus_bitstream_reference.py input.hevc
The probe prints SHA256, accepted payload count, versions and strict
authoring count. This is parser validation only, not a decoded-image,
display or full two-pass player test. Additional 2160p50 Samsung
HDR10+ test sequences are documented by DVB at
https://dvb.org/specifications/verification-validation/hdr-test-content/
(EBU content under CC BY 4.0; see the original attribution).
Those downloads require the DVB request procedure. No commercial
stream or physical device has been certified by these tests.
