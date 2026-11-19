# Games — libretro cores with RomM as the library

Status: concept, nothing implemented. Agreed direction: games play inside Ambient TV's own player
and UI (no separate emulator UI), RomM is the only game library and metadata source, and Ambient TV
gets no scraper of its own.

## Goals

- A game is a normal library item: Home rows, library grid, detail page, player, OSD, settings
  panels, resume, background art — the same components, focus behavior and look as films and
  episodes. No stripe selection markers (buttons, chips, menu entries), as everywhere else.
- Save states behave like chapters, quick save like a bookmark, the rewind buffer like a timeshift
  window.
- Cores are pinned, built reproducibly, versioned in git, distributed without an app rebuild.
- Every core shows exactly the options it offers, in the player settings panel, with the same
  scope/origin logic as scoped video settings.

Non-goals: an own scraper, a ROM manager, a RetroArch-style menu, netplay (later), Play Store
policies (Ambient TV is sideload only).

## Prior art

- **Kodi RetroPlayer**: own Game API, `game.libretro` wrapper add-on, one binary add-on per core.
  Games are controlled like video (pause, rewind via seek bar, fast-forward); an in-game savestate
  dialog was added later. Lesson: video-player semantics work for games; per-core add-on packaging
  is expensive to maintain.
- **LibretroDroid** (Swordfish90, GPL-3.0, powers Lemuroid): small C++ libretro frontend for
  Android, `GLRetroView` (a `GLSurfaceView`), GLES only, legacy `SET_VARIABLES` core options.
- **Argosy LibretroDroid fork** (`rommapp/argosy-launcher`, module `libretrodroid`, GPL-3.0):
  adds a Vulkan hardware-render backend for cores (context negotiation interface; output through an
  `AHardwareBuffer` into the GLES presentation), `RewindBuffer`, SoundTouch time-stretch,
  rcheevos (RetroAchievements), `StateLoadPolicy`, `BackgroundFrame`, rumble, sensors,
  microphone, disk control, VFS, curated per-core option manifests on the Kotlin side. Not needed on
  TV: `SecondaryOutput` (second display), `libadrenotools` (custom Adreno drivers).
  Still legacy `SET_VARIABLES` only (no core options v2).

## Architecture overview

```
RomM server ──REST──▶ data/romm (RommBackend, RommRepository) ──▶ RoutingMediaRepository
                                                             │
Home / Library / Detail (existing UI) ◀──────────────────────┘
        │ play
        ▼
GamePlaybackHandle : PlaybackHandle, OsdKeyTarget      (existing player chrome, panels, OSD)
        │
        ▼
:libretrodroid (Argosy fork, pinned + patches) ──dlopen──▶ core .so (own build, signed catalog)
```

## 1. RomM as the source

RomM is added as a server source like Jellyfin/Emby: `data/romm` with backend, client, DTOs,
mappers and cache, routed through `RoutingMediaRepository`. The API is OpenAPI-described
(`/api/docs`, `/openapi.json`) and SemVer-versioned; the client is generated or hand-written
against a pinned minimum RomM version (record it in `external-versions.properties`, check it via
`/api/heartbeat`).

**Sign-in.** Device pairing, the TV-friendly flow (comparable to Jellyfin Quick Connect):
`POST /api/auth/device/init` → show the user code / QR → the user approves in the RomM web UI →
`POST /api/auth/device/token`. Alternative: Client API Token pairing (8-digit code,
`POST /api/client-tokens/exchange`). Tokens are per user (RomM allows up to 25 active per user);
stored like the other server credentials. No passwords stored.

**Library mapping** (`/api/platforms`, `/api/roms`, `/api/collections`, smart/virtual collections):

| RomM | Ambient TV |
|---|---|
| Platform | Library section (one per system), filters: genre, year, player count, region |
| Rom: `name`, `summary`, `genres`, `franchises`, `companies`, `first_release_date`, `average_rating`, `age_ratings` | `MediaItem` title, summary, genres, studio, year, rating |
| `path_cover_large` / `url_cover` | Poster (aspect per platform: landscape boxes e.g. SNES/Mega Drive, portrait e.g. NES) |
| `merged_screenshots` | Backdrop / detail background, `BackgroundFrame` art behind 4:3 games |
| `youtube_video_id` | Trailer via the existing trailer/`AmbientVideoBackdrop` path |
| `player_count`, `regions`, `languages`, `has_manual` | Detail badges |
| Collections (incl. smart/virtual) | Home rows / collection pages |
| Play sessions (`/api/play-sessions`), per-file progress | "Continue playing", last played, playtime |
| `merged_ra_metadata` | Achievements row on the detail page |

Metadata quality is RomM's job (IGDB, ScreenScraper, MobyGames, LaunchBox, Hasheous,
RetroAchievements are configured on the server). Wrong matches are fixed in RomM; the existing
metadata edit/match dialogs are not offered for games.

**ROM files.** `GET /api/roms/{id}/content/{file}` (supports `Range`), multi-file games via file
ids. Cores need a local path: files are downloaded into an app cache with an LRU size limit;
"keep on device" pins large disc images. Progress uses the normal download UI; playback starts when
the file is complete (no streaming into cores in v1).

**BIOS.** `GET /api/firmware/{id}/content/{file}` → the core system directory, verified against
the core catalog's BIOS md5 list. Missing required BIOS blocks launch with a clear message.

**Saves and states.**
- SRAM saves: RomM device sync protocol — register the TV as a device (`/api/devices`),
  `POST /api/sync/negotiate` with local saves (mtime, sha1), apply the returned upload/download/
  conflict operations, complete the session. Runs before launch and after exit. Conflicts follow
  the server's resolution (e.g. keep both); never silently overwrite.
- Save states: `/api/states` (upload with `stateFile` + `screenshotFile`; fields `emulator`,
  `core`, `core_version`, `slot`, `disc_file_id`, `origin_device_id`). This gives cross-device
  states with screenshots and the core identity needed for compatibility checks.
- Screenshots from the TOOLS panel go to `/api/screenshots`.

**Loose files on SMB/NFS/WebDAV/SFTP/local.** No scraper, no game library from file sources.
Supported only as "play file": file name as title, system by extension/folder, no art, local saves
only. Recommended path for a NAS: point RomM at the same folder.

## 2. Player integration

`GamePlaybackHandle` implements `PlaybackHandle` and `OsdKeyTarget`, so the existing player chrome,
`UnifiedPlayerPanel`, OSD, badges and session logic apply. A game session is not background-capable
(no mini player, no background playback): leaving saves and pauses.

| Existing component | Game meaning |
|---|---|
| `chapters()` / `VideoChapter`, `ChapterStrip`, `ChapterButton`, `SeekPreviewOverlay` chapter preview, `chapterNotice` | Save states: `startMs` = play time at save, title "Slot n · time", `imageUrl` = state screenshot. Selecting one loads it |
| Bookmark action (`PlaybackBookmarks`) | Quick save |
| Resume prompt (resume policy) + Home "Continue watching" | Auto-state on exit → "Continue / Start over"; Home row "Continue playing" with the last screenshot |
| Timeline live/DVR mode | Live edge = now, timeshift window = rewind buffer; seeking left rewinds |
| Pause / fast-forward keys | Core pause; fast-forward via frame timing + SoundTouch |
| `FrameStepTarget` | Frame advance |
| `DisplayRefreshRateMatcher` | Match core fps (≈60.1 / 50 Hz) |
| Nerd stats / FPS overlay | Core, core version, renderer, fps, frame time, audio underruns |
| Sleep timer | Saves a state before stopping |
| Badges | System, core, renderer, "saving unavailable", RA hardcore |

Rules:
- Loading a state always creates an automatic "before load" state first (undo); states branch, they
  are not one linear timeline.
- States record core id + core version + catalog hash. States from another core version are marked
  incompatible in the chapter strip (not hidden, not loaded blindly); `StateLoadPolicy` size checks
  catch the rest. The user can keep the old core version for them (section 3).
- RetroAchievements hardcore mode disables state loading and rewind; the UI says so instead of
  greying out silently. Unlocks appear as OSD notices.

**Input.** With the OSD hidden, gamepad buttons go to the core. OSD: Start+Select, Guide, or long
Back (configurable); opening it pauses the core. With the OSD open, the gamepad navigates via normal
focus. The TV remote always controls the OSD. Gamepad support is new in the app (no
`SOURCE_GAMEPAD` handling exists today).

## 3. Cores: build, versioning, distribution

**In git** (binaries are not; `jniLibs/` and `third_party/` stay ignored):
- `cores/cores.json`: per core id — libretro repo, tag, commit (build verifies tag → commit),
  SPDX license, `distribution` (`pack` | `external`), systems, extensions, BIOS list (file, md5,
  required), ABIs, option curation (section 5).
- `scripts/build_libretro_cores.sh`: fetch at commit, apply `scripts/patches/libretro/<core>/`
  (dated change notes as for Monogram), `ndk-build`/CMake per ABI with the pinned NDK and
  `-Wl,-z,max-page-size=16384`, strip, fingerprint marker (sources + toolchain + flags) to skip
  unchanged cores.
- `./build.sh libs-check cores` / `libs-update cores` via `update_external_libraries.py`; license
  changes need manual review. About screen and `third_party_licenses/` list every core.
- BIOS: only names and md5 sums, never files.

**Distribution without an app rebuild.** Cores talk to the frontend only through the stable libretro
C API, so they ship on their own channel:
- GitHub releases tagged `cores-YYYY.MM[.n]` (no APK): `core-<id>-<commit7>-<abi>.so.zst` plus
  `cores.json` with version, commit, sha256, size, `minAppVersionCode`.
- `cores.json` is signed; the app embeds only the public key. Content-addressed: an unchanged
  core keeps its hash and is not downloaded again.
- The app checks the latest `cores-*` release like app updates, verifies signature and hashes,
  downloads on demand (first game of a system), stores in `noBackupFilesDir/cores/<id>/<sha256>.so`,
  read-only, hash-checked before `dlopen`. The previous version stays until the new one has started
  once (rollback). Old versions referenced by states can be kept.
- An app release is needed only when the frontend changes (new environment callbacks, HW render
  features); `minAppVersionCode` hides cores the installed app cannot run.

**Licenses** (not legal advice): `pack` = GPL-3-compatible cores built and shipped by Ambient TV
(candidates: mGBA, Nestopia UE, Mesen, bsnes; each license verified when the core is added).
`external` = non-commercial cores (snes9x, Genesis Plus GX, FBNeo, MAME 2003+) and GPL-2-only
cores (pcsx_rearmed, Mupen64Plus): not redistributed; explicit user action loads them from the
libretro buildbot or imports a file; marked "external", unpinned, still ELF-checked.

**Why not the buildbot as main source:** `nightly/android/latest/` is overwritten by each build
(no stable artifact to pin, old versions disappear), stable RetroArch releases use nightly cores, no
control over 16 KB alignment or patches.

## 4. 16 KB pages and Vulkan

**16 KB.** Own builds: NDK 30 aligns to 16 KB by default; the core script also passes
`max-page-size=16384` like the existing native scripts, and `verify_release.sh` checks `p_align`
of every core. External cores: the app reads ELF program headers on install and rejects/marks cores
that do not fit the device page size (`Os.sysconf(_SC_PAGESIZE)`); no reliance on compatibility
modes. Dynarec cores may hard-code 4096 in `mmap`/`mprotect`: every core is tested on a 16 KB
emulator image before it enters `pack`.

**Vulkan.**
- Phase 1 (from the Argosy fork): Vulkan hardware rendering for cores that use
  `RETRO_HW_CONTEXT_VULKAN` (e.g. PPSSPP, Flycast, Beetle PSX HW, Mupen64Plus-Next/paraLLEl-RDP),
  presentation still GLES.
- Phase 2: Vulkan presentation (swapchain on the `SurfaceView`) with **librashader** for RetroArch
  slang shaders (CRT-Royale, Mega Bezel, …). librashader has no OpenGL ES runtime, so full slang
  support requires the Vulkan path. Built with `cargo-ndk`, pinned like SVP/Monogram. Implemented as a
  second presentation backend in the patched fork, GLES stays as fallback.
- Selection: existing Vulkan probe (`native/vulkan`) and Vulkan playback evidence policy decide
  "Auto"; RENDERER panel offers "Vulkan / OpenGL ES" like for video. Fallback to GLES on driver
  failure.

## 5. Game and core settings in the player panel

Two kinds, both with the scoped layering of `ScopedVideoSettings` (field-wise merge, origin shown
per row, "remember for" choice on change, reset to the next layer):
- Frontend settings (shader preset, scaling/integer scaling, aspect, renderer, audio, controllers):
  **game → system → default**.
- Core options: **game → core → core default** (keys belong to a core).

Panel layout for games:
- **Video**: existing panel plus integer scaling, shader preset, renderer.
- **Audio**: existing.
- **Game** (new): core options grouped by the core's categories; "switch core" when a system has
  several; disk control for multi-disc games; cheats optional.
- **Controls** (new): per port the device types the core offers (`SET_CONTROLLER_INFO`, e.g.
  multitap, mouse); remapping shows the core's input descriptors ("B: Jump") from
  `SET_INPUT_DESCRIPTORS`; OSD hotkeys.
- Tools, OSD info, details: as in section 2.

Core options come from the core at runtime. Required patch to the fork: core options v2
(`SET_CORE_OPTIONS_V2`, `_V2_INTL` for translations incl. German, `SET_CORE_OPTIONS_DISPLAY`
/ update-display callback for dynamic visibility, `GET_CORE_OPTIONS_VERSION` = 2). Without it
cores fall back to a flat list without categories, labels, info texts or translations.

Curation in `cores.json` per core: hide options the frontend owns (aspect, frameskip, audio
resampling) or route them to the frontend row; mark options that need a restart (libretro has no
flag; the panel then offers "restart game" with an automatic state first); TV defaults; drop stored
values whose key/value no longer exists after a core update.

## 6. Dependency on the Argosy fork

- Include `libretrodroid` from `rommapp/argosy-launcher` at a pinned commit as a Gradle
  subproject (same pattern as the SmartTube wrappers in `settings.gradle.kts`); pin + commit
  verification in `external-versions.properties`; own patches under `scripts/patches/`.
- Not built: `SecondaryOutput`, `libadrenotools`, netplay wiring.
- Risk: the fork is tightly coupled to Argosy and moves fast. Updates only through
  `libs-update` with review, like Monogram. License GPL-3.0 matches the already GPL-3.0-distributed
  APKs.

## Phases

1. **MVP**: pinned fork + core options v2 patch; core catalog/build/signing pipeline with 3–4
   `pack` cores (NES, GB/GBA, SNES via bsnes if fast enough on TV SoCs; Mega Drive as `external`
   until a GPL-compatible ARM-capable core is found); RomM sign-in, library, detail page, download cache, BIOS; `GamePlaybackHandle` with
   OSD, states as chapters, quick save, resume, gamepad input; Game/Controls panels.
2. RomM save sync + state upload/download, play sessions, achievements (rcheevos), rewind as
   timeshift, fast-forward, background frame, Vulkan HW cores, PS1/N64/PSP cores.
3. Vulkan presentation + librashader slang shaders; external core import; netplay evaluation.

## Open points

- Minimum RomM version (device auth and sync protocol are recent); behaviour against older servers.
- Download cache size defaults for TV storage; behaviour when the RomM server is offline (cached
  library, locally kept ROMs stay playable, saves sync later).
- Which `pack` cores cover SNES (bsnes performance) and Mega Drive acceptably under
  GPL-3-compatible licenses.
- Controller hotkey defaults that do not collide with games.
