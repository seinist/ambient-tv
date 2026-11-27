# Experimental MediaCodec → Vulkan import

**Opt-in test build only.** The production AAR and the production Vulkan
copy-back path are unchanged. The experiment is restricted to opaque
AImageReader BT.709 SDR with a matching driver-reported range and matrix,
one graphics queue, and no CPU picture filters. HDR/DV, explicit VkFormat,
protected images and unsupported extensions/features are rejected.

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

## Validation

`bash scripts/test_vlc_ahb_import.sh` runs host ASan/UBSan regressions,
including format identity and device-loss ownership, GLSL compilation,
four Android ABI owner syntax/link checks, pinned libplacebo API and
patched display/instance translation unit checks when the source paths are
provided. The CI stages the exact patches twice and checks the isolated build
hashes. Prior run 45940950741 passed the patched C contract; the subsequent
cache, recovery, presentation statistics and app changes need their own checks.

The experimental workflow also schedules a full ARM64 VLC/JNI AAR build
after the native contract checks. That linked artifact is not a GPU execution
test. No hardware performance or correctness claim is made yet.

## Required before production activation

- Complete the full linked build; verify opaque MediaCodec/AImageReader
  negotiation on the actual driver, descriptor allocation, ownership/layout
  validation, color range/crop, orientation, subtitles and rendering.
- On Shield and phone test long playback, seeks, pause, surface recreation,
  format changes, HDR rejection, restart to copy-back, device loss and close.
  Confirm no validation errors or retained AImages and measure startup,
  frame times, dropped frames, decoder backlog and memory versus copy-back.
- The watchdog requires valid counters and observable decoder progress;
  terminal driver failure or decoder starvation may need a separate native
  failure signal. Do not treat the current fallback as proof of all recovery
  scenarios.

References:
- https://docs.vulkan.org/refpages/latest/refpages/source/VK_ANDROID_external_memory_android_hardware_buffer.html
- https://docs.vulkan.org/refpages/latest/refpages/source/VkAndroidHardwareBufferFormatPropertiesANDROID.html
- https://docs.vulkan.org/refpages/latest/refpages/source/VkSamplerYcbcrConversionCreateInfo.html
- https://docs.vulkan.org/refpages/latest/refpages/source/vkImportSemaphoreFdKHR.html
- https://docs.vulkan.org/refpages/latest/refpages/source/vkGetSemaphoreFdKHR.html
- https://docs.vulkan.org/refpages/latest/refpages/source/VK_EXT_queue_family_foreign.html
- https://docs.vulkan.org/spec/latest/chapters/devsandqueues.html
- https://developer.android.com/ndk/reference/group/media
