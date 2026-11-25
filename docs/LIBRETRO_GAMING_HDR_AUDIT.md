# Gaming HDR, filter colour and Vulkan audit

This follow-up audit covers the gaming frontend and its patched LibretroDroid
renderer. It distinguishes source encoding, transport precision, picture filters
and display output. A 10-bit or FP16 image alone does not prove HDR.

## Reproduced faults and corrections

| Area | Reproduction or consequence | Correction |
| --- | --- | --- |
| Vulkan display aspect | 256×224 with initial core DAR 4:3 produced 1234×1080 in native Vulkan, versus 1440×1080 in GLES. 720×576 with DAR 16:9 similarly became 1350×1080 instead of 1920×1080. | Both presenters consume the same resolved initial/runtime/user aspect. Native viewport calculations are checked against the actual GLES `VideoLayout`. |
| Android surface transform | `currentTransform` was advertised as already applied without corresponding image/MVP/viewport rotation. | Use Identity only when supported; otherwise retain GLES. Core rotation, crop, split, custom viewport and BFI also retain GLES until the native presenter implements them. |
| Duplicate rendering | Native WSI presented while the hidden GLES chain rendered the same frame and filter again. | Skip the hidden primary GLES draw only for a confirmed successful frame on the visible fullscreen surface. A 16px probe, old status, menu, resize or failed frame cannot enable this. Return to GLES forces a redraw even without new core pixels. |
| Vulkan implicit sRGB decode | Actual GPU repro: encoded grey 128 became 55 after a source-sRGB-to-UNORM blit. The backing image format can differ from the supplied view format. | Raw compatible bit-copy to a known non-sRGB representation, followed only by explicit packing conversion. Raw WSI and Slang use that same normalized source. |
| Hardware texture padding | Twelve filters sampled a magenta unused region of a 64×64 FBO around a 32×32 grey game image. Examples included Sharp RGB 184 instead of 128 and VHS blue 255 instead of 129. | Normalize the visible source to a tight 1x target before neighbourhood filtering. Preserve existing HDR/CUT normalization and relative previous-pass references. |
| Window alpha | Seventeen catalog filters retained the unused X byte of XRGB8888 as alpha zero. | Force opacity only in the final SDR/PQ window pass. Intermediate alpha, including ScaleFX classifier data, is preserved. Both raw and filtered native WSI require opaque composition. |
| Unconfirmed HDR input | A saved PQ/HLG setting decoded ordinary SDR when the core rejected HDR or an overriding game option selected SDR. | The actual frame supplies a native transfer uniform. Decode only a matching, accepted HDR source; unknown/SDR frames bypass the conversion. Failed bridge publication retains the old frame's dimensions and transfer. |
| HDR settings conflict | A per-game colour-format override could defeat the picture panel's HDR choice. A retained Vulkan input setting also suppressed the GLES SDR-as-HDR option. | Reconcile the current game's conflicting colour-format override on an explicit input choice; preserve unrelated options and other games. Effective input conversion is off for GLES sessions. |
| GLES2 last source pixel | XRGB8888 conversion stopped before the final pixel, leaving R/B swapped there. | Convert every visible pixel, using a reusable frontend-owned upload buffer. |
| Core buffer ownership | GLES2 colour conversions and GLES3 1555 conversion modified buffers owned by the core. Reused/partially updated frames could be converted twice. | Preserve original source and padding bytes, handle pitched and unaligned rows, and reuse separate conversion storage. |
| 1555 colour expansion | White became RGB 255/251/255 because 5-bit green expanded to 62 rather than 63. | Replicate the high green bit during the 5-to-6-bit conversion on both GLES paths. |
| FP16 raw capture | `glReadPixels(RGBA, UNSIGNED_BYTE)` from a float source attachment failed and returned an unusable preview. | Sample raw source code values into a separate RGBA8 target before byte readback. This bypasses picture filters and window PQ conversion. Bound storage, preserve GL state, and keep screenshots opaque. |
| Slang source resize | AHB allocations grow; a later smaller core image failed the old source-size equality gate and disabled Slang. | Supply the shader runtime a tightly sized normalized source with truthful `SourceSize`. |
| Vulkan error completion | Fence timeouts/errors were treated as completion, allowing reset or destruction of in-flight resources. | Wait for actual completion, latch genuine failures, retain resources without an idle proof, and report the failure at the managed boundary after the core call. Never throw across a libretro C callback. |

The existing CUT2/CUT3 colour-versus-classification sampler correction and atomic
filter/target replacement remain in place. This audit extends their coverage
rather than substituting similar-looking algorithms.

### Exact previously affected filter sets

The **17 alpha cases** were Bicubic, Adaptive, Sharp Bilinear, Pixel AA, CRT
Light/Geometry/Aperture/Glow, Game Boy DMG, GBC Grid, GBA Color, DS LCD, Film
Grain, Deband, Color Boost, and the Adaptive Deblur/Vintage Warm pack shaders.

The **12 hardware-padding cases** were Sharp, CRT, LCD, Bicubic, Adaptive, CRT
Geometry, CRT Glow, NTSC Composite, VHS Soft, and the CRT Soft Bloom, PAL Color
Bleed and Adaptive Deblur pack shaders. The new authentic upscalers already
normalized their source; the renderer now also protects the older filters.

## Audited catalog and mode compatibility

The audit extracts the actual Kotlin shader strings, optional pack files and
native dispatch. It asserts the catalog/dispatch contract so a changed menu
cannot silently leave a filter outside the matrix.

| Family | Actual selections covered |
| --- | --- |
| Native, 6 | Sharp, Pixel, Smooth/CUT3, CUT2, CRT, LCD |
| Picture shaders, 2 | Bicubic, Adaptive |
| Compact GLES, 15 | Sharp Bilinear, Pixel AA, CRT Light, CRT Geometry, CRT Aperture, CRT Glow, Game Boy DMG, GBC Grid, GBA Color, DS LCD, NTSC Composite, VHS Soft, Film Grain, Deband, Color Boost |
| Imported algorithms, 8 | ScaleFX, SABR v3, xBR Level 2, xBRZ Freescale, 2xSaL, Super 2xSaI, SuperEagle, ScaleHQ 2x |
| Optional pack sources, 5 | Adaptive Deblur, CRT Soft Bloom, LCD RGB Mask, PAL Color Bleed, Vintage Warm |

All five compact-effect strengths offered by the UI are exercised. Deliberate
DMG/GBA/CRT/NTSC/Vintage colour changes are compared with **the SDR output of the
same filter**, not with an unfiltered neutral image. This prevents intentional
art styles from being incorrectly classified as HDR faults.

| Configuration | Supported behaviour and limit |
| --- | --- |
| Software or GLES core, SDR output | Normal GLES picture filtering. Software frame data stays owned by the core. |
| SDR-as-HDR10 output | The final filtered sRGB image is converted once to linear BT.709, BT.2020 and PQ. The actual 10-bit PQ EGL surface must succeed. GLES3 is required to guarantee high-precision fragment arithmetic; otherwise output stays SDR. |
| Confirmed PQ input from Vulkan | Decode and tone-map before the selected GLES filter. Acceptance requires the native HDR10 pixel-format handshake plus the current compatible 10-bit frame; bit depth or a saved option alone is insufficient. |
| Unconfirmed/stale PQ or HLG input | Bypass input conversion. No speculative HDR badge or colour reinterpretation. HLG math is tested, but no current libretro source contract enables production HLG input. |
| Native raw WSI | Requires a matching supported source/output transfer, opaque composition and compatible geometry. The HDR path remains separately evidence-gated. |
| Native Slang | Seventeen fixed presets, SDR only. HDR input/output, external GLSL packs, extra effects and non-default compact-effect intensity retain GLES. |
| Extra effect plus filter | NTSC, VHS, Color Boost and Film Grain are tested before every offered filter and strength. The single prefix is reserved for required HDR decoding when selected; these effects do not stack with that prefix. |
| ScaleFX | Authentic execution needs renderable floating-point intermediates. GLES3 uses RGBA32F metrics. GLES2 and unsupported/over-budget devices use the explicitly tested fallback; this is not counted as authentic ScaleFX execution. |
| Allocation failure | Intermediate targets are bounded at 128 MiB per chain. An ordinary SDR hardware source has an allocation-free nearest fallback if even the boundary copy cannot be allocated. Required transfer prefixes are never removed to fit a failing chain. |

The maximum GLES chain length is nine passes. A hardware boundary copy can add
one pass to an unnormalized custom chain; ScaleFX plus an additional effect still
fits. Atomic shader replacement can temporarily hold both the old and new chains.

## Vulkan source-format contract

`retro_vulkan_image.create_info` is an **image-view** description. It cannot be
used to guess the creation format of the backing image. Vulkan blits decode
sRGB source images; this is the wrong implicit operation for frontend filters
that expect encoded SDR values. See the primary specifications for
[blitting](https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdBlitImage.html)
and [raw image copies](https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdCopyImage.html).

| Source view | Normalized code-value representation |
| --- | --- |
| R8G8B8A8 UNORM or SRGB | R8G8B8A8 UNORM; direct copy to an equally sized RGBA8 bridge when possible |
| B8G8R8A8 UNORM or SRGB | B8G8R8A8 UNORM cache, followed by known packing conversion |
| A2B10G10R10 / A2R10G10B10 UNORM | Matching packed 10-bit cache; HDR transfer remains a separate contract |
| R16G16B16A16 SFLOAT | FP16 cache, retaining the code values; FP16 alone does not imply linear scRGB or HDR |

Identity and exact R/B component swaps are supported for the listed packed
formats; FP16 accepts identity. Unsupported formats/mappings/subresources are
explicitly rejected and diagnosed. The core's pending work is still retired,
and the previous correctly interpreted GLES frame is retained. Persistent
rejection currently requires changing the renderer/core; it is not presented as
successful support for that format.

The normalization cache is separately capped at 128 MiB, the device image-size
limit and 8192 pixels per axis. The common equally sized RGBA8 path needs no
additional cache or format-conversion blit. Raw WSI and Slang both read the
normalized source. Foreign-queue release includes the original transfer writes
as well as subsequent reads.

## Performance and failure boundaries

The removed duplicate GLES filter pass is a concrete reduction in work. It is
**not a claim that Vulkan is faster than GLES on the user's TV**. The shared
AHB snapshot, GL/Vulkan synchronization and `GLSurfaceView`'s framework-managed
EGL swap remain. Removing these requires a different ownership/frame-pacing
design and device profiling; skipping them without that design would break
captures, menus or fallback.

The native fullscreen gate combines UI surface generation with success of the
exact native frame. Pause, menu, resize, teardown, failed presentation, capture,
secondary output and rewind cannot leave an obsolete native success suppressing
GLES. Unsupported native geometry returns to the established GLES path.

GPU completion is a lifetime requirement, not a performance hint. A timeout
does not permit resetting a pending fence or freeing an image. Actual errors
are latched and translated after the core call; the void `wait_sync_index`
callback can return only after completion/device loss is established. A wholly
hung driver can still block this synchronous core API. No bounded recovery from
an arbitrary driver hang is claimed. The resource-retirement rule follows
[`vkDestroyImage`](https://docs.vulkan.org/refpages/latest/refpages/source/vkDestroyImage.html).

## Reproducible verification

Run `scripts/fetch_libretrodroid.sh` before the compiler checks so they use the
**fully patched current output wrapper**, not an obsolete header reconstructed
from an earlier patch. Both PR CI and the optional-pack release workflow do this.

| Check | Actual scope |
| --- | --- |
| `test_game_filter_renderer.py` | Actual patched renderer on EGL/Mesa GLES2 and GLES3; paused switches, black/opaque bars, retained sources, signed float metrics, previous-pass references and allocation/compile fallback |
| `native_game_hdr_filter_audit_test.cpp` through that runner | All 36 catalog sources, all UI strengths, independent PQ/HLG references, 100/203/400-nit white, confirmed/unconfirmed/live transfer changes, four extra effects, alpha, pitched/unaligned immutable uploads, RGB10/FP16, hardware padding/flip, finite shader output and raw captures |
| `test_game_vulkan_presentation.py` | 756 geometry cases against actual `VideoLayout`; fullscreen/fallback handoffs and extracted production frame-ingress failure paths |
| `test_game_vulkan_color.py` | Actual productive copy helper on Lavapipe: backing/view format mismatches, component swaps, packing, cache resize/reuse, source subresources and unsupported-input rejection |
| `test_game_vulkan_sync.py` | Fault injection into the production wait/submit/retire/presentation methods; no physical GPU is needed to exercise the error branches |
| `check_game_glsl.py`, `import_game_upscalers.py` | Actual GLES sources plus final opaque/PQ wrappers compile/link |
| `check_game_slang.py` | All 17 presets, 25 source files and 50 SPIR-V stages, with pinned hashes and dependency closure |
| `test_librashader_preset_api.py` | Real pinned C API/ABI and SDR preset colour-space declarations |
| `test_game_vulkan_slang.py` | Productive `VulkanSlangPipeline` and that same host runtime: every preset creates, renders and reads back two frames, with poisoned-to-black borders and shrinking source/target |
| Kotlin/Android CI | Settings scope, HDR contracts, display/transfer policy, fullscreen gates, full app/JVM compilation and all four Android native ABIs |

The Vulkan colour helper reproduced the old 128→55 fault and passed **103 GPU
pixel cases**. Four optional A2R10 conversions were correctly rejected by the
local Mesa device and are not counted as successful rendered cases. Khronos
API and synchronization validation reported no errors. Local sanitizers cover
the upload, renderer/capture and Vulkan helper/error-contract paths; local LSan
is disabled only where the traced executor cannot run it, while CI retains it.

The complete GLES matrix passed **3,393 cases: 1,564 on GLES2 and 1,829 on
GLES3**, with zero failures. Each case checks multiple fields, frames and pixels;
these are not counts of individual pixel assertions. Exactly 23 GLES2 cases
explicitly validate the ScaleFX fallback.

| Matrix phase | GLES2 | GLES3 |
| --- | ---: | ---: |
| SDR → PQ, three reference-white levels | 288 | 288 |
| Independent transfer reference | 2 | 2 |
| Actual PQ / HLG prepass | 192 / 192 | 192 / 192 |
| Unconfirmed input / live transfer changes | 72 / 144 | 72 / 144 |
| Four effects with every filter/strength | 364 | 364 |
| Unused XRGB alpha | 72 | 72 |
| Immutable pitched source upload | 3 | 1 |
| Hardware padding/crop/orientation | 0 | 108 |
| Precise input reference / RGB10 / FP16 | 2 / 144 / 0 | 4 / 144 / 144 |
| Finite final custom-shader values | 89 | 90 |
| Raw capture and foreign GL state | 0 | 12 |
| **Total** | **1,564** | **1,829** |

The capture cases include 31×17 sources, nondefault pack alignment/row lengths
and skip offsets, pack/unpack PBOs, distinct read/draw FBOs, VAO/VBOs, samplers,
active texture units and rasterizer discard. The state is restored exactly and
the next filtered frame matches the frame before capture. The finite-output
probe checks the final custom shader before clamping; it does not instrument
every internal floating-point intermediate.

The Slang chains preserve authentic upstream float/sRGB intermediate settings.
In particular, ScaleFX's metric targets remain floating point, and 2xSaL,
Super 2xSaI and ScaleHQ retain their upstream sRGB/bicubic passes. No HDR Slang
pipeline, arbitrary LUT pack support or cross-API pixel identity is inferred.

### What these tests do not establish

- PQ values are numerically checked in host RGBA8 pbuffers; this is not a
  measurement of Android's 10-bit surface, HDMI signalling or a television's EOTF.
- FP16 GLES fixtures use real RGBA16F textures in shared EGL contexts. The
  Vulkan fixtures use real regular Vulkan images. Android AHardwareBuffer import
  and WSI/SurfaceFlinger remain device integration checks.
- Lavapipe/Mesa results do not establish Mali/Adreno/Shield frame times, power
  consumption or the behaviour of every libretro core/driver version.
- Keep a device acceptance pass for 4:3/16:9 and integer scaling at 1080p/4K,
  repeated filter/HDR switches, native fullscreen/menu transitions, suspend/resume,
  screenshots, source-resolution changes and the same games under GLES/Vulkan.

For source pins, licensing, packaging and the original filter changes, see
[the game shader implementation notes](LIBRETRO_GAME_SHADERS.md).
