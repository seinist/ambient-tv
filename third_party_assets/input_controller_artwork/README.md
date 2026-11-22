# Controller artwork

The 30 SVG files in `duotone/` are byte-for-byte original **Controllercons 2.1**
artwork by **Kieran McClung**. Their Git blob IDs and SHA-256 digests, original
paths, source bounds and corresponding native Android resources are recorded
in `manifest.json`.

- Upstream: <https://github.com/controllercons/controllercons.github.io>
- Project: <https://controllercons.github.io/>
- Pinned commit: `384ac8a1b22b6c173d4a44447df6d73ee96d0427`
- Source directory: `versions/2.1/svg/duotone/`
- Copyright: **Copyright (c) 2023 Kieran McClung**
- License: **SIL Open Font License 1.1 (OFL-1.1)**; the complete, unmodified
  original is preserved in `LICENSE.txt`.
- Pinned license:
  <https://github.com/controllercons/controllercons.github.io/blob/384ac8a1b22b6c173d4a44447df6d73ee96d0427/LICENSE.txt>

The upstream project's “License and usage” section applies OFL-1.1 to the
Controllercons collection, whose listed formats explicitly include SVG. This
import uses the individual SVG files, not the font binaries. Format conversion
and bundling are permitted by the original license; the artwork and converted
derivatives remain under that license, with the original copyright and full
license included. No Reserved Font Name is specified after the upstream
copyright statement. The original artwork has no additional embedded creator
or license metadata. The separate application code keeps its existing license.

## Reproducible native conversion

Run from any directory with Python 3.9 or newer:

```sh
python3 scripts/import_input_controller_artwork.py
python3 scripts/import_input_controller_artwork.py --check
```

The standard-library-only importer validates the original source hashes and
accepts exactly the two path layers used by the pinned source set. Unexpected
SVG elements, attributes, viewport, classes or opacity fail conversion. It
retains the complete original path strings, drawing order and nonzero fill
rule. Black source fills become white so the app can apply its theme tint;
the secondary layer retains its original 0.5 opacity. No paths are traced,
flattened or resampled. No font, bitmap, runtime SVG renderer or network fetch
is required by the application.

The original square SVG pages contain substantial empty margins around some
devices. Each native vector uses a tight proportional viewport. The pinned
visual bounds were measured with `inkscape --query-all`, rounded outward to
0.001 original SVG units and padded by 0.25 units on each edge for antialiasing.
A translation-only group moves the original paths into this viewport. Width
and height remain proportional, so controllers, single Joy-Cons and the Wii
Remote retain their natural shapes. Optional independent bounds verification
requires Inkscape:

```sh
python3 scripts/import_input_controller_artwork.py --check --verify-bounds
```

`ControllerVectorAssets.kt` is generated alongside the vectors. Its entries
contain the drawable ID and viewport's `left`, `top`, `width` and `height` in
the **original 64 × 64 source coordinate system**. Overlay anchors therefore
use `(x - left) / width` and `(y - top) / height`. The manifest also retains
the unpadded artwork bounds for auditing. Those coordinates describe an asset;
they do not identify which physical controller is connected or define its
input mapping.

## Packaged files and attribution

Only the native resources and offline notice/license are packaged in the APK.
The source SVGs, manifest and importer remain development files:

- `app/src/main/res/drawable/input_controller_*.xml`
- `app/src/main/res/raw/input_controller_artwork_notice.txt`
- `app/src/main/res/raw/input_controller_artwork_ofl_1_1.txt`

The About settings provide a controller-artwork card with source revision,
complete imported file list, conversion description and full OFL license.

Controllercons is an unofficial illustration collection. This import does
not grant trademark rights or imply endorsement by the depicted hardware
manufacturers. Ambient TV's remote, keyboard and generic fallback artwork
is separate original work, not part of this Controllercons import.
