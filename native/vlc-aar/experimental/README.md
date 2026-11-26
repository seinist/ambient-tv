# Experimental MediaCodec → Vulkan import

This is an isolated source prototype, **not an enabled renderer**. It is not
part of the VLC patch glob or packaged APK. It must not be used to remove
`--no-mediacodec-dr` from the existing Vulkan playback path.

## Implemented primitives

- Opaque external-format AHardwareBuffer import into a dedicated Vulkan image
  allocation, with explicit buffer reference ownership and no CPU pixel copy.
- External-format YCbCr conversion, image view and matching sampler. Explicit
  VkFormat, protected buffers, multilayer buffers and missing features are rejected.
- Sync-file descriptor ownership, temporary binary semaphore import and
  release semaphore export. Caller owns the semaphore lifetime and actual submissions.
- Portable fd ownership regression:
  `c++ -std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined test_unique_fd.cpp -o /tmp/vlc-ahb-fd-test && /tmp/vlc-ahb-fd-test`.
  Passed on host with Clang; this does not execute Vulkan or Android imports.

- Decoder frame lease retains a producer fence for pre-submit cancellation, duplicates
  the fence for Vulkan import, and separates AImage return from Vulkan resource
  retirement. An uncertain submission does not return the decoder buffer early;
  callers must retain the lease and explicitly drain it.
- Android AImage adapter reads borrowed hardware buffer, crop and timestamp,
  validates crop bounds, and returns the image through AImage_deleteAsync.
- Host regression for the frame lease:
  `clang++ -std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined test_frame_lease.cpp -o /tmp/vlc-ahb-lease-test && /tmp/vlc-ahb-lease-test`.
  Equivalent source and assertions passed locally with ASan/UBSan; Android
  AImage/Vulkan headers and real GPU calls remain untested.

- Paired foreign-queue ownership barriers bracket fragment sampling of the
  imported image (GENERAL externally, shader-read layout internally).
- Submission creates dedicated binary producer/consumer semaphores and a fence,
  imports a duplicate producer fd, submits once, exports the consumer fence and
  polls completion without blocking the renderer. Actual renderer queue locking
  and resource retention are required at integration.
- `bash scripts/test_vlc_ahb_import.sh` runs the two host sanitizer tests and
  compiles a translation unit against each of the four Android NDK ABIs.
  The copied frame-lease source/test was executed again successfully with
  ASan/UBSan. NDK syntax checks have not run here (no NDK installed).

- GPU-only RGB sampling pass (vertex/fragment GLSL, immutable YCbCr sampler,
  descriptor set, render pass and graphics pipeline), with normalized crop.
  Regular UNORM/float RGBA output, no sRGB encoding or CPU upload.
- Pinned libplacebo 5.264.1 C bridge: same-device/single-graphics-queue RGB
  texture wrapping and explicit hold/release, RGB/full-range frame metadata.
  Requires actual conversion fence completion before handoff.
- Pinned VLC picture adapter holds the picture_t that owns the AImage,
  duplicates the producer fd with CLOEXEC and distinguishes duplication failure.
  Conservative FrameLease mode forbids early decoder return and releases the
  picture only after GPU completion. No direct AImage_deleteAsync on VLC-owned images.
- Extended shader and Android compile check. New bridge headers require the
  pinned VLC/libplacebo build headers; they are not yet part of the production
  build. Neither shader compilation nor the new Android syntax checks ran here
  (no NDK, glslc or network clone access in the local environment).

- Reusable device-local RGB targets, with dedicated image allocation, format
  checks and no host staging. Bounded generation-tagged slot pool separates
  conversion completion from libplacebo completion, prevents stale frames after
  discontinuities and quarantines uncertain submissions.
- Three portable sanitizer tests plus shader/NDK checks are now wired to a
  separate experimental GitHub Actions workflow. The equivalent slot-pool
  C++ source and assertions passed locally with ASan/UBSan; the actual NDK
  checks are still pending. The slot pool is not yet wired to VLC.

## Required integration before activation

1. Build these headers against the pinned Android NDK and supported Vulkan ABIs.
   Verify the exact external format and features from **real decoded AImage**
   buffers, not a synthetic RGB allocation. Validate the dedicated allocation,
   image and conversion using validation layers on hardware.
2. Connect to the pinned VLC Android MediaCodec/AImageReader decoder context.
   Use the picture adapter rather than the direct AImage deletion callback;
   retain a bounded set of VLC pictures until GPU completion. AHardwareBuffer
   reference ownership alone does not stop decoder reuse.
3. Enable and verify AHB import, sampler YCbCr conversion, foreign queue family
   and external semaphore fd on the **same logical device as the renderer**.
   Wire the provided acquire/release and submission primitives into the real
   command recording path; render using an immutable conversion sampler.
   Verify the external image layout and ownership contract with actual decoder
   buffers. Retain all submitted resources, including the lease, until poll succeeds.
   Never destroy an imported resource while a GPU submission references it.
4. Integrate with libplacebo 5 on its rendering device. Its pinned import API
   does not expose Android opaque external formats; an integration patch must
   connect the sampling pass and RGB handoff to display.c, allocate/recycle
   output RGB images and command buffers, configure the same logical device's
   required extensions/features and queue locks, and safely reclaim textures
   after libplacebo hold completion. Do not mix independent Vulkan devices.
   Cache pipelines and immutable sampler descriptors per supported buffer format;
   per-frame pipeline construction is not a production performance solution.
5. Preserve source crop, orientation, range, transfer, primaries and HDR metadata;
   driver-suggested YCbCr conversion alone does not establish HDR correctness.
   Start with SDR only. Maintain copy-back fallback for unsupported formats or
   devices. Benchmark against the existing copy-back path.
6. Test real decoder output, seeks, pause, surface recreation, decoder changes,
   renderer handoff, shutdown and failure recovery on Shield and phone. Confirm
   no validation errors or retained AImages before any production enablement.

References: Vulkan specification and extension descriptions
- https://docs.vulkan.org/refpages/latest/refpages/source/VK_ANDROID_external_memory_android_hardware_buffer.html
- https://docs.vulkan.org/refpages/latest/refpages/source/VkAndroidHardwareBufferFormatPropertiesANDROID.html
- https://docs.vulkan.org/refpages/latest/refpages/source/VkSamplerYcbcrConversionCreateInfo.html
- https://docs.vulkan.org/refpages/latest/refpages/source/vkImportSemaphoreFdKHR.html
- https://docs.vulkan.org/refpages/latest/refpages/source/vkGetSemaphoreFdKHR.html
- https://docs.vulkan.org/refpages/latest/refpages/source/VK_EXT_queue_family_foreign.html
- https://developer.android.com/ndk/reference/group/media
