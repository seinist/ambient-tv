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
