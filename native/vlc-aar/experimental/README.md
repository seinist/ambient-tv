# Experimental MediaCodec → Vulkan import

**Opt-in test build only.** The production AAR and the production Vulkan
copy-back path are unchanged. The experiment is restricted to opaque
AImageReader BT.709 SDR or explicit BT.2020 HDR10/HLG on Android API 34+
with a matching driver-reported matrix, range and actual AImage dataspace,
one graphics queue, and no CPU picture filters. HDR10+ with validated per-frame dynamic metadata is experimental;
Dolby Vision, invalid HDR10+ payloads, non-YUV explicit VkFormat, protected images and unsupported extensions/features
remain on the existing copy-back or native HDR routes.

## Build and validate

```sh
VLC_EXPERIMENTAL_AHB=1 VLC_VULKAN_ABIS=arm64-v8a bash scripts/build_vlc_vulkan.sh
./gradlew :app:assembleDebug -PVLC_AHB_EXPERIMENT=1 -PAPK_ABI=arm64
```

The first command stages the pinned VLC patches in a separate marked checkout
and produces `native/vlc-aar/build/experimental/libvlc-vulkan-ahb-experimental.aar`.
It never replaces app/libs. The second explicitly selects that AAR; normal
Gradle builds never auto-select it. Select Vulkan video output for the test.
The app then opts into `--vk-ambient-ahb` only for eligible playback. Do not
distribute the experiment as a validated production build.

## Native path

- Pinned VLC display.c Open/PictureRender/Close and Vulkan instance patches,
  built with a C++ display owner on libplacebo's actual logical device.
- Opaque or known multi-planar YUV (including P010) AHB dedicated external-format
  import, driver YCbCr conversion, foreign queue acquire
  and release, producer sync-fd import, conservative VLC picture lease.
  AHardwareBuffer references do not substitute for the decoder picture lease.
  The decoder requests GPU_SAMPLED_IMAGE even for unrotated output. The
  experimental GPU-only AImageReader accessor does not require SurfaceControl
  or Android 31's AHardwareBuffer_getId; ordinary VLC callers retain their
  original API contract. Stable IDs prevent aliasing across in-flight slots.
  On API 29/30 or failed ID lookup, at most one slot may be in flight, avoiding
  aliasing between distinct AHB wrappers. Actual older-device support still
  depends on Vulkan features and must be tested on hardware. Direct Android
  display and GLES explicitly reject the GPU-only reader rather than
  dereference a missing SurfaceControl/ID function. All discarded
  pictures retain their original producer fence in the VLC-owned context;
  its destructor transfers it to AImage_deleteAsync when no consumer fence exists.
- Three bounded reusable slots, command buffers, device-local RGB targets,
  immutable sampler, descriptors and pipeline. Exact buffer identity reuses
  the import. Distinct buffers with identical driver format/conversion and
  output geometry reuse the actual sampler and pipeline, replacing only the
  imported image/view and descriptor binding. Other formats rebuild the pass.
  A shared VkPipelineCache also permits driver compilation reuse across slots.
  The actual physical device is checked for sync-fd import support. Descriptor
  pool capacity uses maintenance6's maximum when available, otherwise a
  bounded conservative device-limit-based budget.
- GPU-only conversion and asynchronous libplacebo semaphore handoff. Decoder
  picture completion and consumer hold/reclaim completion are distinct;
  no per-frame CPU fence wait or device-wide idle. Per-slot binary semaphores
  and fences are reused only after both conversion and consumer wait completion;
  the real same-device handoff avoids unnecessary exportable ready semaphores.
  On terminal device loss
  the owner destroys its resources after the queue reports loss and discards
  rather than claiming successful conversion; original producer fence ownership
  is preserved. Unexpected retirement errors remain quarantined.
- Actual presentation accounting: rejected opaque frames count as lost,
  not displayed, in the experimental VLC core. The app's continuous
  presentation watchdog can retry Vulkan copy-back once on failure/stall
  before the existing Android/OpenGL fallback. It preserves the existing
  asynchronous restart and position/track restore path.
- Experimental-only MediaCodec input and AImageReader callback/flush timeouts;
  output polling observes abort/flush instead of waiting indefinitely. Late
  callbacks after abort are discarded. The watchdog now also handles static
  or missing counters and stalled opening/buffering/seek states (bounded
  deadlines reset on progress, pause or surface loss).
- HDR10/PQ and HLG use a 16-bit floating-point RGB intermediate. The source
  primaries, transfer and mastering/light metadata remain attached for
  libplacebo tone mapping. Actual AImage dataspace must agree; the API is
  dynamically resolved, so old Android devices retain SDR support. Opaque
  input alone cannot prove decoder bit depth; explicit known 8-bit YUV is rejected
  for HDR, while explicit P010 and other known >=10-bit YUV use the external-format path. HDR10+ obtains per-buffer T.35 metadata from MediaCodec, validates up to
  three windows, both optional luminance grids and saturation fields, and binds
  the global window to the exact AImage timestamp. As with the pinned VLC
  avcodec mapper, libplacebo applies the full-picture window only: local-window,
  grid and saturation-specific rendering is not implemented. This projection
  is logged once per decoder. The decoder
  rejects missing/mismatched/invalid metadata when HDR10+ is requested.
  The source ST 2086 units and normalized HDR10+ luminance are converted to
  libplacebo units. Dolby Vision is rejected using native format flags as well
  as app classification; opaque converted RGB cannot safely carry raw RPU reshape.
  When a known Dolby Vision title requires GPU tone mapping in the experimental
  build, use VLC's existing avcodec software decoder and its per-picture DOVI
  ancillary for GPU reshaping instead of MediaCodec copy-back without RPU.
  Native Dolby Vision surface playback and production builds are unchanged.
  A separate, default-off native `--vk-ambient-raw-p010` option now exercises
  explicit native P010 imports, RGB_IDENTITY sampling (no range expansion),
  16-bit UNORM storage and packed Cr/Y/Cb mapping into libplacebo for HDR10/HLG.
  This is a pre-reshape validation route; it does not yet accept Dolby Vision.
  It requires the actual P010 format, native image import and chroma features.
  HEVC access-unit scanning now detects in-band RPU even when container DV flags
  are absent and requests an RPU-aware decoder reload before modifying the input.
  The bounded Annex-B/length-prefixed extractor, RBSP unescape and exact PTS
  association queue have host sanitizer fixtures; the queue is groundwork for
  the future hardware-decoded per-picture RPU attachment, not yet active.
  The existing external-format RGB path is unchanged by default.
  The software path is a correctness fallback, not AHB/DV hardware decoding; enhancement-layer
  reconstruction and runtime decoder performance are not established.

## Validation

`bash scripts/test_vlc_ahb_import.sh` runs host ASan/UBSan regressions,
including format identity and device-loss ownership, GLSL compilation,
four Android ABI owner syntax/link checks, pinned libplacebo API and
patched display/instance/MediaCodec translation unit checks when the source paths are
provided. The CI stages the exact patches twice and checks the isolated build
hashes. Native contract job 140322953652 in run 45943521606 passed all four ABI
checks including the MediaCodec metadata path before the latest format and parser extensions. The full
linked build was superseded by subsequent commits; no linked artifact has
been confirmed yet.

The experimental workflow also schedules a full ARM64 VLC/JNI AAR build
after the native contract checks. That linked artifact is not a GPU execution
test. Linked builds are serialized without canceling a running build when a
new contract-check commit arrives. No hardware performance or correctness claim is made yet.

## Required before production activation

- Complete the full linked build; verify opaque MediaCodec/AImageReader
  negotiation on the actual driver, descriptor allocation, ownership/layout
  validation, color range/crop, orientation, subtitles and rendering.
- On Shield and phone test long playback, seeks, pause, surface recreation,
  format changes, SDR/PQ/HLG color accuracy, HDR10+ metadata and unsupported HDR10+/DV fallback, restart to
  copy-back, device loss and close.
  Confirm no validation errors or retained AImages and measure startup,
  frame times, dropped frames, decoder backlog and memory versus copy-back.
- A permanently blocked Vulkan or Android driver call cannot be safely
  interrupted by the in-process watchdog. The decoder/application timeouts
  handle ordinary starvation and missing statistics, not arbitrary kernel or
  driver hangs. The experimental reopen reports a terminal error after 12 s
  without releasing or reusing an in-flight native player. Full process isolation
  would be needed for hard recovery guarantees.
  Verify recovery on hardware before enabling production.

References:
- https://docs.vulkan.org/refpages/latest/refpages/source/VK_ANDROID_external_memory_android_hardware_buffer.html
- https://docs.vulkan.org/refpages/latest/refpages/source/VkAndroidHardwareBufferFormatPropertiesANDROID.html
- https://docs.vulkan.org/refpages/latest/refpages/source/VkSamplerYcbcrConversionCreateInfo.html
- https://docs.vulkan.org/refpages/latest/refpages/source/vkImportSemaphoreFdKHR.html
- https://docs.vulkan.org/refpages/latest/refpages/source/vkGetSemaphoreFdKHR.html
- https://docs.vulkan.org/refpages/latest/refpages/source/VK_EXT_queue_family_foreign.html
- https://docs.vulkan.org/spec/latest/chapters/devsandqueues.html
- https://developer.android.com/ndk/reference/group/media
- https://developer.android.com/ndk/reference/group/a-data-space

The contract CI additionally applies the reviewed production VLC patch stack before staging the experiment. The MediaTek HEVC Surface fallback is retained; the experimental patch does not overlap its inserted guard.

The experimental contrib build now adds a private, statically linked interface to FFmpeg's existing RPU parser. It validates CRC, profile 8, 10-bit base layer, no residual/extended mapping, bounds on pivots and coefficients, and returns a value snapshot. The host CI builds the pinned actual FFmpeg decoder and exercises allocation, invalid RPU handling and flush. This parser interface is not yet attached to MediaCodec output pictures; the production FFmpeg source and normal AAR are untouched.

The explicit native `--vk-ambient-ahb --vk-ambient-dovi-ahb` test mode connects the parser, exact PTS queue, AImage timestamp check, VLC DOVI ancillary and native P010 identity sample to libplacebo's existing RPU reshape. Only flagged single-layer HEVC profile 8 with BT.2020/PQ limited-range source and actual P010 is admitted. RPU NALs are removed from MediaCodec input to avoid vendor double-processing. Missing or unsupported RPU, mismatched timestamps or formats fail closed. App activation is deliberately not yet enabled; full linked CI, sample tests and real-device validation are mandatory. Profile 5, enhancement layers, spatial HDR10+ and other formats are not implemented.

The parser CI also generates CRC-protected synthetic profile-8 metadata using FFmpeg's own RPU writer, checks successful decode and distinct value snapshots, corrupts the CRC, exercises recovery and rejects profile 5. The hardware route rejects non-base-layer NALs even when container EL flags are wrong. These are metadata tests, not decoded-video/color-reference tests.

App opt-in: build the isolated AAR first, then use both `-PVLC_AHB_EXPERIMENT=1 -PVLC_DOVI_AHB_EXPERIMENT=1`. The latter is independently default-off. Known Dolby Vision GPU processing is admitted on API 34+ only; native decoder/renderer require actual supported profile, colorimetry and P010 buffer. If the route fails, the existing watchdog reopens with the FFmpeg RPU-aware software decoder, then ordinary output fallback if needed. No production flag changes. The UI describes the requested experimental route, not verified hardware decoding until runtime tests.

Profile-5 support extends the private RPU parser and native hardware opt-in to verified single-layer 10-bit full-range IPTPQc2. The renderer does not interpret IPT as BT.2020 YCbCr before reshape; actual P010, PQ transfer and full range remain required. Profile-specific parser state and generated RPU fixtures reject cross-profile and range mismatches. Correct the existing VLC DOVI coefficient scaling for legal 31/32-bit denominators. No hardware validation yet. CI uses Ubuntu 24.04 rather than the rolling runner image to avoid unrelated APT repository failures.

The decoder output structure is zero-initialized so the JNI route never reads garbage NDK HDR10+ metadata. The experimental AImageReader path disables tunneled playback. Listener callbacks use a video-context-owned lifetime gate; detach clears the decoder pointer and waits for active callbacks without holding the decoder mutex. The reader context outlives native output thread join and codec stop. Native CI includes JNI decoder and libplacebo utility translation units.

All AImageReader sessions abort pending callback waits before detaching at close, including ordinary readers in the isolated experimental AAR; early decoder initialization records the video category so bailout cleans partial setup. The original production AAR remains unmodified.

HDR10+ now retains all window geometries, per-window distributions, Bezier curves, saturation weights and both luminance grids in a separate picture ancillary matched to the AImage timestamp. The existing VLC ancillary is produced by a dedicated global-window projection adapter. Host regressions inspect every retained window and grid, including versions 0/1 and rejection of unknown versions. This removes data loss before the renderer and is groundwork for spatial rendering; the current libplacebo path still uses global tone mapping only.

A separate default-off `--vk-ambient-hdr10plus-spatial` prototype performs a GPU RGB/PQ prepass for two or three supported local windows and SDR output. It reuses libplacebo's ST2094-40 LUT generator, caches the three curves and dispatch resources, applies elliptical feathering and weighted/layered blending in linear max-RGB, then advertises the mapped PQ peak and disables dynamic peak detection for the ordinary gamut/scaling/overlay pass. No CPU video readback. Unsupported geometry, degenerate curves, grids, saturation mapping, crop offsets or explicit conversion LUTs retain global tone mapping. Spatial weights and geometry have host tests; the prototype is not a full ST2094-40 conformance implementation and needs real GPU/reference-image validation before app enablement.

A host Vulkan GPU job builds the pinned libplacebo and exercises the same spatial prepass on a synthetic uniform PQ frame with a local window, checking readback center/corner difference, output luminance bound, resource reuse and unsupported-metadata fallback. It is not a physical Android AHB or conformance-reference test.

The raw-P010/Dolby route requests API-34 AImageReader_newWithDataSpace with native YCbCr P010, source dimensions, range and transfer, using GPU-only usage. Vendors rejecting the explicit reader may still use PRIVATE, but actual Vulkan P010 format and image-dataspace validation remain mandatory. Native P010 import checks its own physical-device format/import capabilities rather than requiring unrelated external-format sampling capabilities. A requested default dataspace is not proof of actual decoder color behavior; decoded codeword/reference validation remains required.

The local-window prototype is separately available in the isolated app test build with `-PVLC_AHB_EXPERIMENT=1 -PVLC_HDR10PLUS_SPATIAL_EXPERIMENT=1`. The flag is passed only for known HDR10+ sources requiring GPU tone mapping; native HDR and ordinary builds do not enable it. Per-frame unsupported metadata still takes the existing global path. Host Vulkan CI 45949405304 passed with a synthetic 600-nit PQ input (179.50-nit local center versus 84.24-nit global corner), and the native four-ABI/FFmpeg contracts passed on the same revision. This demonstrates local GPU processing, not ST2094-40 conformance or Android hardware compatibility.


## Extended HDR10+ and FEL work (2026-11-27)

The opt-in local-window HDR10+ prepass now also accepts one window, uses
cosine feathering and weighted/layered overlap, evaluates both display
luminance grids against fraction-bright-pixels and average MaxRGB (not pixel
coordinates), and applies bounded per-window saturation compensation. Grid
factors modify scene/display peaks and participate in the LUT cache key.
The saturation and peak adaptation are experimental Annex-B-inspired
algorithms, not a normative reference implementation. The metadata parser
retains legal knee endpoints and records a partial application-version-1
constraint check without rejecting otherwise parseable legacy payloads.
Version 1 excludes local windows, grids and saturation mapping; the general
syntax can nevertheless describe these fields. Unsupported curves still
fall back to global processing. No certification or complete ST 2094-40
conformance claim is made. Host native, FFmpeg and HDR10+ GPU tests passed
at f6b1e678 (run 45952281364).

FEL groundwork: dovi_fel_nlq.h implements integer-code residual inverse
quantization with the zero case, coefficient scaling, signed residual and
maximum clamp. dovi_fel_gpu.h composes aligned raw BL and EL textures using
the pinned libplacebo reshaping shader before adding the bounded residual.
The output is reconstructed codewords, not display RGB. Dedicated CI
fixtures compare GPU pixels with the CPU reference and exercise the real
pinned FFmpeg dovi_split filter, including PTS preservation. These new
components are not connected to the active VLC decoder or display. Profile
7 playback still requires EL decoder initialization, synchronized decoded
picture ownership, chroma/geometry alignment, complete RPU NLQ transport
and downstream color conversion. Missing EL must not be presented as FEL.
The original single-layer hardware route and production configuration
remain unchanged.


### Profile 7 decoder and metadata follow-up

The software EL decoder component uses the real pinned FFmpeg HEVC decoder
and `dovi_split` filter. It accepts combined Annex B access units with
in-band EL parameter sets, decodes raw 10-bit 4:2:0 frames, and transfers
owned frames by exact PTS from a bounded 12-frame queue. Drain/flush and
failure recovery are explicit; no stale or nearest-timestamp frame is
substituted. A host test wraps an actual independently encoded Main10
picture as type-63 NALs, decodes it and exercises ownership across flush.
The isolated native contrib now enables `dovi_split`.

The private FFmpeg RPU interface exports validated profile-7 LINEAR_DZ
parameters and the existing reshape/matrix fields; profile 5/8 continue
to reject residual metadata. The FEL metadata bridge converts this value
snapshot to libplacebo reshape and NLQ inputs. Synthetic CRC-protected
profile-7 RPU and cross-profile rejection tests have been added.

The previous host FEL GPU fixture passed (run 45952822050); the logs
contained debug-utils extension validation warnings, addressed by enabling
the extension explicitly in the test instance. The previous FFmpeg split
test failed on Annex B packet size; the replacement compares NAL payloads,
allowing trailing zeros. New FFmpeg/decoder and final-head CI are not
yet confirmed.

The components still require live VLC decoder/callback wiring, pairing
of hardware BL and software EL across output reordering and drops,
chroma/layer alignment and matrix/color conversion before the feature
can be enabled. No actual FEL title playback or production activation
is claimed.


### Experimental live Profile 7 FEL route

The AHB decoder/display integration now accepts explicitly flagged single-track
profile-7 streams with combined Annex B BL/EL/RPU, explicit BT.2020 PQ limited
range and uncropped dimensions. The isolated test build requires all of:

```sh
VLC_EXPERIMENTAL_AHB=1 bash scripts/build_vlc_vulkan.sh
# App Gradle properties:
# -PVLC_AHB_EXPERIMENT=1 -PVLC_DOVI_AHB_EXPERIMENT=1 -PVLC_DOVI_FEL_EXPERIMENT=1
```

The last flag passes the separate default-off `--vk-ambient-dovi-fel` option.
The base layer is decoded by MediaCodec into actual native P010. The software
FFmpeg EL decoder receives the original combined access unit before wrapped
EL NALs and RPU are removed from the BL input. Its output thread pairing uses
a bounded condition wait on exact PTS, with explicit flush/drain/drop and
ownership handling. The AImage timestamp is checked again. An owned picture
ancillary transfers the raw EL planes and RPU to the display without sharing
FFmpeg state across modules.

The Vulkan prepass uploads three reusable R16 UNORM EL planes, samples with
separate chroma and layer alignment (1:1 or 2:1, left/center layer siting),
performs NLQ and BL reshape in the correct order, and uses the existing
libplacebo Dolby Vision matrix/PQ conversion without a second reshape.
The resulting PQ RGB frame goes through the ordinary display pipeline.
Unknown chroma siting uses a documented top-left assumption. The host
reference test for planar alignment and matrix conversion passed at
6f1dae5 (run 45955201352); the real FFmpeg decoder/RPU contract also passed
at that revision. The newer opaque API and active VLC handoff tests,
four-ABI contract, fully linked AAR and app validation remain unconfirmed.

This is a restricted experimental route, not a fully validated or
bit-exact Dolby Vision implementation. Separate EL tracks, EL configuration
available only out-of-band, cropping and unsupported geometry remain
outside the route. Physical Android playback, reference image comparison,
seek/reorder stress and performance validation remain necessary. HDR10+
full ST 2094-40 conformance is still not established.


### ST 2094-40:2020 curve and distribution follow-up

Reference: https://pub.smpte.org/pub/st2094-40/st2094-40-2020.pdf
(section 8.5, 8.7.4 and 9.4; Annex B.4).

The normalized authored Bezier curve now uses de Casteljau evaluation
for degree 1 through 16, including the zero and unit knee cases and
zero intermediate anchors. The authored target uses this curve directly;
other targets retain libplacebo adaptation, except for the explicitly
handled degenerate/zero-anchor curve cases. Curves are normalized to the
actual target peak, not limited by the source peak when an authored curve
intentionally expands brightness. The derivative is checked in Bernstein
form, using bounded interval subdivision when control points are not
sorted; unresolved cases fail closed. Metadata without an authored OOTF
uses scene-statistics-based mapping. Zero MaxSCL falls back to actual
distribution percentiles, excluding the version-1 reserved markers.

Version-1 diagnostics now distinguish mandatory restrictions and CFD
ordering from the recommended nine-position distribution vector. Tests
cover analytical curve cases, reserved markers, invalid derivatives, and
GPU readback independent of the LUT used in rendering.

At a6c7820 all three native contract jobs passed (45955660024), including
the integrated four-ABI MediaCodec/display syntax checks, actual pinned
FFmpeg tests, and host GPU fixtures. This is not a full linked AAR or
device playback result. The subsequent HDR10+ changes need their own CI.
The renderer still has display-adaptation policy, LUT approximation and
experimental legacy grid/saturation handling; neither complete reference
equivalence nor HDR10+ certification has been established.


### Display LUT normalization and MP4 hvcE follow-up

Annex B.3 source normalization now uses the mastering actual-peak LUT
as an alternative denominator to MaxSCL, not a multiplier on the
MaxSCL-derived peak. Individual RGB components are clamped to that
normalization domain before applying the tone curve. Saturation uses
separate normalized source/target luminance scales based on Annex B.11:
both available actual-peak factors are applied together or both omitted.
Missing explicit mastering peak uses the scene peak as an adaptation
fallback; actual-display adaptation and guarded logarithms remain
implementation policy. Ellipse centers are bounded by the image rather
than the processing rectangle, and the normative clockwise angle in
y-down image coordinates is covered by a 45-degree test. The existing
inverse coordinate rotation is intentionally retained.

All three native contract jobs passed at c3765b0 (run 45956475239).

The experimental MP4 demux patch reads hvcE as an HEVC configuration
record and validates bounded VPS/SPS/PPS arrays. For opted-in combined
profile-7 streams with wrapped EL NALs, it injects the wrapped
configuration at the first EL NAL after BL VCL on random access or
discontinuity. This preserves the container length-prefix width and
avoids VLC's HEVC packetizer discarding orphan tail NALs. Malformed or
unsupported configurations are not injected. The host fixtures exercise
prefix widths, truncation and the insertion location. Separate BL and EL
tracks with vdep references are still not merged by this patch. The
final native/app build and real MP4 seek/playback tests remain pending.


### Legacy dual-track MP4 profile 7

The experimental MP4 demuxer now recognizes the Annex C legacy carriage
with a DV-specific dvhe/dvh1 enhancement track, profile 7, EL/RPU flags
and an explicit, unique vdep reference to a conventional HEVC base track.
It only pairs seekable, non-fragmented tracks with matching sample counts,
timescales and edit lists, one sample description per track, unique
track IDs, and BL color/geometry compatible with the experimental P010
path. The BL ES is recreated with the combined Dolby Vision configuration,
while preserving the BL codec, extradata and priority. The EL track is
removed from normal ES scheduling, but its sample index remains owned
by the MP4 demuxer.

For each BL access unit the EL sample is read directly from its indexed
chunk, without modifying EL playback state. Both PTS and DTS must match
after edit-list mapping. The merger validates both NAL-length widths,
wraps ordinary EL NALs as type 63, preserves the single type-62 RPU, and
injects EL hvcC parameter sets on random access/discontinuity. Invalid,
missing or mismatched samples fail the paired track rather than using
nearest timestamps or stale residuals. The merged stream goes through
the existing HEVC packetizer and experimental VLC FEL decoder.

Host tests cover mixed prefix widths, truncation, missing RPU, config
injection and the new path's actual independently encoded Main10 EL
decode versus the original single-track wrapper (synthetic BL/RPU
markers, not a certified Dolby Vision stream). The native CI now also
compiles the patched MP4 demuxer and box parser for all four Android
ABIs. Both MP4 patch contexts were checked against the exact pinned
VLC source. Native contracts passed at 9f7f663 (45956782241), before
the separate-track changes. The new CI and linked ARM64 AAR are not
yet confirmed. Actual dual-track MP4 playback, seek/reorder tests,
fragmented MP4 and streams with different edit/timing tables remain
unvalidated or outside this restricted implementation. HDR10+
full reference equivalence remains unproven; production is unchanged.


### Dual-track audit and fragmented MP4

Sequential variable-size EL sample offsets now use a bounded cursor;
backward seeks and chunk changes restart from the indexed chunk. Invalid
sample sizes, duplicate first slices, missing matched random access and
terminal pairing errors are rejected, without repeatedly selecting the
same corrupt ES.

Seekable pure fragmented MP4 with separate DV profile-7 tracks now also
uses the experimental FEL route if both tracks have one stable sample
description, matching timescale and no edit lists. Each relevant moof
must contain one traf per layer with explicit matching tfdt. Both
trun indices and sample counts must match, and each sample is checked
for exact decode/presentation time and duration in raw track ticks
(including signed composition offsets). EL bytes are read only inside
the current mdat payload. The BL stream position is restored before
continuing the normal trun loop. Hidden-track state is refreshed on
fragment boundaries and seeks, and explicit discontinuities re-inject
EL configuration. Host tests exercise cursor seek/overflow, NAL
boundaries and fragment time/byte-range validation.

Both MP4 patches were checked against the exact pinned VLC source
contexts at 9ffb7f0. The earlier FFmpeg and HDR10+ GPU jobs passed
(45958323181); the Android ABI job was canceled by subsequent pushes,
and the latest complete AAR build and app checks remain unconfirmed.
This is not validation with a real fragmented Dolby Vision title.
Nonseekable streams, hybrid moov+moof tracks, audio-only/missing-layer
fragments, differing trun layouts or timescales and alternate container
carriage remain outside the restricted route. HDR10+ full reference
equivalence remains unproven; production configuration is unchanged.


### Authored curve precision and metadata validity

ST 2094-40:2020 sections 8.5, 8.7 and 9 and informative Annex B
were rechecked against the published standard:
https://pub.smpte.org/pub/st2094-40/st2094-40-2020.pdf

The direct-authored GPU curve now evaluates the linear portion exactly;
its 256-entry LUT covers only the curved segment, so even the smallest
legal nonzero knee does not interpolate across the linear/Bezier
boundary. Direct normalized output scales with target peak without an
additional black pedestal. The ordinary renderer handles display black
adaptation. The independent GPU tests verify black, steep near-black
knees, and colored saturation against analytical calculations, not
just the generated LUT. Different overlap options are accepted for
provably disjoint selectors; intersecting bounding boxes still use
the conservative rejection policy when their modes differ.

The parser distinguishes syntax compatibility from checkable semantic
constraints for both application versions, including CFD ordering and
Bezier monotonicity. Version 0 optional-feature recommendations remain
separate from mandatory restrictions in version 1. The source peak
fallback uses the final valid CFD percentile; reserved v1 markers are
not scene statistics. A decoder cannot verify statistics derived from
the original scene from metadata alone. This is not a certification
claim. Annex B describes illustrative processing; adaptation to other
target displays, ideal-black BT.1886 saturation, conservative geometry
checks and LUT interpolation remain implementation choices.

At 4c6137a all three native contract jobs passed (45961233663), including
the real reordered Main10 EL reference fixture and all four Android
ABI syntax checks; the app PR checks also passed. The linked ARM64 AAR
build started. At 5d09306 the independent new HDR10+ GPU fixtures passed
(45962861319). Subsequent parser/CFD changes need their final CI.
No real title/device playback, bit-exact Dolby Vision FEL reconstruction
or full HDR10+ certification is established. Experimental defaults
remain off.


### FEL numeric precision and output ownership audit

The FEL sequence (resample EL integer codewords, inverse LINEAR_DZ
quantization, add to reshaped BL, Dolby color decoding) was compared with
libplacebo v7.387.0, src/renderer.c sample_el and
src/shaders/colorspace.c sh_dovi_compose_nlq:
https://github.com/haasn/libplacebo/tree/v7.387.0/src

The manual bilinear path now converts each R16 UNORM fetch back to its
integer code before interpolation, avoiding normalized texture rounding
noise at half-code boundaries. Round the signed difference from the NLQ
offset, with a deterministic symmetric half-away-from-zero tie policy
(the upstream GLSL round tie direction is implementation-dependent).
The full-resolution luma path needs only one fetch instead of four.
Chroma siting is independently validated and all six signaled locations
are tested. The dedicated FEL dispatch is reset each frame.
The parser and shader bridge preserve unsigned 64-bit NLQ coefficients;
all scaled values fit floating-point range. A generated RPU exercises
the 32-bit fractional denominator and values above UINT32_MAX.
These changes do not replace the bilinear filter with a proprietary
Dolby reference resampler or establish bit-exact equivalence.

The output path now retires pending FEL/RPU snapshots on metadata
rejection, rejects unexpected EL ownership at the AImage callback,
and observes decoder close during the exact-PTS wait. No asynchronous
FEL decoder worker or unbounded queue was introduced.

All three native contract jobs passed at 5321d55 (45963085341).
At 51dc2cc the new GPU tests for signed ties, direct luma, all six
chroma sitings and wide coefficients passed (45964029537). The later
RPU generator test, MediaCodec ownership changes and final ABI
contracts need their own CI. Full linked ARM64 AAR and real-device
Dolby Vision playback remain unconfirmed. The experimental flags
remain off by default.


### Forward-only fragmented FEL and explicit input framing

Pure fragmented MP4 with an unambiguous Profile 7 BL/EL track pair
can now use a forward-only source. A finite mdat payload is consumed
once into an owned block, limited to 64 MiB. All fragment track reads
(including audio and subtitles) use bounded slices of that immutable
payload, so neither the EL-before-BL nor EL-after-BL storage ordering
requires a backward stream seek. The cache is released at fragment
completion, reset, or demux close. Seekable inputs continue to use
the existing indexed read path. This deliberately does not enable
unbounded mdat boxes, hybrid moov+moof files, or arbitrarily large
fragments; buffering a whole fragment increases initial latency and
memory use.

Fragmented BL and EL tracks may have different media timescales.
Explicit tfdt, total run duration, and sample DTS, PTS and duration
are compared by overflow-checked exact rational conversion. A
fractional destination tick is rejected, not rounded. Ordinary
non-fragmented dual-track MP4 still requires equal timescales and
compatible edit lists.

The decoder no longer requires Annex B vendor input for the wrapped
EL path. Explicit 1-, 2- and 4-byte NAL length prefixes are accepted;
the combined access unit is validated and converted to Annex B for
FFmpeg, while the original framing is retained when stripping EL
for the vendor BL decoder. The conversion rejects embedded unescaped
start codes, invalid headers, truncated NALs and oversized samples.
The actual Main10 decoder comparison fixture now uses this converter.

Host regressions cover bounded mdat slices, both storage orders,
signed exact time conversion, overflow, all prefix widths and
transactional rejection of truncated samples. The previous head
e58b260 passed all three native contract jobs (45965101902).
The new integrated source changes still require final CI and
actual forward-only dual-track playback testing. The earlier linked
native ARM64 build succeeded at the library-link stage, but the
AAR packaging failed on Maven Central HTTP 429; a bounded retry
has since been added. Full HDR10+ reference equivalence and exact
Dolby proprietary reconstruction remain unproven. Default-off unchanged.


### Additional indexed MP4 and HDR10+ conformance follow-up

The exact rational clock conversion is now also used for indexed
nonfragmented dual-track MP4: the original sample DTS, composition
delta and duration must match after conversion, and the mapped VLC
timestamps must still agree. Equivalent edit-list media times may
use different track clocks; empty edits retain the -1 sentinel and
the edit durations and rates must remain equal. Seekable hybrid
moov+moof files with matching initial sample counts, no edit lists
and stable sample descriptions are admitted to both existing
indexed and fragmented paths. Forward-only hybrid files and
incompatible edits remain excluded.

ST 2094-40 sections 6.1 and 6.7 use pixel-center membership.
When two local selectors specify different overlap modes, the
renderer now intersects their clipped elliptical row intervals at
integer pixel centers, rather than rejecting every overlapping
axis-aligned bounding box. The bounded scan uses a conservative
floating-point margin near tangencies. A regression independently
checks every pixel of a rotated, disjoint pair, and tests a truly
overlapping pair. Annex B.12 saturation gain is bounded both by the RGB gamut and
the explicit maximum gain of 4. The earlier removal of the 4x
limit was incorrect and has been reverted. The independent GPU
fixture computes an unconstrained B.11 gain above 4 and verifies
the capped result. Section 10.3's zero
FractionBrightPixels recommendation for new version-0 metadata is
reported separately from compatible legacy data; version-1 local
nonzero values violate the section 10.4.1 check.

Both MP4 patches match the pinned source contexts. At 8595108 the
FFmpeg and GPU contracts passed (45966188095); the ABI job and app
unit tests were canceled by subsequent pushes. The new head needs
its own complete CI and real indexed/hybrid/forward-only playback
validation. Full HDR10+ certification and bit-exact proprietary
Dolby FEL resampling are still unproven. Default-off unchanged.


### Visible picture geometry and independent HDR10+ syntax validation

The experimental profile-7 decoder, MP4 pairing gate and HDR10+
spatial prepass now accept origin-aligned visible pictures with
right/bottom coded allocation padding. A 3840x2160 visible BL in a
3840x2176 allocation can therefore match a 1920x1080 visible EL in
a padded 1920x1088 allocation. AImage import already produces the
visible texture. FEL explicitly rejects a nonzero physical crop
origin because the current EL phase mapping does not describe that
case. The shader and display handoff share an overflow-safe exact
1:1 or 2:1 visible-dimension check; tests cover padded and invalid
rectangles and incompatible ratios. Arbitrary shifted crops remain
unsupported.

The pinned FFmpeg contract CI now additionally generates T.35
metadata via av_dynamic_hdr_plus_to_t35 and compares every decoded
field against av_dynamic_hdr_plus_from_t35, including both application
versions, up to three windows, 25x25 peak grids, 15 percentiles and
15 curve anchors. FFmpeg writes application version 1; the version-0
reader fixture changes only the version byte. The test compares
syntax, not certification or display processing, and deliberately
includes legacy optional metadata combinations. Truncation checks
remain stricter in the local parser.

All three native contracts passed at 1664363 (45966850004), including
the high-gain saturation and rotated-selector tests. Its app test
was canceled by later pushes; linked ARM64 AAR still pending.
Final CI for the new geometry and independent metadata fixture is
not yet confirmed. The proprietary Dolby reference reconstruction
and arbitrary crop phase support remain outside the verified scope.
Default-off unchanged.


### Shared HDR adapter audit and B.12 correction

The published ST 2094-40:2020 Annex B.12 (page 23) explicitly
bounds the maximum saturation gain eta to [0,4], in addition
to its RGB gamut inequalities. The earlier removal of this limit
was incorrect. The shader and independent high-gain fixture now
apply the correct maximum of 4. The dark-luminance log guard is
still an implementation choice. Annex B remains informative.

The isolated experimental VLC build now converts ST 2086 xy
and luminance units and normalized HDR10+ RGB statistics in the
shared libplacebo adapter, not only in the AHB display path.
This also fixes the units on the software/copy-back fallback
within that build. Remove the old AHB-only conversion to avoid
double scaling. Content light metadata is already in nits and
remains unchanged. The shared HDR10+ adapter clears a stale
authored curve when the next metadata set has no curve.
The Dolby reshaping adapter uses ldexpf instead of shifting a
signed 32-bit integer by an RPU fractional denominator of 32.

The host CI now builds the actual patched VLC utils.c and runs
a linked UBSan regression on HDR units, curve reset and signed
32-bit-denominator reshaping coefficients; unrelated VLC plugin
functions are discarded by the linker. The display and utils
patch contexts were checked against the pinned source. All
three native contracts passed at 1664363, and the independent
FFmpeg HDR10+ syntax fixture and GPU tests passed at 81fda87
(run 45967867413); its NDK job was canceled by newer pushes.
Final-head CI and actual playback remain unconfirmed.
No certification or proprietary bit-exact FEL reconstruction
claim is made. Experimental defaults remain off.


### Global HDR10+ fallback and EL crop-origin audit

The full private HDR10+ metadata snapshot remains unchanged. Its
projection into VLC's global ancillary now excludes nonmonotone
curves and invalid CFD distributions. When all MaxSCL components
are absent, the last valid CFD percentile supplies an estimated
global peak (not independently measured per-component MaxSCL).
Reserved markers and malformed distributions cannot supply this
peak. An authored degree-one curve with no intermediate anchors
is represented exactly by degree elevation to [0, 0.5, 1], since
the pinned libplacebo fitter treats zero anchors as absent metadata.
Endpoint knees remain supported by the exact spatial path; the
global fitter instead receives scene-statistics-only metadata to
avoid its endpoint singularities. The shared spatial and fallback
validation uses the same helpers. Host tests check that none of
these projections alters the raw snapshot.

The EL software decoder disables automatic FFmpeg frame cropping
until it can check the original crop origin. It accepts right/bottom
padding and explicitly applies the crop, but rejects shifted left/top
origins rather than silently mixing misaligned pictures. New actual
HEVC conformance-window fixtures are produced by independent
hevc_metadata processing; they test accepted 62x60 visible dimensions
and terminal rejection of shifted-origin input. Full shifted crop
phase reconstruction is still unsupported.

At fffb7f2, the FFmpeg and GPU contracts passed (45968705455),
including the actual VLC shared HDR adapter UBSan test and corrected
saturation cap. Its NDK and app jobs were canceled by newer pushes.
The latest changes need their final CI. Full linked packaged AAR,
arbitrary shifted crop support, real-device playback, certification
and proprietary bit-exact reference reconstruction remain unconfirmed.
Default-off unchanged.


### Matched shifted conformance windows and zero-residual fast path

Supersedes the earlier origin-zero-only admission policy: the private
EL snapshot retains the actual SPS conformance-window origin. The
decoder validates the even 4:2:0 crop and the snapshot transfer applies
the crop exactly (unaligned memcpy only, never unaligned SIMD). The
AImage's physical BL crop must agree with the declared BL origin and
the EL origin after exact 1:1 or 2:1 scaling, independently on each
axis. MediaCodec retains coded dimensions for the experimental P7
output. Renderer entry repeats the same geometry contract.
This supports matched, chroma-aligned shifted visible rectangles;
odd, mismatched and fractional-phase crops remain unsupported. The
resampler operates on the visible EL rectangle and clamps its edges;
equivalence to proprietary pre-crop resampling is not established.
Real HEVC crop fixtures compare every Y/Cb/Cr sample with the
uncropped decoded reference. GPU tests check accepted/rejected origins.

A mathematically identically zero NLQ residual (all components with
zero maximum or both zero slope and threshold) needs no EL GPU plane
allocation, upload, or sampling. This includes canonical MEL
coefficients as described by dovi_tool's RpuDataNlq::is_mel:
https://github.com/quietvoid/dovi_tool/blob/main/dolby_vision/src/rpu/rpu_data_nlq.rs
The predicate is not a profile classifier. The EL software decoder
and exact-PTS ownership remain active to preserve reference pictures
across a later nonzero-residual frame. Keep the final codeword clamp
identical. The shader additionally rejects invalid pivot ranges,
mapping methods, MMR orders and nonfinite coefficients before entering
libplacebo. Host GPU regressions exercise zero/nonzero transitions,
absent EL textures and malformed reshaping.

At 63d115a FFmpeg and HDR GPU contracts passed (45969269376), including
the previous crop and fallback tests; NDK and app jobs were canceled
by subsequent pushes. MP4 and display patches match pinned source
contexts. The new crop/zero-residual implementation needs final CI,
AAR and practical playback validation. No full HDR10+ certification,
bit-exact proprietary FEL filter or arbitrary phase reconstruction
claim is made. Experimental defaults remain off.

### Authored HDR10+ curve precision and FEL color validation

The spatial renderer uses a 1024-knot LUT over the curved segment, with
shader indexing derived from the actual texture extent. For normalized
Bezier control points in [0,1], degree <=16, the second derivative is
bounded by 480, yielding a uniform linear interpolation bound below
0.000058 (excluding floating point error and display adaptation).
Unused local LUT rows copy the global result instead of regenerating it.
The host GPU fixture checks the degree-16 closed form and actual pixel
readback. FEL nonneutral packed-P010 integration checks CPU-reconstructed
codewords followed by the same pinned libplacebo color conversion; matrix
nonfinite inputs are rejected. These are engineering regression tests,
not full HDR10+ certification or an independent Dolby reference decoder.


### Spatial HDR10+ on explicit PQ targets

The spatial prepass now also accepts a PQ target with explicitly supplied
valid peak/minimum luminance, up to 10000 nits. It retains absolute PQ
encoding and mapped metadata for the normal renderer. Unknown PQ peaks,
HLG targets and nonzero authored saturation compensation on PQ fall back
to the existing global rendering route; the current saturation algorithm
uses SDR BT.1886 and is not assumed to be a PQ saturation model.
Native Android HDR passthrough preference and application gating remain
unchanged. This is native renderer capability, not a new default HDR
output policy or certification. Host GPU fixtures compare absolute nits
on 600 and 1500 nit targets and test rejected configurations.


### HDR saturation working space and HLG target support

ST 2094-40:2020 Annex B.5 (informative, equations B.6–B.10)
uses BT.1886 as an intermediate nonlinear RGB transform, returning to
linear RGB after saturation processing. It does not prescribe BT.1886
as the final display transfer function. The same ideal-black working
transform is now allowed for explicit PQ and HLG target luminances.
The spatial prepass remains PQ-encoded display-referred light; the
pinned libplacebo renderer performs the final HLG inverse OOTF/OETF.
No unknown target peak is inferred. Peaks below 100 nits are excluded
for HDR because pinned libplacebo ignores that static metadata range;
HLG contrast must also keep the inverse OOTF black parameter below one.
Host tests cover nonneutral PQ saturation against independent arithmetic
and final HLG rendering of local neutral pixels against BT.2100 math.
This is an experimental adaptation, not normative conformance or a
change to native Android HDR routing.
Reference: https://pub.smpte.org/pub/st2094-40/st2094-40-2020.pdf


### FEL mapped visible rectangles

Keep the original coded EL extent alongside the conformance crop and
sample count. Where visible rectangles do not match, require exact 1:1
or 2:1 coded extents and full coverage of the BL rectangle by available
EL samples. The display bridge additionally checks that the AImage
confirms the declared coded BL extents and physical origin. The shader
adds the derived EL-luma offset before chroma subsampling, including odd
EL offsets (half chroma sample phase). No extrapolation of missing EL
pixels or arbitrary scale is admitted. Existing matched crops retain
their prior behavior. The GPU uses the existing experimental bilinear
filter, not a claim of Dolby proprietary reference filtering. Tests cover
actual cropped HEVC coded dimensions, invalid geometry and independent
pixel arithmetic for shifted 2:1 sampling.

The AImage bridge forwards its coded-extent confirmation bit to the
renderer. Both admission and rendering therefore select the same
mapping even when the codec reports visible dimensions. A proven
exact coded ratio takes precedence over coincidentally equal visible
sizes; only an unproven coded ratio uses the prior matched-visible
contract. Fractional luma offsets are rejected; an integral odd luma
offset can still represent half a chroma sample at 4:2:0.


### Contiguous dual-track fragmented decode clocks

A paired fragment can omit one or both tfdt boxes after an explicit
pair of anchors and a fully validated, contiguous run timeline. The
inherited start must equal the previous verified end for both tracks;
any explicit anchor in a partially implicit pair must match that end.
The demuxer seeds both raw track clocks before building trun indexes,
without rounded SIDX/movie-time fallback. Seeks and discontinuities
invalidate implicit anchors; audio-only fragments do not advance video
clocks. Validate run count/start/end and return preparation errors from
seek. Missing initial anchors, ambiguous timing, missing layers and
unproven discontinuous starts are still rejected. Pure host regressions
cover exact and conflicting rational timelines; full demux and device
playback validation remain separate.


### Explicit HDR target negotiation in Ambient TV

The default-off spatial build may override native HDR preference only
when the user explicitly selects Vulkan for a known HDR10+ source,
Android API >=34, the enabled display HDR types and valid reported
luminance are available, and CPU processing/SVP are off. PQ is preferred
when enabled; otherwise characterized HLG is possible. Auto and
production remain unchanged. The native swapchain hint is set from
these independent display capabilities, not source mastering metadata.
Validate the actual target transfer, BT.2020 and >=10-bit depth before
applying the target parameters; a failed explicit HDR output goes back
to the original native HDR/SDR policy instead of an unverified Vulkan
copy-back path. Detect default-display/capability changes during playback.
A spatial SDR request explicitly selects SDR. Native and app regression
fixtures cover invalid capabilities, source vs target luminance and
actual output format checks. Physical display behavior is unverified.


### FEL prediction-domain correction against newer libplacebo

libplacebo v7.387.0 now includes FEL composition. Its code distinguishes
EL integer code scaling from BL prediction scaling (2^bit_depth), clamps
all predictor channels to their respective pivot intervals before MMR,
and uses the RPU nonlinear offsets directly in the composed domain.
The pinned v5.264.1 implementation instead uses UNORM pivots and scales
nonlinear offsets. The live FEL prepass explicitly corrects these three
aspects without changing other VLC rendering paths or the pinned ABI.
EL sampling and residual calculation retain their own integer domain.
Synthetic GPU regressions exercise cross-component MMR with unequal
pivot limits and a nonzero matrix offset. This is a source comparison
against newer open-source libplacebo, not Dolby reference certification.
See https://github.com/haasn/libplacebo/blob/v7.387.0/src/shaders/colorspace.c
and https://github.com/haasn/libplacebo/blob/v7.387.0/src/include/libplacebo/utils/libav_internal.h .


The v5 prediction shader also clamps the output to the endpoint pivots,
whereas the newer shader clamps to [0,1]. For the FEL-specific domain,
preclamp the input and widen only the shader's local endpoint copy so
that internal segment selection is unchanged and the output is not
incorrectly clipped. The initial new cross-channel regression exposed
this difference; its independent expected values remain unchanged.

EL discard markers are now consumed in the decoder before queue admission,
so a known dropped frame cannot cause overflow of a full 12-frame queue.
The marker set remains bounded, is reset on flush/drain, and ignores
unknown late drops after drain. Allocation/submit failures are terminal
until flush. Host tests exercise full-queue known/unknown output, duplicate
PTS, repeated discard and drained late discard in addition to real HEVC.

An additional nonneutral packed-P010 test now computes the entire FEL
color conversion independently in double precision (polynomial and
residual, nonlinear RPU matrix/offset, ST 2084 EOTF, linear matrix,
HPE-to-BT.2020 conversion and inverse EOTF). It does not use a second
libplacebo shader as the expected RGB result. This checks the implemented
floating-point reconstruction, not the proprietary Dolby reference filter.


### Adjacent seekable BL/EL fragments

In addition to the existing shared-moof layout, accept a seekable
BL moof/mdat immediately followed by an EL-only moof/mdat with the
next sequence number and the exact declared dependent track. Retain the
second box tree until reset, index its runs in their own file-offset
coordinate system, restore the BL stream position after lookahead and
skip the consumed EL fragment once BL demux completes. Exact paired
sample count, rational timing and per-sample PTS/duration checks remain.
Require bounded boxes, explicit initial EL trun offset, monotone EL
storage and complete containment in the EL mdat. Signed offsets and
box extents have host tests. Forward-only separate fragments, multiple
EL tracks in the adjacent fragment, and non-adjacent layouts remain
unsupported; full container playback is not yet demonstrated.


### Forward-only adjacent fragments

For the same strict adjacent BL/EL layout, forward-only input consumes
both mdats once and retains distinct payload buffers within a combined
64 MiB limit. The bounded EL moof is parsed between them. The normal
fragment scheduler reads BL/audio/subtitle samples from the first
buffer and exact dependent samples from the second. After consumption,
advance the sequence number without seeking backwards, and release
both buffers at fragment end/reset. Partial reads and budget violations
are terminal for forward-only input; no unbounded buffering or guessing.
Fixed a missing addition marker and recomputed unified diff offsets;
actual container playback and final linked build remain to be verified.


### Multiple dependent tracks in the adjacent fragment

The adjacent EL moof may carry multiple hidden dependent tracks, each
paired with its own BL in the first moof. Per-track membership controls
payload selection; the shared box is read once even on forward-only
input. Validate all trafs (no unrelated or duplicate tracks), index
explicit/default/implicit base data offsets in the adjacent moof's own
coordinate system, share the 65,536 run and 1,048,576 sample limits,
and sort run byte ranges to reject overlap before demux. Preserve the
combined 64 MiB payload cap. Tests cover disjoint, touching, overlapping,
duplicate and empty byte intervals. Actual multi-track playback and
linked build remain unverified. Non-adjacent fragments remain excluded.

Fragment reset now releases run indexes and clears adjacent-track membership
before reuse. Failed preparation after a seek or during streaming resets
all partially constructed indexes and stops demux rather than attempting
to use the replaced context. The cached fragmented scheduler limits each
track to its current sample per iteration instead of reading an entire
run ahead on forward-only inputs.

Before VLC's generic trun indexing, paired moofs now validate run/sample
counts, mandatory sample tables and the uint32_t run-size accumulator,
including unrelated tracks whose bytes affect implicit offsets. Implicit
FEL clocks require both the previous and current sequence headers; missing
headers invalidate inherited anchors, and explicit timeline jumps flag
BL discontinuity even when sequence numbers are consecutive. Added host
boundary tests and fixed the duplicate-range fixture's variable name.

Paired fragment preparation also compares every corresponding sample's
exact rational duration and composition offset before sending any BL AU,
regardless of trun boundaries. With the verified equal initial clocks
this proves all DTS/PTS pairs, not just equal total duration. Preserve
discontinuity flags through audio-only moofs, and use the explicit VLC
demux_sys_t pointer in preflight. Latest linked build and real container
playback still require verification.

The HDR10+ experimental Vulkan output is now selectable in the shared
playback output panel even when the ordinary native-HDR policy would
hide Vulkan. The controller and UI share the same default-off target
capability query. Auto, production, other HDR formats and unavailable
Vulkan remain unchanged. A characterized target is only an admission
condition; native swapchain validation and fallback remain authoritative.
HLG-only HDR displays remain eligible for explicitly requested conversion;
the current nativeHdrPreferred value describes source/display compatibility,
not a user-forced SDR preference. Corrected the missing unified-diff prefix
in the previous MP4 change and validated every hunk's line syntax/counts.


HDR10+ spatial coordinates: full mirrored crops and rotation are retained
for final presentation; physically flipped planes are fetched in logical
source orientation before local masks. Nonzero plane shifts, unknown RGB
range transforms, ICC profiles and custom frame LUTs are excluded from the
spatial pass rather than applying a second unverified color transform.
The ordinary libplacebo renderer remains the fallback. An asymmetric GPU
fixture verifies flipped source rows and the authored local window while
preserving the final crop/rotation. No claim of device-level certification.

Paired fragment indexing now bypasses VLC's legacy Smooth offset heuristic:
explicit trun offsets resolve relative to the traf base, implicit offsets
advance from the previous run, and all additions are checked for overflow.
Unknown tracks and allocation failure fail closed in paired fragments,
not ordinary MP4. Validate signed composition offset arithmetic before
sample delivery. New host boundary tests cover positive/negative explicit
offsets, implicit continuation, unsigned offset wrap and signed PTS wrap.

Before the first fragmented sample is delivered, validate the entire
physical BL mdat run index (including shared EL and unrelated auxiliary
tracks), checking exact payload bounds and sorted nonoverlapping byte
intervals. Separate EL ranges remain independently validated. Empty
auxiliary runs do not claim bytes. The one-time check is reset with the
fragment context and shared by seekable and buffered paths. This strict
admission deliberately rejects aliased runs and layouts with multiple
physical mdats for a single moof, rather than silently truncating them.

Adjacent BL/EL carriage now admits inert top-level free/skip boxes in
all three gaps (BL moof to mdat, BL mdat to EL moof, EL moof to mdat).
Each pair shares a 16 MiB / 64 padding-box scan budget in addition to the
64 MiB combined forward-only payload cap. No timed or unknown box is
skipped; the EL moof must still be the next media fragment, sequence and
track checks remain exact. Seekable lookahead restores the original
position; forward-only reads use the actual mdat header positions.
Host tests cover exact padding budgets and malformed lengths. Arbitrary
intervening media fragments and multi-mdat layouts remain unsupported.

Fragment seeking prefers the selected paired BL over selected audio.
For this track, sidx must reference the BL itself; hidden EL indexes are
never used as presentation seek destinations. A loaded EL-only moof is
rejected before the existing context is retired, permitting the existing
caller to restore its previous position. Ordinary unpaired seek policy
is retained. Actual seek behavior requires container/device validation.

Seekable paired fragments now support up to 64 consecutive physical mdat
boxes per BL or separate EL moof. Scan header extents without downloading
payloads; each indexed run and delivered sample must fit a single physical
payload, never a header or gap. Binary-search range checks and host tests
cover boundaries and overflow. Ordinary MP4 is unchanged. Forward-only
multi-mdat input, interleaved timed moofs and padding between successive
mdats remain outside this extension; the existing 64 MiB buffered route
is unchanged. Latest linked build and real media fixtures remain pending.

Forward-only paired carriage now buffers up to 64 consecutive mdats per
layer in separate owned blocks. A single checked 64 MiB total payload
budget covers both BL and EL; no concatenation or header bytes are copied.
Binary-search cache lookup uses actual physical extents. Partial acquisition
is released on any failure, and all owned blocks are released at reset or
fragment completion. Added cumulative-budget boundary tests. This needs
one next-box header lookahead after the last mdat, so a live stream can wait
for the next header; padding between successive mdats and timed moofs
between layers remain unsupported. Latest linked build and real streaming
fixtures remain to be verified.

Paired fragment preflight now computes the greatest referenced byte end
using exact checked trun offsets, before reading any mdat. Both seekable
and forward-only scanners stop once the last required payload is covered;
no extra next-box header peek is needed after the last EL/shared mdat.
Inert free/skip padding between required mdats is permitted within existing
bounded budgets. Any timed or unknown box before required bytes, backward
payload reference, overflow or invalid header fails closed. Extra unused
mdats between a separate BL and EL are not admitted. Host tests cover
required-end arithmetic. Real container integration remains to be verified.

The Linux native contract now also compiles and executes the actual staged
MP4 demux translation unit with deterministic stream/allocator shims and
linker section garbage collection. The fixture checks exact preflight,
seekable and forward-only scans with inter-mdat padding, zero trailing
header reads, cache bytes, overlap rejection and partial read cleanup.
This exercises real demux functions, not a full VLC player or encoded
Dolby Vision media; CI results remain pending.

Paired fragment preflight now rejects duplicate traf track IDs across
all tracks (including auxiliary audio/subtitle tracks), repeated tfhd,
tfdt and mfhd headers, and more than 256 trafs. VLC has one shared set
of default sample values per track, so accepting repeated trafs with
potentially different defaults would be ambiguous. This is a conservative
restriction on the experimental paired route, not a change to ordinary MP4.
Actual staged demux sanitizer regressions exercise duplicate identities
and clock anchors as well as a valid single-header layout.

The repository-owned linked ARM64 job now records a native-link receipt
with source hash, checked-out commit and SHA-256 of both libraries after
successful linkage, before Gradle packaging. If packaging fails, the
receipt and native libraries are retained as a distinct non-AAR artifact.
The job still fails normally; the AAR success condition is unchanged.
Gradle cache writes are enabled for this restricted linked job, scoped
by GitHub's PR cache rules, to reduce repeated Maven downloads. The older
97b2321 build linked both libraries but all four packaging attempts hit
HTTP 429 from Maven Central (job 140463618096).

Player integration audit: VLC's output selector now uses its own source
metadata and Dolby Vision base layer exactly as its controller does;
MPV's cached runtime HDR detection cannot alter VLC choices. On failure
of the explicit HDR10+ target, re-evaluate native HDR10+/HDR10 support on
the attached display before rebuilding the Android/GL instance. An HLG-only
or SDR display needs SDR tone mapping on that fallback, not stale native
HDR preference. Added JVM policy tests. Latest CI and display transition
playback remain to be verified.

Explicit characterized HDR10+ output now fails closed if the actual frame
has no complete metadata, incompatible geometry/LUTs, or failed spatial
mapping. It cannot silently present global projection as successful
explicit spatial HDR. SDR retains its documented global fallback. Explicit
HDR admission requires the HDR10+ option and excludes the competing DOVI
route. Failed experimental AHB frames clear to black, remain counted as
dropped, and the existing progress watchdog can trigger Android fallback
on persistent failures. GPU regressions exercise mapped, global and reject
paths. Latest CI and real device playback remain pending.

### Independent reference and AFM3 transport validation

GPU CI generates 256 HDR10+ and 256 FEL pixel vectors using standalone Python math, not VLC/libplacebo. Actual authored-curve and FEL reconstruction GPU paths report maximum and RMS normalized PQ error. Limits are engineering tolerances (0.0025 HDR10+ / 0.001 FEL), not certification thresholds. Existing tests cover local masks, grids, saturation, P010 ranges and decoder/metadata contracts. These are synthetic vectors, not licensed Dolby reference output or certified HDR10+ vectors. Hardware AHB import, vendor decoding, proprietary resampling, HDMI signal and optical output remain unverified.

The native AFM3 encoder and ACK validator are shared by the actual demux and host sanitizer socket test. Android instrumentation exercises the real LocalServerSocket receiver, same fixed wire header, fragmented writes, ACK, repeated identical targets, stale IDs and session retirement. Run on a test installation with `adb shell am instrument -w -e vlcMetadataSocket true <test-package>/com.ambient.tv.ui.player.PlaybackAuditInstrumentation`. The host socket test does not execute the full VLC control queue; the Android test simulates the native producer using verified wire bytes. A linked AAR and real tagged-seek playback fixture remain necessary for end-to-end coverage. No certification claim or production enablement.

### Actual tagged seek integration audit

The standalone `native/vlc-aar/seek-audit` APK builds against the freshly linked experimental arm64 AAR without unrelated Ambient dependencies. CI prepares it and a generated seekable fMP4 with indexed fragments and nested emsg. `bash scripts/run_vlc_seek_e2e.sh` builds the current checkout, verifies receipt and exact packaged ELF bytes, installs only the separate debug audit package on an attached arm64 Android device and requires real native epoch and emsg delivery for two completed seeks at the same target plus superseded requests. The CI runner prepares artifacts; an attached device is required to execute the APK. No device pass is implied by packaging.

External official reference inputs and golden-frame intake: see [official reference validation](OFFICIAL_REFERENCE.md). The offline GPU runner exercises the same spatial and FEL render paths on supplier-provided frames, validates SHA-256, and requires both format cases. Public DVB material and Dolby profiles 5/8 are not substitutes for licensed FEL golden reference output.

The broad FEL synthetic reference exposed an FP32 cancellation issue near a gamut edge (previous HDR10+ ramp passed at max PQ error 0.00048977). The RPU-domain matrix/PQ stage now evaluates the ST 2084 EOTF using a stable 1-exp(t) form and exact rational constants before the HPE matrix. Legacy normalized-input conversion remains unchanged. A separate local float32 model predicts a reduction in worst error from approximately 0.00183 to 0.000023; the real GPU gate retains the original 0.001 limit and reports the worst pixel even on failure. The generated MP4 fixture's sidx and track clocks both use 1000 Hz, and ffprobe must report the complete 12-second duration.

The linked CI now also builds an isolated x86_64 AAR and the matching audit APK, checks exact packaged native library bytes, and runs the actual JNI/demux/socket test on a hardware-accelerated Android API-35 emulator. ARM64 remains a separate build and artifact for physical-device execution. A missing KVM device, failed emulator boot, native build or missing native metadata fails the job rather than reporting a skip/pass.
