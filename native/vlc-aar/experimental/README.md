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
