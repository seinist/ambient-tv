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

## Required integration before activation

1. Build these headers against the pinned Android NDK and supported Vulkan ABIs.
   Verify the exact external format and features from **real decoded AImage**
   buffers, not a synthetic RGB allocation. Validate the dedicated allocation,
   image and conversion using validation layers on hardware.
2. Connect to the pinned VLC Android MediaCodec/AImageReader decoder context.
   Hold each AImage until a release fence is delivered (or GPU completion is
   proven). AHardwareBuffer reference ownership alone does not stop decoder reuse.
3. Enable and verify AHB import, sampler YCbCr conversion, foreign queue family
   and external semaphore fd on the **same logical device as the renderer**.
   Acquire foreign ownership after the decoder fence, render using an immutable
   conversion sampler, then release ownership and export a release fence.
   Never destroy an imported resource while a GPU submission references it.
4. Integrate with libplacebo 5 on its rendering device. Its pinned import API
   does not expose Android opaque external formats; an integration patch must
   provide either an external-format sampling pass into a renderer-owned texture
   (no CPU round-trip) or genuine external-format texture support, with correct
   command-buffer and fence lifetime. Do not mix independent Vulkan devices.
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
