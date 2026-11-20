# Additional libretro systems (PR #406 follow-up)

This extension is **based on** `feat/gamepad-controller` (PR #406). It does not depend on RomM.
The baseline 26 cores in `cores/cores.json` stay pinned and unchanged. New cores are initially
installed on user request from the public Libretro buildbot and validated against the device's ELF
page size. **A buildbot listing is not proof that a core runs on a given Android TV.**

## Added systems and cores

| System | File detection | Core(s) |
|---|---|---|
| Nintendo DS / DSi | .nds, .dsi, .ids | melonDS DS, NooDS |
| Nintendo 3DS | .3ds, .3dsx, .cci, .cxi | Citra 2018, Panda3DS (experimental) |
| Nintendo GameCube | .gcm, .tgc, .dol, .iso (verified magic), folder for .rvz | Dolphin |
| Nintendo Wii | .wbfs, .ciso, .wad, .iso (verified magic), folder for .rvz | Dolphin |
| Nintendo Wii U | .wua, .wud, .wux, .rpx | Cemu (experimental; Vulkan only) |
| Sony PSP | .cso, .zso, or folder for .iso/.pbp/.chd | PPSSPP |
| Sony PS2 | folder for .iso/.chd/.cue | ARMSX2 (experimental) |
| Sega Dreamcast | .gdi (tracks copied), .cdi; folder for .chd/.cue | Flycast |
| Sega Saturn | .ccd, .mds, or folder for .cue/.chd/.iso/.m3u | Beetle Saturn, Yaba Sanshiro (legacy), Yabause, Ymir (experimental) |
| Commodore 64 | .d64, .d71, .d81, .g64, .t64, .tap, .prg, .crt, ... | VICE x64sc, x64, xscpu64 |
| Commodore 128, VIC-20, Plus/4, PET, CBM-II | same shared extensions; **folder name** distinguishes machine | VICE x128, xvic, xplus4, xpet, xcbm2/xcbm5x0 |
| Atari 5200 / 7800 | .a52 / .a78 | a5200 / ProSystem |
| Sega 32X | .32x | PicoDrive (already available for Mega Drive) |

Examples of folders for ambiguous formats: `Games/PSP/Game.iso`,
`Games/Saturn/Disc.cue`, `Games/C128/Disk.d64`, `Games/WiiU/Game.wua`.
An `.iso` with the GameCube or Wii disc signature wins over a misleading folder name.
Do not guess whether arbitrary `.iso`, `.chd`, `.cue` or `.m3u` content belongs to a platform;
the wrong core can crash or corrupt its own saved data. Folder names are a fallback, not a scraper.

## Rendering and features

* **Vulkan hardware contexts:** PPSSPP, Dolphin, Flycast, ARMSX2 and experimental Cemu;
  preference is applied to the core plus LibretroDroid's `preferVulkan` path. Vulkan failure
  retries once with OpenGL ES **only** where a non-Vulkan backend exists.
* **Cemu** requires Vulkan; **Yaba Sanshiro** uses OpenGL ES 3.0 and is not advertised as a
  Vulkan hardware-render core. **VICE, Beetle Saturn, Yabause, Ymir** produce software frames.
* Not every core can serialize a game state, rewind or work in Ambient TV's local netplay.
  Core-declared flags disable controls that cannot be supported (e.g. Cemu state save/load,
  cheats, achievements, netplay). Many of the newest systems have not been tested on a real TV.
* The program's own Vulkan presentation and librashader shaders are a **separate follow-up**;
  using a Vulkan core does not automatically provide Vulkan output for software cores.
* Wii Remote motion, infrared pointer, 3DS touch screen, DS dual screens and Wii U GamePad
  screen/controls need dedicated mappings and UI; a generic RetroPad is not a substitute.

## Core support files and BIOS

System files are **not shipped with the APK**. Use the existing file browser's
"Take over game data" folder action to import support files under the libretro system directory.

* **PPSSPP:** put PPSSPP assets/fonts at `PPSSPP/` in the system directory;
  without them menus and some games can malfunction.
* **Dolphin:** requires `dolphin-emu/Sys/` for game databases and compatibility settings.
* **ARMSX2:** needs PS2 BIOS files under `pcsx2/bios/`, plus `pcsx2/resources/`.
* **Ymir:** requires one known Saturn BIOS (see the Games settings status).
* **Nintendo DSi:** needs its appropriate Nintendo firmware/NAND for DSi-specific features.
* **Cemu:** Wii U content may require title keys and a console-derived firmware setup.
  There is no bundled BIOS, key or ROM content.

## Distribution and verification

These additions are **buildbot-first** until dedicated pinned recipes exist. Do not add untested
commit hashes to `cores/cores.json`: the Cores workflow builds every listed core for four ABIs.
Own signed core builds and 16 KB ELF validation must be tested per new core before promotion.
A user's consent, content/core license and the app's existing ELF checks remain mandatory.

Manual tests still needed: real ARM32/ARM64 TV and phone, 4 KB/16 KB page-size systems,
controller hotplug, source files on local/USB/SMB/NFS/WebDAV/SFTP, multi-file GDI/CUE/M3U,
core option persistence, input mapping and Vulkan fallback. Test Nintendo DS touch/second screen,
Wii Remote, Cemu GamePad and ARM PS2 separately; they are **not validated as playable** here.

Sources:
- https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/
- https://docs.libretro.com/library/ppsspp/
- https://docs.libretro.com/library/vice/
- https://docs.libretro.com/library/yabasanshiro/
- https://docs.libretro.com/library/dolphin/
- https://docs.libretro.com/library/armsx2/
