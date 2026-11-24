# Libretro capability audit – follow-ups after PR #406

RomM is its own system (DOCUMENTATION 4.12). This document tracks **implemented code**, **real-device acceptance still needed**
and **unimplemented features**. A core appearing in the catalog does **not** establish that it runs.

## Implemented in the existing game player and follow-up branches

| Area | Implemented | What still needs validation |
|---|---|---|
| Input | RetroPad 1–4, analog sticks, triggers, game menu, controller hotkeys | Controller detection and remaps per device |
| Touch | On phones the virtual RetroPad offers D-pad, ABXY, Start/Select, L/R/L2/R2 and one or two analog sticks; per-system toggle/layout and input union with physical controllers. DS/3DS/Wii pointer touches still reach the core and default to no virtual controls. A persistent touch menu target opens the existing game OSD. | Test simultaneous multi-touch (including a physical pad), phone portrait/landscape, screen-reader accessibility, touch-to-core pointer calibration and real game compatibility |
| Files | Direct seekable single-file disc access and Dreamcast GDI via SAF/USB/SMB/NFS/WebDAV/SFTP; explicit local-copy fallback | Cores that bypass libretro VFS, network reconnection and latency |
| Save states | Runtime serialize-size check, 64 MiB cap, SRAM/state on exit, screenshots, manual and auto states | Some cores cannot serialize, or produce states bigger than the limit |
| Rewind and speed | 20-second / 64 MiB rewind, 2× fast-forward and configurable hotkeys | ROM/core/CPU performance and audio |
| Cores | 50 registered core IDs, 26 originally pinned; external buildbot fallback, page checks | Pin reproducible new cores only after ABI/license checks |
| Core updates | One preceding binary/metadata version kept; user rollback and failed-load recovery | Confirm updating/recovery on Android 14+, active-core reload edge cases |
| Achievements | Extended console ID lookup for DS, PSP, GC/Wii, Saturn, Dreamcast, Commodore, etc. | Hashing, per-core achievement capability and server verification; direct-ROM achievements need incremental reader |
| Cheat database | Extended known system-folder name candidates | Catalog and core cheat format differences |
| Picture / OSD | Per-system aspect presets (core, 1:1, 8:7, 4:3, 3:2, 16:10, 16:9), integer scaling, existing GLES shaders plus CUT2, 16-tap bicubic and 5-tap adaptive sharpening; accurate paused/running indicator tied to the actual emulation state | Measure shader cost and GLES fallback on Shield/Philips/phone, test real SNES aspect/pause rendering and TV focus for the picture/controls panels |
| Vulkan HDR → SDR (experimental) | Explicit PQ/HLG→SDR shader and optional Vulkan→GLES FP16 AHardwareBuffer transport; runtime format capability check, allocation and GLES-import fallback to RGBA8. Disabled for SDR; selecting HDR restarts the core | Core must really output encoded PQ/HLG; FP16 availability, high-quality highlights and correct colors require physical-device tests |

The virtual controller settings are stored in the existing `gameSettings` preference map, which participates in backup and companion settings synchronization. Most picture shaders still run on GLES. PR #434 additionally has nine original
built-in Slang presets on an **experimental native Vulkan SDR** WSI chain via
pinned librashader; this is not FSR or evidence of native HDR. The game still pauses when its interactive OSD is deliberately opened; the label no longer claims a paused state when the core is actually running.


See [game shaders and footprint policy](LIBRETRO_GAME_SHADERS.md) for the compact GLES
catalog, HDR prefix, device-acceptance checklist and deferred optional-pack support.


## Deep audit hardening (2026-11-21, PR #433)

The Games hardening branch introduces stable controller port assignments across hotplug,
a non-removable long-Back emergency menu path, forced rumble release, SHA-256 core
identities (including legacy state migration), strict state compatibility before
native unserialization and Android AtomicFile saves. Netplay now requires a host
approval before transferring state, uses a bounded frame window and send queue and
times out stalled connections. Archive unpacking counts actual emitted bytes, rejects
flattened name collisions and respects the 4-GiB aggregate ROM cache budget.
Core ZIP/binary limits and firmware checksum verification are mandatory. The
signed core-release workflow must observe a fully successful build matrix with
all artifacts present.

These are source-level fixes, not evidence of successful gameplay on hardware.
Netplay runs on a local network without end-to-end cryptographic peer identity;
do not expose the discovery service directly to the internet. The emergency
Back press is deliberately longer than the ordinary Back action. A core appearing
in the catalog does not imply the platform's hardware is supported. The native
Vulkan/HDR, specialized devices (Wii motion/IR, DS second screen, Wii U GamePad,
keyboard/mouse/lightgun), device EOTF and renderer parity gates below remain required.

## Outstanding features (not represented as completed)

1. **Full Libretro input abstractions:** `RETRO_DEVICE_KEYBOARD` (raw held physical keys,
   keyboard callback/text), mouse wheel/extra buttons and advanced per-core device negotiation.
   PR #441 adds up to four pointer contacts, relative mouse and lightgun polling to the pinned
   LibretroDroid frontend; their compatibility must still be tested on actual cores.
2. **System-specific controls:** PR #441 adds a separate phone touchscreen/stylus pad with
   user-selectable top/bottom/left/right/full-frame calibration (DS/3DS/Wii U default lower),
   Wii-style pointer/IR aiming via the touch surface, mouse/lightgun modes and opt-in phone
   accelerometer/gyroscope readings routed per Libretro player port. **The core must actually
   consume these Libretro input/sensor devices.** This does not emulate every proprietary
   Wiimote/Nunchuk/IR extension, camera/light-sensor, or stream Wii U GamePad/DS video to the
   handset. Real two-screen and motion compatibility requires per-core/device testing.
3. **Frame advance and Run-Ahead:** Single-frame stepping needs an explicit paused-core call;
   Run-Ahead needs measured core state serialization and extra emulation steps and must be
   off on expensive GPU cores and netplay/hardcore.
4. **Advanced graphics:** See [the native Vulkan/HDR contract](LIBRETRO_VULKAN_HDR.md) for
   the exact source-format negotiation, display swapchain and librashader release gates.
   The fork's existing multi-pass GLSL compositor can run over
   Vulkan core frames. Opt-in PQ/HLG-to-SDR conversion uses an **FP16 AHardwareBuffer** for
   Vulkan→GLES transport when the driver supports FP16 blit, allocation and GLES texture
   import. The native patch `0004-fp16-vulkan-bridge.patch` detects unsupported formats and
   falls back to RGBA8 automatically on allocation, Vulkan image or GLES import failures.
   Patch `0005-vulkan-frame-status.patch` reports the actual bridge mode to the player. SDR
   is unchanged and does not allocate FP16. Changing HDR input restarts the game so the
   buffer precision is configured before the core renders.
   
   **Still not native Vulkan HDR presentation:** the Argosy fork renders its final picture via
   GLES/Android SDR surfaces; there is no HDR-capable Vulkan swapchain, source metadata/format
   negotiation or color-space signaling. HDR cannot be detected automatically and a core may
   have clipped or quantized data before the bridge. The chosen transfer function must match
   the actual core output. Proper 10-bit/FP16 **core output** negotiation, HDR output
   metadata and color-space-aware swapchains are separate from the new experimental
   SDR-only Slang path. Vulkan hardware acceleration alone is not HDR output.
5. **Audio and timing:** Choose audio latency/resampling and video refresh matching per core,
   without interfering with Media3/VLC/MPV playback.
6. **Patch and special data types:** IPS/BPS/UPS import or overlay, libretro subsystem content,
   reliable multi-file virtual ISO/CUE/M3U sidecars, sensor/camera/microphone/MIDI permission
   management and feature selection.
7. **Large-file edge cases:** Core-specific VFS bypass, multiple open file descriptors and
   network failure; never silently copy huge ROMs to internal storage, or load multi-gigabyte
   ROM bytes to the JVM/native heap.
8. **Cross-device library:** RomM games keep firmware, in-game saves, states and the files cores write themselves
   (memory cards, NAND, PSP SAVEDATA) on the server (DOCUMENTATION 4.12). Local games still have no cross-device sync.

## Acceptance before a release

- Real Android TV hardware and phones with 4-KiB and 16-KiB page sizes.
- Gameplay from local SAF, USB and each NAS protocol including seek-heavy ISO/CHD/GDI.
- Correct state after core update, rollback, app-background, unplugged source and controller removal.
- Confirm each core's permitted features and BIOS/assets; no misleading Vulkan/rewind buttons.
- Test Vulkan FP16↔RGBA8 failover, HDR→SDR color ramps/highlights and PQ/HLG choice on
  Shield, Philips TV and phones (with unsupported drivers); watch GPU memory at 4K.
- On low-end hardware verify total app memory: proxy windows are bounded, but **emulator RAM,
  GPU textures and native allocations are not**.

Reference: https://docs.libretro.com/development/input-api/
