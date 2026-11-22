# Ambient TV — Libretro native Vulkan SDR/HDR device acceptance

This is a **release gate**, not a record of tests already passed. PR #427 must
stay a Draft until each applicable gate is verifiably satisfied on the exact
commit proposed for merge. A successful Gradle/NDK build alone cannot validate
Android HDR signaling or Vulkan GPU synchronization.

## Hardware matrix

Record exact device/build, Android version, display connection, HDR modes,
Vulkan driver name/version, core/build and ROM/test-pattern provenance:

| Device type | Android / ABI | Output | Required |
| --- | --- | --- | --- |
| NVIDIA Shield Android TV | arm64 | HDR10 capable TV, SDR mode | Yes |
| Philips Android/Google TV | arm64 | HDR10/HLG modes, SDR mode | Yes |
| Android phone | arm64 | HDR capable and SDR-only screen, if available | Yes |
| Other Android ABI | armeabi-v7a, x86, x86_64 | NDK build/16-KiB page test | Build coverage |

Also test a Vulkan driver without AHardwareBuffer FP16 or the selected HDR
WSI colorspace. The feature must stay on GLES or fall back silently with a
correctly explained diagnostic. Never use a UI badge as proof that the TV
has changed its HDMI EOTF.

## Test protocol (all required unless marked nonapplicable with a reason)

1. **Baseline:** With native diagnostic OFF, SDR core image, OSD, focus,
   DPAD/keyboard/controller input, touch-targeting for DS, screenshots,
   save states and background/exit behavior match the pre-#427 release.
   No native swapchain is allocated during normal playback.
2. **Native SDR probe:** Enable the small WSI diagnostic, resume a compatible
   Vulkan game, then reopen Info. It must report PRESENTING only after
   `vkQueuePresentKHR` actually succeeds. Force unsupported queue, format
   and no-Window cases: no fake active status, GLES retained.
3. **SDR fullscreen:** Only after a real small-window frame is confirmed,
   enable the per-session fullscreen diagnostic and resume. Verify 4:3 and
   16:9 aspect, letterbox, crop/rotation/integer-scaling rules, frame pacing,
   touch input and OSD buttons above the native video layer. Test opening
   the right panel, Settings/back/exit and Home navigation during playback.
4. **HDR10 PQ output:** Use a core/content pair **independently verified**
   to produce PQ-encoded 10-bit Rec.2020 Vulkan images. Verify A2R10G10B10 or
   A2B10G10R10 source format, Vulkan PQ surface format and actual display
   HDR10 mode (panel/HDMI status, not just Ambient TV badge). Compare
   calibrated grayscale, saturation, gradients, near-black/peak highlights
   and screenshots against a trustworthy HDR reference. Record any content
   MaxCLL/MaxFALL and mastering metadata from its actual source.
5. **HLG output:** Repeat with independently verified 10-bit Rec.2020 HLG
   encoded content, a real HLG WSI format and the TV's HLG mode; never label
   HLG as PQ because libretro HDR metadata v1 only defines PQ/scRGB output
   modes. Check midtones and highlight rendition.
6. **Incorrect source and unsupported output:** With an SDR 8-bit Vulkan
   image, selecting PQ/HLG manually must *not* produce claimed HDR frames.
   With unavailable HDR capabilities, do not offer native HDR. On failed
   image allocation, EGL import, `vkAcquireNextImageKHR`, queue submit or
   `vkQueuePresentKHR`, demote to the GLES path without crashes, leaks or
   a permanently black fullscreen Surface.
7. **Surface lifecycle:** Repeatedly background/foreground, use standby,
   rotate the phone, change TV resolution/refresh, attach/detach displays,
   recreate Activity, exit/reenter the same game and rapidly toggle the
   diagnostic. Verify no stale `ANativeWindow` refs, no GPU use-after-free,
   no lost focus and no window still covering GLES after fallback.
8. **Resource usage:** Test 4K and low-memory devices; record GPU/CPU memory,
   extra AHardwareBuffer allocations and frametime p50/p95/p99 compared with
   GLES-only. A high-performance mode must not unexpectedly render duplicate
   full-resolution surfaces in normal playback.
9. **Shaders and output parity:** The native player must preserve or
   intentionally disable with visible reason all existing picture modes
   (sharp/pixel/smooth/CRT/LCD), aspect/rotation/crop, integer scaling,
   screenshot/save-state previews, OSD overlay and secondary display. No
   silent degradation is acceptable for a production renderer.
10. **Core source/metadata:** GET_HDR_METADATA and GET_DISPLAY_METADATA
    version/size contracts must match the pinned header and actual hardware.
    Reject unsupported 10-bit/scRGB software source formats until proper
    GPU upload/conversion is implemented. Do not synthesize mastering
    primaries, peak content luminance, MaxCLL or MaxFALL. Test source
    format provenance for each HDR-capable core.
11. **CI/release:** Test latest PR HEAD against the latest `main`, successful
    native ARM64 build, Android JVM and instrumentation test compilation,
    four release ABIs, 16-KiB ELF page alignment, zero new CI regressions,
    all review threads addressed, conflicts resolved. Check shader/resource
    limits and documented dependency licenses before enabling librashader.
12. **Opt-in and backup:** Native experimental modes default OFF, are not
    silently restored or transferred by backup/TV Sync, never activate during
    netplay, and always return to the standard GLES renderer after a new
    game session unless explicitly selected again.

## Evidence required for each physical-device result

Record: PR HEAD SHA, app build fingerprint, TV/phone model, Android API and
Vulkan driver, HDR capabilities from the app, WSI format pair, core ID/version,
exact content/test pattern, observed TV HDR EOTF, pass/fail and reproducible
steps. For any failure capture scrubbed app-native logs (no tokens or personal
paths) and timestamps. Do not report a test as passed without that evidence.

## Current status (not release approved)

- Native SDR WSI and experimental manually selected 10-bit PQ/HLG
  presentation paths exist in PR #427.
- **Not verified:** real HDR signal/metadata, independently proven core
  PQ/HLG source input, complete renderer shader/rotation/filter parity,
  physical-device lifecycle and performance validation.
- `librashader` remains a separate integration and license/build validation
  requirement. RomM remains deferred.
- Until these items are resolved, do not enable production HDR, remove the
  GLES fallback or merge PR #427 as feature-complete.
