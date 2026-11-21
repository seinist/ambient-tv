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
via the manual workflow. This subset is not full RetroArch .glslp/.slangp compatibility;
librashader and original multipass assets remain tracked separately in issue #435.
