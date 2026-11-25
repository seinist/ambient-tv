# Ambient TV game picture filters

## Built-in selection

The existing eight native/picture options and fifteen compact original GLES
presets are joined by eight authentic Libretro filter families. All appear in
**Picture > Filter > Pixel & scaling**, including software cores such as SNES.
The system defaults are unchanged. Selections use stable `GameSettings` keys,
with the existing global/system/game precedence, backup and TV sync.

| Added filter | Key | GLES algorithm and presentation |
| --- | --- | --- |
| ScaleFX | `scalefx` | Five algorithm passes; two floating-point metric targets; 3x result and nearest presentation |
| SABR v3 | `sabr` | Edge interpolation at the game viewport size |
| xBR Level 2 | `xbr-lv2` | Edge interpolation at the game viewport size |
| xBRZ Freescale | `xbrz-freescale` | Edge interpolation at the game viewport size |
| 2xSaL | `2xsal` | 2x algorithm output followed by linear presentation |
| Super 2xSaI | `super-2xsai` | 2x algorithm output followed by nearest presentation |
| SuperEagle | `super-eagle` | 2x algorithm output followed by nearest presentation |
| ScaleHQ 2x | `scalehq2x` | 2x algorithm output followed by nearest presentation |

Every new GLES chain first copies the actual game image to a tightly sized
nearest-filtered source texture. This normalizes hardware framebuffer padding,
flipping, crop and rotation before the algorithms inspect neighbouring pixels.
It also makes ScaleFX's original-image reference refer to the picture **after**
any selected effect or HDR-to-SDR conversion. ScaleFX's software-source chain
uses seven GLES passes, eight with the optional prefix. Fixed 2x filters use
three; the other single-pass algorithms use two including the source copy.
Hardware sources also receive a renderer-owned 1x content-boundary copy when the
chain does not already declare an effective original pass. This protects older
neighbourhood filters too. At most nine passes are supported; intermediate
allocation limits still apply.

These are pinned upstream algorithms, with default parameters and preserved
copyright notices. They are separate from `GameRetroShaders`, whose fifteen
compact effects remain original approximations with five intensity steps.
The eight imported algorithms have no ineffective intensity slider: a strength
saved for a previous compact effect does not change them or block native Slang.

The selected GLES configuration is cached and only the selected chain is
compiled. Upstream originals and manifests are outside APK assets; generated
Kotlin strings are bundled once. No filter download or extra native runtime is
required. This source footprint is not a measurement of final APK size.

## Source provenance and reproduction

- GLSL: `libretro/glsl-shaders`, revision
  `a35069864b4bf30f8c8e740d57206543fb7e745e`.
- Slang: `libretro/slang-shaders`, revision
  `02bf134555c4595bd6b69be89feaff923da83754`.
- Super 2xSaI has no corresponding GLSL source in that pin; its Slang interface
  is adapted to GLES 100 while retaining its algorithm and license notice.
- The complete GLES source list, hashes and adaptation notes are in
  `third_party_assets/game_shaders/`. Generate/check with
  `python3 scripts/import_game_upscalers.py --check --validate`.
- Native Slang assets, hashes and packaged license texts are under
  `app/src/main/assets/games/slang/upstream/`. Check with
  `python3 scripts/check_game_slang.py`.

The GLES adapter changes API declarations, required precision and equivalent
GLES 100 syntax, not the filter algorithms. Slang uses the official presets,
including their own final interpolation where specified. Therefore exact
pixel-for-pixel output parity across the two APIs is not claimed.

## Switching, intermediate targets and colour

Programs and intermediate framebuffers are rebuilt together **before** a new
chain is drawn. Changing filters while paused does not depend on another core
frame arriving and does not reallocate or erase the retained source image.
Intermediate passes cannot accidentally render into the window and colour the
black side bars. Window clearing resets scissor and colour-write state inherited
from a hardware core.

GLES 2 supports bounded RGBA8 multipass chains. ScaleFX's signed metric buffers
need GLES 3 floating-point render targets; these use RGBA32F with high-precision
sampling. Unsupported formats, incomplete framebuffers, shader compile errors
or excessive intermediate allocations fall back safely. HDR-to-SDR conversion
must remain present in that fallback. Each GLES intermediate chain is capped at
128 MiB; an atomic replacement can briefly retain the old and new chains together.

The renderer distinguishes two input contracts: ordinary/RetroArch GLSL chains
read the preceding pass as their main texture, whereas native CUT2/CUT3 passes
read the original colour image plus preceding edge data. A prefix changes the
effective original colour image to its output. Mixing these contracts corrupts
colours and becomes particularly visible with SDR-to-HDR output.

HDR-to-SDR conversion precedes filtering and uses nearest sampling for pixel-art
algorithms. It runs only when the native source handshake and the current frame
confirm the requested transfer; an old menu selection cannot reinterpret SDR.
SDR-to-HDR10 encoding runs only after the last picture pass. That window pass is
always opaque; intermediate alpha is preserved for classification data. Native
Slang output remains SDR-only. The actual television HDR signal, Android GPU
behaviour and visual acceptance still need device verification.

## Native Vulkan Slang

Seventeen fixed presets are available to the existing opt-in native Vulkan WSI
path: the nine compact original effects and the eight authentic upscalers.
Only Vulkan cores with an SDR source/target and an eligible selection use it.
Software cores use the normal GLES path. External signed GLSL packs, additional
GLES effects and adjusted compact-effect intensity retain GLES presentation.
The filtered Vulkan path requires an opaque swapchain: librashader clears unused
output pixels to transparent black, so an alpha-composited surface would expose
underlying layers in the side bars. The raw native path also requires opaque
composition because core alpha is not a window transparency contract. Devices
without opaque support retain GLES.

The app copies the complete fixed dependency list for a selected preset into
private storage. Native code receives no arbitrary user preset path. Assets are
verified against packaged bytes; CI checks the pinned hashes, declared passes,
shader dependencies and source budgets. Native allocation estimates also bound
large source/viewport combinations before the shader runtime allocates them.

The already integrated, pinned Vulkan-only librashader runtime is built for all
four Android ABIs with 16-KiB ELF alignment. `SKIP_LIBRASHADER_NATIVE=1` still
produces a GLES-only APK and excludes that library. Nothing in this change adds
a second native shader runtime or promotes native HDR-through-Slang support.

## Verification

- GLES validation compiles and links all actual imported programs and every
  final program wrapped by the production SDR-to-HDR10 encoder.
- Slang validation compiles both stages of every bundled source to SPIR-V.
  The pinned host librashader C API also parses all seventeen presets.
- Mesa/EGL host regressions exercise the real patched production renderer:
  paused filter switches, black borders, source retention, framebuffer failure,
  floating metrics, earlier-pass references and padded/flipped hardware frames.
- A separate colour regression compares production SDR/HDR filtering against
  a numerical BT.2020/ST2084 reference at several SDR white levels.
- Kotlin tests cover selected filter dispatch, composition with effects/HDR,
  stable settings keys, scope precedence and the serialized settings map.
- Android CI compiles the app and native library for the supported ABIs.

These host and CI gates do not measure Shield/Philips/phone performance or
validate TV HDR signalling. Device checks should include repeated paused/live
filter changes, 4:3/integer-scaled side bars, 720p/1080p/4K output, background and
resume, and the filters with and without HDR enabled.

The follow-up [gaming HDR and Vulkan audit](LIBRETRO_GAMING_HDR_AUDIT.md) covers
all 31 built-ins and five optional pack filters, source packing, HDR input/output,
Vulkan aspect and synchronization, and the remaining device validation limits.

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
via the manual workflow. The native Slang runtime now handles seventeen fixed built-in presets. Arbitrary
RetroArch preset import, external LUT/history packs and native HDR colour parity
remain separate work.
