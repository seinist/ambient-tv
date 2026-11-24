# Retro Game Library (Ambient TV)

## Scope

Games is a library **view over existing FileSource transports**, not another SMB/NFS client.
A server may hold `Movies/`, `TV/` and `Games/` on the same share. Existing
credentials, WireGuard routes, online checks, MAC/WOL groups and wake notifications
are reused. RomM is a separate system with its own catalog (`romm/RommLibrary`); it reuses
this library's screen parts (`GameLibraryGames`, header, chips, view options) and view settings.

A new source created inside Games has `showInFiles=false`; an existing Files
source remains visible by default. Each GameLibraryFolder has
`alsoShowInFiles=false`: its subtree is hidden in Files, while sibling media
directories remain browsable. The filter applies to caches, photo libraries,
Android Auto's Files browser, favorites and history. The setting can be switched
per folder; no actual files or access grants are changed.

## Library and UI

- Assign local/USB SAF folders or SMB/NFS shares, optionally to one of 31 cores'
  registered console IDs; `auto` uses the existing header/extension heuristics.
- System categories, all games, favorites, recently played, search, rename,
  name priority (manual > verified DAT > internal ROM header > filename), sort,
  grid, list and console-by-console horizontal rows.
- An optional Home editor group adds the cached All, Favorites and Recent rows.
  Home does not scan or wake network servers. Selecting a game launches Libretro
  with its explicit system ID, or opens Games for an offline manual wake.
- Each of the 31 systems has a distinct, original Android VectorDrawable:
  `app/src/main/res/drawable/game_system_<system_id>.xml`. Use
  `GameSystemIcons.drawable(id)` everywhere. They have a shared 64×64 drawing
  grid, round linecaps, neutral tintable strokes and hardware-first silhouettes.
  The larger controller-key artwork in `InputDeviceArtwork.kt` remains separate.
- File-scoped favorites, last played and manual names are portable settings;
  the SQLite catalog is device-local. Companion remaps NAS FileSource IDs and
  never transfers SAF URI grants. A personal file backup may carry local paths
  for restore on the **same** device.

## Metadata

Readers use bounded random-access header windows where the format supports
them. They do not promise full commercial title names. A No-Intro/Redump DAT
(XML or clrmamepro text, CRC32 key) supplies verified names for supported plain
ROM files up to 128 MiB and for archive entries: either user-supplied (system
selection) or, on request, the libretro-database DATs RetroArch uses for the
systems in the library or single systems ("Load game names from Libretro", followed by a rescan
of the affected folders).
Nothing is downloaded without that request. No databases, copyrighted ROMs,
BIOS files or vendor logos are bundled.

CUE/GDI/M3U companions are grouped in the Games catalog. The playlist entry
remains playable, and the original Libretro direct-content/fallback paths own
opening referenced tracks. Scans are transactional and only user-initiated;
the old index survives a failed or truncated read. For extremely large single
directories the bound increases from 20k up to 200k entries and then fails
rather than silently omitting files.

## Acceptance checklist

- Validate the 31 vectors using `GameSystemIconsTest` and visually at
  sidebar (24dp), chip (20dp) and tile (64dp) sizes. In particular SNES is
  drawn from above with slot and front ports; PS1 has a distinct disc-lid shell
  and PS2 a low-profile slot-loading console.
- Unit tests: GameRomTitlesTest, GameDiscGroupingTest,
  GameOnlyFilePathsTest, GameLibraryCompanionSyncTest,
  GameHomeRowsTest and SidebarNavigationPolicyTest.
- Real device checks remain mandatory for SMB/NFS authentication, WOL via
  WireGuard and local LAN, SAF persistence, very large archives/discs,
  emulator core support, and focus/renderer behavior.

Build validation: `./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`
and the repository's pull-request checks. Do not merge with failing required checks.
