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
Only the selected shader is compiled. No PNG textures, LUTs, shader pack archives,
precompiled variants or additional native libraries are packaged. The APK footprint
must be measured after building, but source cost is tiny.

New effects have bounded strength steps 50/75/100/125/150 percent. Shader selection
follows global/system settings unless a game override is chosen. Everything is stored
in GameSettings, so the existing backup/TV-sync machinery handles it. Default is
unchanged (Sharp); no shaders are downloaded automatically.

## Pipeline and performance

- GLES: one-pass effects, with ES2-compatible code, no temporal history buffers or LUTs.
- Vulkan core + GLES compositor: retained optional FP16 bridge and a required PQ/HLG
  to SDR prepass *before* the picture effect. LibretroDroid patch 0022 composes these.
  If a picture shader fails to compile, fallback must retain the color conversion.
- Native Vulkan WSI fullscreen is still only Pixel/Nearest. All other effects run
  in the GLES compositor; there is **no native librashader renderer yet**.
- CI validates every fragment as GLSL ES 100 and checks the 16 KiB source budget.
  Compilation on glslang is not a guarantee of speed or correctness on hardware.

## Required hardware verification

Test Shield TV, Philips TV and Android phone; GLES2/3, 720p/1080p/4K,
integer/fractional scaling, 60 fps and GPU memory, background/restore,
Back/focus, per-game settings and backup/TV-sync. For HDR-capable Vulkan cores
test correctly negotiated HDR10 input, tone mapping and safe GLES fallbacks.

## Future full RetroArch preset support

Original RetroArch GLSL and Slang packs can require multi-pass chains, external
textures, parameters, temporal history and a dedicated Vulkan runtime such as
librashader. They **cannot safely be executed by feeding presets to the current
custom GLES shader interface**. Native Vulkan WSI shader parity and a signed
optional pack/runtime installer are unfinished separate projects.

For a compact APK, any future authentic shader pack should be optional,
on-demand and removable with a pinned/signed manifest, per-file hashes,
licenses, strict extraction limits and per-device compatibility rules.
