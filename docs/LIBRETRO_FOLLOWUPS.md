# Libretro capability audit – follow-ups after PR #406

RomM is postponed. This document tracks **implemented code**, **real-device acceptance still needed**
and **unimplemented features**. A core appearing in the catalog does **not** establish that it runs.

## Implemented in the existing game player and follow-up branches

| Area | Implemented | What still needs validation |
|---|---|---|
| Input | RetroPad 1–4, analog sticks, triggers, game menu, controller hotkeys | Controller detection and remaps per device |
| Touch | Handheld DS, 3DS, Wii, Wii U pointer events reach the fork's existing single-pointer input; a small menu target remains | Core-specific screen layouts, pointer calibration and actual game compatibility |
| Files | Direct seekable single-file disc access and Dreamcast GDI via SAF/USB/SMB/NFS/WebDAV/SFTP; explicit local-copy fallback | Cores that bypass libretro VFS, network reconnection and latency |
| Save states | Runtime serialize-size check, 64 MiB cap, SRAM/state on exit, screenshots, manual and auto states | Some cores cannot serialize, or produce states bigger than the limit |
| Rewind and speed | 20-second / 64 MiB rewind, 2× fast-forward and configurable hotkeys | ROM/core/CPU performance and audio |
| Cores | 50 registered core IDs, 26 originally pinned; external buildbot fallback, page checks | Pin reproducible new cores only after ABI/license checks |
| Core updates | One preceding binary/metadata version kept; user rollback and failed-load recovery | Confirm updating/recovery on Android 14+, active-core reload edge cases |
| Achievements | Extended console ID lookup for DS, PSP, GC/Wii, Saturn, Dreamcast, Commodore, etc. | Hashing, per-core achievement capability and server verification; direct-ROM achievements need incremental reader |
| Cheat database | Extended known system-folder name candidates | Catalog and core cheat format differences |
| Vulkan HDR → SDR (experimental) | Manual PQ/HLG decoding, luminance mapping and BT.2020→BT.709 conversion after Vulkan image transport in the GLES compositor; stored per core and default OFF | Core must actually emit encoded HDR, and 8-bit transfer limits highlight fidelity; real devices required |

## Outstanding features (not represented as completed)

1. **Full Libretro input abstractions:** Physical keyboard as `RETRO_DEVICE_KEYBOARD`,
   relative mouse, lightgun and multiple pointer contacts. The pinned Argosy fork currently
   implements joypad/analog/one pointer, not full keyboard/mouse/lightgun polling; requires
   native frontend work plus a game-focus mode separate from menu key bindings.
2. **System-specific controls:** DS dual-screen layouts and touch targeting, Wii Remote
   infrared/motion/nunchuck controls and Wii U GamePad secondary-screen input. A basic
   Android touch→pointer bridge does **not** solve those features.
3. **Frame advance and Run-Ahead:** Single-frame stepping needs an explicit paused-core call;
   Run-Ahead needs measured core state serialization and extra emulation steps and must be
   off on expensive GPU cores and netplay/hardcore.
4. **Advanced graphics:** Multi-pass GLSL shader chains are available in the fork but are not
   exposed as curated/validated options by Ambient TV. Manual HDR (PQ or HLG) to SDR conversion
   is experimental and opt-in on the GLES compositor for Vulkan hardware cores. **This is not
   native Vulkan HDR presentation**: the current Vulkan-to-GLES bridge uses an 8-bit RGBA
   AHardwareBuffer and lacks libretro HDR source-format/metadata negotiation. HDR cannot be
   detected automatically; highlights may already be clipped before conversion. It is **OFF
   by default** and must never be applied to normal SDR. Full HDR source negotiation, FP16/10-bit
   transport, PQ/HLG output colorspaces, HDR swapchain metadata and `librashader` shaders
   require native renderer work and validation on TV and handheld hardware. Vulkan hardware
   cores are not equivalent to Vulkan presentation.
5. **Audio and timing:** Choose audio latency/resampling and video refresh matching per core,
   without interfering with Media3/VLC/MPV playback.
6. **Patch and special data types:** IPS/BPS/UPS import or overlay, libretro subsystem content,
   reliable multi-file virtual ISO/CUE/M3U sidecars, sensor/camera/microphone/MIDI permission
   management and feature selection.
7. **Large-file edge cases:** Core-specific VFS bypass, multiple open file descriptors and
   network failure; never silently copy huge ROMs to internal storage, or load multi-gigabyte
   ROM bytes to the JVM/native heap.
8. **Cross-device library:** RomM/library and save-state sync deliberately postponed.

## Acceptance before a release

- Real Android TV hardware and phones with 4-KiB and 16-KiB page sizes.
- Gameplay from local SAF, USB and each NAS protocol including seek-heavy ISO/CHD/GDI.
- Correct state after core update, rollback, app-background, unplugged source and controller removal.
- Confirm each core's permitted features and BIOS/assets; no misleading Vulkan/rewind buttons.
- On low-end hardware verify total app memory: proxy windows are bounded, but **emulator RAM,
  GPU textures and native allocations are not**.

Reference: https://docs.libretro.com/development/input-api/
