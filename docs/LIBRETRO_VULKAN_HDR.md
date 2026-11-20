# Libretro Vulkan and HDR output contract

**Scope:** Ambient TV games player, PR #406. RomM is deferred. The current playback path is
a libretro hardware Vulkan core followed by an AHardwareBuffer → GLES compositor. It is
**not** an HDR Vulkan swapchain and must never be advertised as HDR passthrough.

## Implemented and observable

- Core Vulkan selection with an OpenGL ES retry when Vulkan startup fails.
- Explicit PQ/HLG *input* → SDR conversion through the GLES shader, disabled by default.
- Optional FP16 Vulkan → GLES frame transport for explicitly selected HDR input; format and
  allocation checks; RGBA8 fallback for unavailable or unimportable FP16 buffers.
- Runtime bridge status via pinned-fork patch `0005-vulkan-frame-status.patch`:
  `LibretroDroid.getVulkanFrameStatus()` uses an atomic native snapshot and reports:
  - bit 0: Vulkan AHardwareBuffer actually allocated;
  - bit 1: the buffer is FP16 (otherwise RGBA8);
  - bit 2: the session requested FP16;
  - bit 3: FP16 failed and the session remains on RGBA8.
- The player samples the status at most once per 120 rendered-frame events, and displays the
  observed backend/precision, rather than equating a requested Vulkan setting with success.
  Zero means no Vulkan buffer has been observed. No frame-by-frame JVM polling.
- Tests for the native status decoding, SDR defaults, manual shader selection and fallback.

## Native HDR bring-up – independent PR after #406

This follow-up introduces the **first, fail-safe HDR negotiation stage**, not a live
HDR presentation backend:

- `0006-hdr-source-format-guard.patch` only accepts the software formats the current
  GLES upload and screenshot paths truly understand: 0RGB1555, XRGB8888 and RGB565.
  Newer 10-bit SDR, 10-bit PQ and scRGB FP16 core inputs are **rejected**, so a core
  can select a supported SDR format instead of the frontend treating bytes as the
  wrong pixel layout. The experimental GET_HDR_METADATA / GET_DISPLAY_METADATA
  requests are explicitly refused until the new source and output contract exists.
- `GameHdrDisplayProbe` reads HDR10/HLG/HDR10+/Dolby Vision support from the
  **display attached to the Android UI context** (on API 34+ the actual active
  display mode). Unknown/disconnected displays remain SDR/unknown. This is
  **display capability**, not proof of an active HDR output signal.
- `0007-real-vulkan-surface-formats-probe.patch` exports
  `LibretroDroid.probeVulkanSurfaceFormats(Surface)`: a **read-only** Vulkan
  WSI query for a caller-owned, separate and currently valid Android display
  Surface (not the existing AImageReader/GLES surface). It checks that the
  running core's GPU and graphics queue can present, then enumerates the exact
  `VkFormat`/`VkColorSpaceKHR` pairs: SDR, HDR10/PQ, HLG and scRGB FP16.
  The optional `VK_EXT_swapchain_colorspace` instance extension is enabled
  only when supported. Returned bit flags are decoded by
  `GameVulkanSurfaceFormats` and unit-tested.
- `GameVulkanSurfaceHost` now owns a small **real Android SurfaceView** in the
  game's AndroidView, behind the unchanged visible GLRetroView. It cannot
  steal focus or touch, probes only after the core is ready and calls JNI via
  the GLSurfaceView GL thread. Surface creation/change invalidates prior
  results; destruction clears the status and prevents late results from
  being shown. The Info panel displays WSI surface formats independently
  from Android display-HDR support.
- Native patches `0008-native-sdr-swapchain-lifecycle.patch` and
  `0009-vulkan-sdr-frame-present.patch` introduce an **optional SDR-only**
  Vulkan swapchain on the small, real Android Surface. The JNI caller passes
  an owned `ANativeWindow` reference on the emulator/GL thread. The backend
  checks present-queue compatibility, transfer-destination usage, an
  SDR/sRGB VkFormat/color-space pair, Vulkan swapchain support and image
  acquisition; it chooses FIFO and supports detach, resize and shutdown.
  When requested, the synchronized libretro Vulkan image is blitted into the
  SDR swapchain alongside the existing AHardwareBuffer→GLES path. An unknown,
  HDR-encoded, non-UNORM or unblittable core source is rejected from native
  SDR output, without taking away the normal GLES presenter. Errors permanently
  disable this diagnostic for the surface session until reattached.
- The game Info panel has an **opt-in diagnostic only** (default OFF) that
  outputs into a separate 16px SurfaceView. This tests actual queue presentation
  without obscuring video/OSD. `GameVulkanSdrStatus` distinguishes requested
  surface, created swapchain, frames actually presented and GLES fallback.
  After activating, resume gameplay so frames can be submitted, then reopen
  Info to inspect the status. The tiny surface must not be mistaken for
  full-resolution native video playback or an HDR-enabled output.
- `GameVulkanOutputPolicy` provides a testable release gate for native PQ/HLG
  output. It requires a real active presentation surface, validated
  format/colorspace pair, accepted core source format, color pipeline and output
  metadata **all at once**. The current GLES bridge never supplies those
  prerequisites; it therefore stays SDR. scRGB is not accidentally treated as PQ.
- The game's Info panel names the display HDR formats but explicitly states that
  the Vulkan HDR output is inactive. This does not change OSD focus,
  background playback, existing renderer selection or manually chosen HDR→SDR.

**Next implementation slice (NOT yet implemented):** validate the new tiny
native SDR frame presenter on real NVIDIA Shield, Philips TV and phones,
including supported and unsupported GPU drivers, repeated Surface destruction,
rotation, pause/resume, color fidelity, frame pacing and shader/OSD behavior.
Once stable, promote the preview SurfaceView to a full-screen compositor
layer with explicit activation and fallback controls, and avoid duplicate
GLES draws when possible while preserving captures and Compose OSD. The core
still negotiates against an offscreen AImageReader; **no native Vulkan HDR**
is yet advertised or activated. Implement true HDR core source formats,
output color-space selection, mastering metadata and real display-signal
verification before exposing an HDR toggle. `librashader` follows the
stabilized output path.

## Incompatible pieces that must be completed together for native HDR

1. **Core HDR handshake.** The currently pinned Argosy/libretro-common header predates
   the newer experimental `RETRO_ENVIRONMENT_GET_HDR_METADATA` (101) and
   `RETRO_ENVIRONMENT_GET_DISPLAY_METADATA` (102) API. The newer pixel formats include
   `RETRO_PIXEL_FORMAT_HDR10_2101010` (4) and `RETRO_PIXEL_FORMAT_SCRGB_RGBA16F` (5).
   Upgrade the pinned header and negotiate feature bits according to the *actual frontend*
   input surface. Do not claim that an FP16 intermediate buffer alone supports scRGB core
   input. Never accept HDR10 pixel data and interpret it as SDR.
2. **Vulkan window surface.** Today's core negotiation surface is an offscreen AImageReader
   window, not the Android display output. Use a lifecycle-managed real ANativeWindow from
   an Android SurfaceView, with correct surface loss, resize and recreation handling. Keep
   the Compose controls, input, screenshots, secondary display and pause/background behavior.
3. **Output policy.** Ask the actual Android display for HDR capabilities; enumerate
   VK_EXT_swapchain_colorspace and surface formats, and choose a *supported pair* of
   format/colorspace. Consider `VK_COLOR_SPACE_HDR10_ST2084_EXT`,
   `VK_COLOR_SPACE_HDR10_HLG_EXT` and FP16 linear output only when supported.
   Correctly signal BT.2020, transfer function, reference white, mastering metadata
   and content light levels; do not synthesize metadata or hardcode an HDR-active badge.
4. **Color pipeline.** Render core image into Vulkan intermediate(s) with sufficient
   precision. For HDR output, preserve the core's native HDR encoding or transform it
   exactly once. For SDR output, tonemap PQ/HLG → SDR using the selected curve and gamut
   transform. Do not double-decode PQ or double-apply exposure. 30-bit HDR10 and FP16
   scRGB must not use the same source decoding shader.
5. **Renderer and shader integration.** Introduce an opt-in Vulkan presentation backend
   behind existing GL presentation; reuse existing core frame synchronization and retain
   OpenGL ES fallback. Integrate `librashader` slang presets only after Vulkan present
   and frame history work: pin a source release, build for all four ABIs/16-KiB pages,
   enforce shader/pass/texture/cache limits, keep non-shader path fast and handle failed
   shader compilation without interrupting the game.
6. **Proof of correctness.** CI must compile native Vulkan and JVM tests. Run on real
   Shield/Philips TV/Android phones: HDR10/HLG capable and SDR-only displays, Vulkan
   FP16-supported and unsupported drivers, core crash/restart, wake/background,
   resolution and refresh changes, shader toggle, 4K GPU memory, and input/OSD focus.
   Confirm actual signal format using device/TV diagnostics, not just an HDR UI badge.

## Explicit release gates

- Current default: SDR, no native HDR. PQ/HLG manual decoder is experimental and
  appropriate only when the core genuinely outputs that transfer function.
- A real Vulkan HDR output setting must remain unavailable until the complete
  source-format negotiation, real window swapchain, color-space selection and Android
  HDR signal are implemented and tested. Unknown capabilities mean SDR, not assumed HDR.
- HDR brightness, FBO format, frame synchronization, color conversion and fallback must
  be verified using both unit tests and devices. A passing NDK compile is not a color
  correctness test.

References:
- [Libretro header (new HDR metadata interface)](https://github.com/libretro/RetroArch/blob/master/libretro-common/include/libretro.h)
- [Android wide-color Vulkan surfaces](https://developer.android.com/training/wide-color-gamut)
- [librashader runtimes](https://github.com/SnowflakePowered/librashader)
