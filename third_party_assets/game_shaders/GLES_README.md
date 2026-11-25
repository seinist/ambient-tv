# Pinned Libretro game upscalers for GLES

These are authentic algorithm sources, with their upstream defaults. They are
separate from Ambient TV's small original `GameRetroShaders` effects.

`gles-manifest.json` records the repository, immutable revision and SHA-256 of
every unmodified source/preset. The GLSL sources are from `libretro/glsl-shaders`
at `a35069864b4bf30f8c8e740d57206543fb7e745e`. Super2xSaI has no GLSL counterpart
there; its Slang source is from `libretro/slang-shaders` at
`02bf134555c4595bd6b69be89feaff923da83754`.

| Filter | GLES chain | License in upstream source |
| --- | --- | --- |
| ScaleFX | Five algorithm passes, first two RGBA32F, 3x result then nearest display | MIT, Sp00kyFox |
| SABR v3.0 | One pass at viewport size | GPL-2.0-or-later, Joshua Street |
| xBR Level 2 | One pass at viewport size | MIT, Hyllian |
| xBRZ Freescale | One pass at viewport size | MIT vertex code plus GPL-3.0 algorithm, Hyllian / Zenju |
| 2xSaL | 2x algorithm result then linear display | GPL-2.0-or-later, guest(r) |
| Super2xSaI | 2x algorithm result then nearest display | GNU-GPL notice, DOSBox Team / guest(r) |
| SuperEagle | 2x algorithm result then nearest display | GNU-GPL notices, DOSBox Team / guest(r) / Derek Liauw Kie Fa |
| ScaleHQ 2x | 2x algorithm result then nearest display | GPL-2.0-or-later, guest(r) |

The full copyright and license notices remain in the original files **and** in
the generated Kotlin shader sources shipped with the app. This directory is
not an Android asset directory: it does not duplicate all originals in the APK.

Every chain first copies the actual core image to a source-sized nearest-sampled
texture. This normalizes hardware FBO padding and orientation once, keeps all
neighbour samples within the actual picture, and gives ScaleFX's final original
image lookup the same coordinates as its metric buffers. ScaleFX has seven GLES
passes including this normalization and the final display pass.

## Reproduce and verify

```sh
python3 scripts/import_game_upscalers.py
python3 scripts/import_game_upscalers.py --check --validate
```

The generator preserves the algorithm bodies and defaults, selects the upstream
GLES branches, promotes coordinate/comparison precision for 4K and RGB packing,
and adapts legacy interfaces to the native renderer. Float samplers explicitly
use high precision to retain ScaleFX metric values. xBR's `decal` sampler becomes
`Texture`, fixed deltas become constant expressions, and the three non-square
matrix products become equivalent component dot products for GLES 100. The
legacy xBR output explicitly writes opaque alpha (upstream assigns RGB only),
so Android surface composition cannot use an undefined alpha value. The
Super2xSaI port only changes its Slang interface and GLSL syntax. Unknown user
shader code and external LUTs are not loaded.

`GameUpscaleShaders` caches only selected chain configurations. In our native
renderer, a pass's `linear` flag controls sampling of its **output**, whereas
RetroArch's `filter_linearN` controls sampling of pass N's **input**. The adapter
therefore translates 2xSaL's linear final resolve to the preceding output texture.
ScaleFX's relative `PassPrev2Texture` / `PassPrev5Texture` bindings continue to
refer to the correct metrics/source when an effect or HDR conversion precedes it.

Validation links every vertex/fragment pair as GLSL ES 100 and checks every final
program through the actual native SDR-to-HDR10 wrapper. Native EGL regression
tests exercise rendering and hot switching separately. Device performance and
visual quality on Android GPUs still require device verification.
