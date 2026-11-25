# melonDS renderer and native content lifetime

The reported melonDS app crash had no device log or reproducible ROM/session
attached. The changes below remove concrete failure paths found in the frontend;
they do not establish which path caused that particular crash.

## Android renderer compatibility

melonDS DS 1.4.0, verified at official upstream revision
[`f394adbacb5722ee97c1b37c8064da9a25818310`](https://github.com/JesseTG/melonds-ds/tree/f394adbacb5722ee97c1b37c8064da9a25818310),
requests `RETRO_HW_CONTEXT_OPENGL_CORE` with desktop OpenGL 3.2 for its classic
renderer or OpenGL 4.3 for its compute renderer. This is not an OpenGL ES context.
The source of this requirement is
[`src/libretro/render/opengl.cpp`](https://github.com/JesseTG/melonds-ds/blob/f394adbacb5722ee97c1b37c8064da9a25818310/src/libretro/render/opengl.cpp).
Its default renderer is software, defined in
[`src/libretro/config/definitions/video.hpp`](https://github.com/JesseTG/melonds-ds/blob/f394adbacb5722ee97c1b37c8064da9a25818310/src/libretro/config/definitions/video.hpp).

`GameSystems` now pins `melonds_render_mode=software` through the existing
backend-option map. Startup applies this map after both per-core and per-game
options, including restored old preferences. The two core-options panels already
exclude keys in backend maps, so they cannot switch this core to an incompatible
desktop context. Other melonDS options and frontend picture filters remain
available. `GameCoreCompatibilityTest` covers the default and stored overrides.

This is a frontend compatibility setting, not a pin of the downloadable core
binary: melonDS DS remains a Libretro buildbot download, as documented in
`GameSystems`.

## Native content ownership

Patch `0037-game-content-ownership.patch` fixes the following inspected problems
in the pinned LibretroDroid fork:

| Path | Previous failure | Current ownership/error handling |
| --- | --- | --- |
| Path/FD ROM loading | `new[]` buffers and cloned paths were never released | `GameContent` owns bytes, stable path and game info through core unload/deinit |
| JNI byte-array loading | Allocated native copy had no owner, including errors | One native buffer moves from JNI into `GameContent`; Java-copy exceptions release it |
| Rejected or cancelled startup | Source resources had no explicit cleanup lifetime | File-read failures unwind locally; submitted content survives core teardown, then releases |
| Disk replacement | Old and failed candidates leaked; a refused add could loop forever | Replacement commits ownership only after core acceptance; rejected additions stop |
| `Utils::readFileAsBytes` | Failed open/stat/seek could produce invalid sizes; short reads were accepted | Checked size, binary positional reads, EINTR retry, checked EOF/error; RAII on every exit |
| Borrowed VFS FD | Reader closed a descriptor still owned by `VFSFile` | Reads preserve descriptor lifetime and seek position |
| VFS open/close | `new` handle reached upstream `free`; `fclose` and `close` shared an FD | Matching C allocation and a single buffered-stream descriptor owner |
| VFS failure | `dup`/`fdopen` errors could reach a null `FILE*` | Checked open, stat and seek; exceptions stay inside the C callback boundary |

Full-path cores keep their exact original path and receive no unnecessary memory
buffer. Virtual relative/include files retain their descriptor registrations
until teardown. This patch does not change the core's `need_fullpath` declaration.

## Verification

After preparing the patched native tree:

```sh
python3 scripts/test_game_content.py --sanitize
```

The test compiles the actual `Utils`, `GameContent`, `VFSFile`, VFS adapter and
libretro-common VFS implementation. Only Android logging is stubbed. Linker
wrappers inject short reads, interruptions, truncated reads, read errors, an
invalid size and descriptor reuse during close. It checks binary contents,
borrowed descriptor offsets, missing/empty/invalid inputs, stable paths,
cancelled candidates, 96 repeated content-owner cycles, 64 VFS lifecycles and
relative virtual files. ASan also checks the real allocator pair on VFS close.

Leak detection is enabled by default with `--sanitize`. Environments that trace
child processes may reject LeakSanitizer itself; for those environments only,
`ASAN_OPTIONS=detect_leaks=0:halt_on_error=1` retains address and undefined-behavior
checks. CI should use the default leak-enabled invocation. These host tests do
not execute the melonDS emulator or replace a real Android session/crash log.
