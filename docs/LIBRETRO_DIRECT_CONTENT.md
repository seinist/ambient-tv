# Direct ROM access without a full file copy

Based on PR #406 (RomM remains postponed).

## Why

The existing GameRomPreparer copies every game from device/USB/NAS to the app's
4 GiB LRU cache and fully unpacks archives. GameCube/Wii/Wii U/PS2/PSP images
can exceed the cache, and creating a second copy wastes local storage. It can
also make a game appear to hang during the initial download.

## Implementation

* **Direct disc path** for a selected core whose libretro core-info
  declares \`needs_fullpath=true\`, and for an original seekable unarchived file (including Dreamcast GDI with up to eight tracks).
  Initial candidates: Dolphin, PPSSPP, Cemu, ARMSX2, Flycast.
* **Local/USB SAF:** reuse the provider's read-only
  \`ContentResolver.openFileDescriptor("r")\`, checking it supports \`lseek\`.
* **SMB/NFS/WebDAV/SFTP:** one \`StorageManager.openProxyFileDescriptor\`
  backed by the existing \`FileAccess.open\` seekable readers, with an isolated
  I/O callback thread and 4 x 256 KiB recycled LRU windows, plus one lazy
  256 KiB scratch block for short reads (1.25 MiB maximum ROM buffers per proxy).
  Full reads write directly into the cached window. No large ROM-sized byte array
  or persistent ROM cache is created. This uses Android's kernel FUSE bridge,
  supported from API 26 (Ambient TV min API 29).
* **LibretroDroid:** the pinned Argosy fork already accepts
  \`GLRetroViewData.gameVirtualFiles\` / \`VirtualFile\` and implements a
  libretro VFS mapping file descriptors to paths. The native core must use this
  interface to benefit from direct reads; some cores use native path I/O instead.
* **GDI multi-track:** original descriptor plus each referenced track are opened as read-only
  virtual descriptors. The core sees their real filenames in the same virtual folder.
* **File type detection:** Wii/GameCube ISO magic is read from only 32 bytes
  of the source. Shared extensions fall back to their known parent-folder name.
* **Fallback:** when direct access fails, the Game Player error page can
  explicitly retry with a local copy. This is **not automatic**. The old cache
  path remains available for unsupported cores, archives, CUE/GDI/M3U track
  groups, or non-seekable providers.
* **Storage guard:** no fallback copy is attempted when its original file
  exceeds 4 GiB or there is less than 128 MiB of free space left afterwards.
  Saved games and SRAM live outside the ROM cache.

## Limitations and follow-up

* Not every \`needs_fullpath\` core actually honors the libretro VFS. A core
  calling \`fopen\` on its synthetic VFS path can reject the mount; use the
  explicit cache fallback if space permits. Validate each core on a real Android
  TV before treating it as fully supported.
* Multi-file Dreamcast GDI is mounted using named virtual descriptors for each track;
  a disc with more than eight tracks or unsafe track names still uses the original local
  cache. Other M3U/CUE multi-disc and multi-track games still use the local cache.
* RetroAchievements hashing and LAN netplay identity currently read a local
  file. They are not attempted for a direct-only session until incremental
  hashing without downloading the full ROM has been implemented.
* Seek/readahead behavior should be checked over Wi-Fi and WireGuard, including
  network interruptions, disconnection/WOL reconnection, very large 64-bit file
  offsets and cancellation during playback.
* The native Libretro VFS v3 \`stat\` size parameter is limited to 32 bits;
  cores should use the 64-bit \`size\` method for very large discs.
* The core's own emulator RAM, VRAM, shader caches and save states still count
  against device resources; skipping a ROM copy is **not** a guarantee that the
  emulation fits in limited system memory.

References:
- https://developer.android.com/reference/android/os/storage/StorageManager#openProxyFileDescriptor(int,android.os.ProxyFileDescriptorCallback,android.os.Handler)
- https://docs.libretro.com/development/cores/developing-cores/
- https://github.com/libretro/libretro-core-info/blob/master/dolphin_libretro.info
- https://github.com/libretro/libretro-core-info/blob/master/ppsspp_libretro.info
- https://github.com/libretro/libretro-core-info/blob/master/cemu_libretro.info
- https://github.com/libretro/libretro-super/blob/master/dist/info/armsx2_libretro.info
