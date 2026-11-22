# Game system icons

The 30 SVG files in `monochrome/` are unmodified source artwork from the
**RetroArch Assets XMB Monochrome** theme. `manifest.json` records the original
path, SHA-256 digest and generated Android resource for every imported icon.

- Upstream: <https://github.com/libretro/retroarch-assets>
- Pinned revision: `8c918e2872482139273374ef4cc6c7aff34d7585`
- Source directory: `src/xmb/monochrome/`
- License: **Creative Commons Attribution 4.0 International (CC BY 4.0)**.
  The unmodified upstream license is in `COPYING` and at
  <https://creativecommons.org/licenses/by/4.0/>.
- Credit: **RetroArch Assets contributors; XMB Monochrome theme by Kivutar,
  with contributions by baxysquare and other RetroArch Assets contributors.**

The upstream theme instructions are preserved in `UPSTREAM_README.md`.
The [upstream Systematic theme description](https://github.com/libretro/retroarch-assets/blob/8c918e2872482139273374ef4cc6c7aff34d7585/xmb/systematic/README.md)
identifies Kivutar's Monochrome as the original theme. The
[baxysquare theme archive](https://github.com/baxysquare/baxy-retroarch-themes/blob/3798875e3a592b2b5d737212be3a2d4bc9454629/README.md)
documents its Monochrome contributions. No assets from that separate archive
are imported here. The selected SVG metadata contains no additional named
creator, copyright or license notices; the original metadata remains in each
vendored SVG.

## Native vector conversion

Run from any working directory with Python 3.9 or later:

```sh
python3 scripts/import_game_system_icons.py
python3 scripts/import_game_system_icons.py --check
```

The importer uses only the Python standard library. It verifies every source
digest and preserves SVG path data, fill rules, clipping and affine transforms
as native Android `VectorDrawable` geometry. Polygon elements become closed
paths. The Android viewport is normalized to 64 × 64, including source files
with an offset viewBox or a 256 × 256 viewport. General affine transforms are
represented by nested translation, rotation and scale groups, including
reflections and shear. Ambient TV supplies the icon tint through its theme.
No bitmap tracing, embedded image, font, external resource or substitute
artwork is used in these conversions.

The source theme intentionally uses controller silhouettes for many consoles
and device silhouettes for handhelds and computers. Some related systems share
the same upstream shape; the associated system names remain visible in menus.

## Commodore 128 fallback

The pinned upstream theme contains `Commodore - 128.png`, but it does not provide
the corresponding Monochrome SVG source. `game_system_c128.xml` therefore
remains the existing **original Ambient TV vector artwork**, unchanged by this
import. It is recorded separately in the manifest and is not attributed to
RetroArch or covered by the RetroArch CC BY notice.

## Attribution in the application

The About settings include an offline icon attribution dialog with the source
revision, imported file list, conversion notice and complete license text.
Those packaged notices are `app/src/main/res/raw/game_system_icons_notice.txt`
and `app/src/main/res/raw/game_system_icons_cc_by_4_0.txt`.

This import does not change the project's separate `SYSTEM_LOGO_LICENSE.txt`
or include RetroArch's theme font. The asset license does not grant trademark
rights or imply endorsement by the depicted hardware manufacturers.
