# Ambient TV: compact game shader library

## Delivered: opt-in GLES effects without increasing the native runtime

Ambient TV keeps the eight prior game picture presets, including Sharp, Pixel, CRT, LCD,
CUT2/CUT3, bicubic and adaptive sharpening. Fifteen additional **original** single-pass
GLSL ES 100 presets are offered by five groups in the existing right-side Picture panel:

- Pixel/scaling: Sharp Bilinear, Pixel Edge Smoothing.
- CRT: Light, Geometry, RGB Aperture Mask, Glow.
- Handheld: Game Boy DMG palette, Game Boy Color grid, GBA colors, Nintendo DS LCD.
- Analog: NTSC composite color bleed, VHS soft.
- Effects: Film Grain, Deband, Color Boost.

These are **not** byte-for-byte ports of RetroArch GLSL/Slang shaders and do not
claim the pixel-accurate look of CRT-Royale, Guest Advanced or other heavyweight presets.

The sources are in a single Kotlin file with an enforced 16 KiB source limit.
Only the selected GLES shader is compiled. A GLES-only build has no native shader
runtime. Full native Vulkan builds include a pinned librashader shared library
per ABI (ARM64 stripped output: 6,665,232 bytes in CI before APK compression).
Set SKIP_LIBRASHADER_NATIVE=1 for a smaller GLES-only APK; final APK size
still needs measurement.

New effects have bounded strength steps 50/75/100/125/150 percent. Shader selection
follows global/system settings unless a game override is chosen. Everything is stored
in GameSettings, so the existing backup/TV-sync machinery handles it. Default is
unchanged (Sharp); no shaders are downloaded automatically.

## Pipeline and performance

- GLES: one-pass effects, with ES2-compatible code, no temporal history buffers or LUTs.
- Vulkan core + GLES compositor: retained optional FP16 bridge and a required PQ/HLG
  to SDR prepass *before* the picture effect. LibretroDroid patch 0022 composes these.
  If a picture shader fails to compile, fallback must retain the color conversion.
- The experimental native Vulkan **SDR** WSI now supports nine pinned, original
  small Slang presets through the Vulkan-only librashader C runtime. Raw Pixel is
  unchanged; all other shaders, signed external GLSL packs and adjusted strength
  remain on GLES. This is not a native HDR signal.
- CI validates every fragment as GLSL ES 100 and checks the 16 KiB source budget.
  Compilation on glslang is not a guarantee of speed or correctness on hardware.

## Native Vulkan Slang: experimental SDR path (PR #434)

Nine tiny original .slang/.slangp presets are bundled: crt-light, crt-geometry,
crt-aperture, crt-glow, gb-dmg, gbc-grid, ntsc, color-boost and sharp-bilinear.
They are **not** copied RetroArch presets. The Slang validator compiles both
stages to SPIR-V in CI.

The pinned Vulkan-only librashader C runtime builds for all four Android ABIs
with 16-KiB page alignment. The renderer uses the actual Vulkan device and queue,
records shader passes into a WSI color-attachment swapchain and samples the
existing Vulkan/AHardwareBuffer RGBA8 intermediate. The libretro callback supplies
VkImageViewCreateInfo, so its hidden VkImage usage and allocation extent are not
guessed. Native transitions restore the AHardwareBuffer for ordinary GLES playback.

A native Slang frame is only available when the core uses Vulkan, the user opts
into native WSI diagnostic/fullscreen, source and target are SDR, the preset is
allowlisted, and shader strength is 100%. Only an **actually presented filtered
frame** sets native status bit 6. Any failure preserves the original GLES renderer
and HDR-to-SDR conversion. Native PQ/HLG-through-Slang output remains disabled.

The full APK includes a Vulkan-only shared Rust library per ABI. For a smaller
GLES-only APK, use SKIP_LIBRASHADER_NATIVE=1. Real device tests on Shield, Philips
and Android phone are mandatory before calling this a production player.
See docs/LIBRETRO_VULKAN_HDR.md for all color/output acceptance gates.

### Final code-only checks

- A dedicated host CI job links the exact pinned librashader C API, parses all nine
  bundled `.slangp` presets (including the 2-pass CRT Glow) and asserts SDR
  color-space output. GLSL/SPIR-V syntax checks run separately.
- All four Android ABI builds verify 16-KiB ELF LOAD alignment. The size gate
  rejects a stripped runtime above 12 MiB per ABI and publishes the raw ELF
  and gzip-compressed sizes for footprint comparison. The compressed size is a
  proxy: it is **not** the measured final split/universal APK delta.
- Frame uniforms use the libretro core-reported FPS and bounded monotonic
  frame deltas instead of a fixed assumed 60 fps.
- The C++ runtime rejects symlinked preset files; the app sets native Slang only
  for its verified built-in files. `SKIP_LIBRASHADER_NATIVE=1` excludes the
  library and disables native Slang selection in the resulting app.
- These are software/CI release gates. Real Android GPU synchronization,
  OSD-layering, input, HDR television signaling, 4K latency and picture quality
  remain hardware acceptance tests and cannot be inferred from green CI.

## Required hardware verification

Test Shield TV, Philips TV and Android phone; GLES2/3, 720p/1080p/4K,
integer/fractional scaling, 60 fps and GPU memory, background/restore,
Back/focus, per-game settings and backup/TV-sync. For HDR-capable Vulkan cores
test correctly negotiated HDR10 input, tone mapping and safe GLES fallbacks.

## Future full RetroArch preset support

Original RetroArch GLSL/Slang packs can require multi-pass chains, texture LUTs,
parameters and frame history. The pinned Vulkan-only librashader runtime has
those engine capabilities, but the app currently allowlists only nine original
built-in presets, including a two-pass CRT Glow Slang presets. Signed external pack files remain a separate
GLES100 format, not arbitrary Slang. Original RetroArch preset import, parameters,
and native HDR parity are separate follow-ups.

For a compact APK, any future authentic shader pack should be optional,
on-demand and removable with a pinned/signed manifest, per-file hashes,
licenses, strict extraction limits and per-device compatibility rules.


## Signed optional packs (follow-up PR #436)

Signed, opt-in GLSL ES 100 shader packs are handled outside the APK by
GameShaderPackStore and GameShaderPackChannel. The initial optional demonstration pack
contains five original effects in shader_packs/shaders (all CC0-1.0). The base app
contains no pack textures, no GLSL release archive and no new native runtime.

- The user explicitly downloads a shader-pack release in Settings > Playback > Games.
  No download or account check is performed while opening a game.
- The publisher creates a new immutable shader-packs-* GitHub release using the
  manual game-shaders.yml workflow and the existing CORES_SIGNING_KEY private key.
  Each archive contains manifest.json, manifest.sig (ECDSA P-256) and hashed
  shaders/*.frag. Pack content, license and source provenance are reviewed first.
- Only single-pass GLSL ES 100 fragments are accepted. Imports, arbitrary
  files, nested shader archives, native binaries, LUTs and temporal history are
  deliberately not enabled in this safe format.
- Limits: 512 KiB compressed, 256 KiB summed extracted bytes, 16 KiB per
  shader, 32 presets, eight installed packs. Signature/hash verification, path
  allowlist, atomic replacement and confirmation to remove are required.
- Shader selections are serialized in the existing game Settings map and
  included in backup and TV sync. Pack files intentionally are not backed up:
  if not installed on the receiving device, the built-in shader is used
  instead and the picture panel explains the missing selected pack.
- Native Vulkan raw fullscreen cannot silently bypass pack effects. Any
  external shader runs through GLES, including the Vulkan-core bridge; selected
  PQ/HLG input retains the mandatory HDR-to-SDR conversion before the effect.

A production release still needs hardware testing and a signed package published
via the manual workflow. The native Slang runtime now exists for eight built-in
presets, but original RetroArch multipass assets and native HDR color parity
remain open in issue #435.
