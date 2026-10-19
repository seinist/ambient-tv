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
