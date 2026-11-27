# Experimental MediaCodec → Vulkan import

**Opt-in test build only.** The production AAR and the production Vulkan
copy-back path are unchanged. The experiment is restricted to opaque
AImageReader BT.709 SDR or explicit BT.2020 HDR10/HLG on Android API 34+
with a matching driver-reported matrix, range and actual AImage dataspace,
one graphics queue, and no CPU picture filters. HDR10+ with validated single-window dynamic metadata is experimental;
Dolby Vision, other HDR10+ payloads, explicit VkFormat, protected images and unsupported extensions/features
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
- Opaque AHB dedicated import, driver YCbCr conversion, foreign queue acquire
  and release, producer sync-fd import, conservative VLC picture lease.
  AHardwareBuffer references do not substitute for the decoder picture lease.
  The decoder requests GPU_SAMPLED_IMAGE even for unrotated output. Stable
  AHardwareBuffer IDs prevent aliasing across in-flight slots. All discarded
  pictures retain their original producer fence in the VLC-owned context;
  its destructor transfers it to AImage_deleteAsync when no consumer fence exists.
- Three bounded reusable slots, command buffers, device-local RGB targets,
  immutable sampler, descriptors and pipeline. Exact buffer identity reuses
  the import. Distinct buffers with identical driver format/conversion and
  output geometry reuse the actual sampler and pipeline, replacing only the
  imported image/view and descriptor binding. Other formats rebuild the pass.
  A shared VkPipelineCache also permits driver compilation reuse across slots.
- GPU-only conversion and asynchronous libplacebo semaphore handoff. Decoder
  picture completion and consumer hold/reclaim completion are distinct;
  no per-frame CPU fence wait or device-wide idle. On terminal device loss
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
  input alone cannot prove decoder bit depth. HDR10+ obtains per-buffer T.35 metadata from MediaCodec, accepts only the
  representable single-window subset, binds it to the exact AImage timestamp,
  and rejects missing/mismatched/unsupported metadata when HDR10+ is requested.
  The source ST 2086 units and normalized HDR10+ luminance are converted to
  libplacebo units. Dolby Vision is rejected using native format flags as well
  as app classification; opaque converted RGB cannot safely carry raw RPU reshape.

## Validation

`bash scripts/test_vlc_ahb_import.sh` runs host ASan/UBSan regressions,
including format identity and device-loss ownership, GLSL compilation,
four Android ABI owner syntax/link checks, pinned libplacebo API and
patched display/instance/MediaCodec translation unit checks when the source paths are
provided. The CI stages the exact patches twice and checks the isolated build
hashes. Native contract job 140316567169 in run 45941465294 passed all
four ABI checks before the latest decoder/HDR/recovery changes. The full
linked build was superseded by subsequent commits; no linked artifact has
been confirmed yet.

The experimental workflow also schedules a full ARM64 VLC/JNI AAR build
after the native contract checks. That linked artifact is not a GPU execution
test. No hardware performance or correctness claim is made yet.

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
  driver hangs. Full process isolation would be needed for hard guarantees.
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
