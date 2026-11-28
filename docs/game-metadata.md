# Game metadata, artwork and ROM folders

The game library stores its catalog and downloaded metadata in SQLite. Opening Home, the
library or a game detail page reads this catalog without listing a ROM share or waking its
NAS. A detail page can download an already identified background image. **Play** retains the
existing source wake and local/remote playback workflow.

## Sources and matching

Choose the provider, automatic matching, metadata language and preferred artwork region in
**Game metadata providers**, available in the game library and game details.

| Source | What it provides | Setup |
| --- | --- | --- |
| Libretro name databases | Automatic No-Intro, Redump and other verified catalog imports for supported systems during the first enabled scan; checksum identification for cartridges and exact release-title search for discs. Manual update remains available. | No account; full disc images are not hashed |
| Local files | Curated text from `gamelist.xml`, plus cover/background files inside the assigned ROM folder | Read during an explicit file scan; no account |
| Libretro | Cover art, screenshots and title screens using the system's thumbnail namespace and canonical game name | Default; no API key |
| ScreenScraper | Game descriptions, release year, genres, developer/publisher, players and regional artwork when supplied by the service | Application developer ID/password; optional personal account |
| TheGamesDB | Game information and alternative artwork when supplied by the service | Application API key |
| Off | Existing local information and manual editing remain available | Online enrichment disabled |

Local curated values and explicit edits are protected by field locks. Provider identity and
canonical title are separate from the displayed title, so renaming a game does not change its
online match. Automatic matching uses existing catalog CRC data where available; it does not
read a large disc image just to obtain a hash. Nintendo DS and 3DS cartridge checksums support up to 512 MiB and 4 GiB respectively using bounded streaming reads; large cartridges on a NAS can therefore take longer on their first checksum scan. PSP ISO and supported CSO/ZSO images use bounded filesystem metadata reads. A provider's unambiguous exact result can be
applied automatically. Other matches can be selected in the editor. Libretro artwork uses a
static image collection. The identification editor also searches downloaded DAT names locally,
including partial titles and regional editions. A catalog name can be selected without a cover;
guessed image paths still require a validated image. Screenshots/title screens can fill a
missing Libretro cover. On combined systems, the artwork namespace is confirmed separately
instead of assuming that the first compatible console is the correct one.

Libretro artwork first uses the thumbnail server. If an image is missing there or the server
returns an unusable image response, the downloader tries the same image in the official
`libretro-thumbnails` GitHub repository for that exact system. The fallback keeps the same
game name, image kind and provider; it does not guess another ROM or switch metadata services.
Both paths use HTTPS and the same image size/format checks. If the selected metadata provider has no platform mapping, an already configured compatible provider may be used for automatic identification. Manually locked matches are never switched. C128 has TheGamesDB coverage but no confirmed Libretro catalog; CBM-II has no confirmed upstream catalog or artwork namespace. Local sidecars and manual metadata remain usable for these systems. A rejected image can also fall
back to the game's other artwork kinds or compatible system namespaces.

Loading or importing a DAT immediately applies already cached checksums to existing games,
then scans affected folders to resolve missing checksums. SNES copier headers are excluded
from the catalog checksum while the original checksum stays available to online providers.
Opening an existing catalog also repairs old automatic Libretro display titles from already
verified DAT names once, without a ROM scan or network request. A verified name remains
authoritative even if its artwork is missing: an image matching only a filename guess cannot
replace that identity. Artwork availability can differ between Libretro and TheGamesDB.
Explicit titles, protected fields and manually chosen matches retain precedence. The progress
display distinguishes loading databases, matching names and fetching information/artwork;
completion is reported only after those steps finish. Cancellation retains completed imports
and their visible catalog counts.

Library, Home and detail titles omit recognized country/region annotations such as `(USA)`,
`(Europe)` or `(Japan)` and language/locale annotations such as `(En,De,Fr)`, `(En-US,Pt-BR)`
or `(Zh-Hant,Zh-Hans)`, including differently cased tags. Automatically suggested metadata
searches and match headings use the same presentation. Region and language labels remain in
result subtitles so different editions can still be selected deliberately; the provider's
requested response language is never treated as evidence of a ROM's languages. The full
DAT/canonical name and artwork identity stay unchanged. Revision, disc and meaningful title
annotations are retained. Explicit custom titles
and search text entered by the user are preserved literally. Existing catalog titles update
locally on opening after an upgrade, without rereading the NAS.

ScreenScraper availability and limits depend on the application/account and the service. The
client serializes its requests conservatively and respects returned cooldowns/quota failures.
TheGamesDB platform/developer/publisher reference data is cached. Temporary network, quota or
credential errors do not become permanent “no match” results.

A malformed provider response or rejected artwork for one game does not stop a folder's
remaining games. The folder finishes with a visible partial-result warning, including the
number of failed updates, and later folders can continue. An ordinary missing match is not
counted as an error. Connection, account, quota and storage failures still stop the operation;
completed matches remain in the catalog. Invalid responses remain retryable rather than being
cached as missing games.

## Optional private-build keys

The build uses the same private key file as Monogram and the other account integrations.
Copy [`private-keys.env.example`](../private-keys.env.example) to the ignored
`private-keys.env` and fill only the application credentials you want included:

```dotenv
SCREENSCRAPER_DEV_ID=
SCREENSCRAPER_DEV_PASSWORD=
THEGAMESDB_API_KEY=
```

```bash
PRIVATE_KEYS=1 ./build.sh release
# Or use a different local file:
PRIVATE_KEYS=/absolute/path/to/private-keys.env ./build.sh release
```

Direct Gradle builds use `-PprivateKeysFile=/absolute/path/to/private-keys.env`. With no file,
the generated `BuildConfig.BUILTIN_...` values stay empty and the provider panel exposes the
application-key inputs. With valid built-in credentials, their matching inputs are hidden.
The provider itself remains selectable and shows that access is already configured. A compiled
key does not automatically change the selected provider. The personal ScreenScraper
username/password remains a runtime account setting.

Runtime keys use the existing encrypted credential store. Built-in keys are not exported to
backups or transferred to a paired device. Runtime keys are included only in an explicitly
credential-bearing backup/account transfer. Provider options and manual text choices are
portable independently. Artwork references and attribution links are sanitized before storage
so they cannot carry the provider's application or account credentials.

As for the existing private-build keys, an APK can reveal embedded credentials. Keep these
builds private. No keys are required for the default Libretro path.

## Detail page and editing

Selecting a library or Home tile opens the game detail page with cover/background, description,
year, system, genres, players, region and developer/publisher where known. The page has Play,
favorite, refresh and edit actions and identifies the metadata/artwork sources. It uses the
existing media-detail layout and focus conventions on TV and a stacked layout on narrow phones.

**Edit** offers separate pages for identification, cover, background and metadata fields.
Selecting another cover changes only the cover; it does not silently identify a different
game or replace the background. Available alternatives can include several images for the same
recognized game. A local image is selected through Android's document picker and copied into
Ambient TV's managed storage. Edited fields and chosen images can be locked; unlocking a field
permits a future provider refresh to update it. Empty locked fields remain deliberately empty.
Results support direct touch selection on phones and the same OK/Enter activation as other
editor controls on TV. Match and artwork result rows use the shared animated accent outline
and background to show the current D-pad/keyboard focus, without resizing the row. Optional
image download failures do not undo an accepted text match.

## Individual folder actions

Each assigned ROM folder has its own actions:

- **Scan:** read that folder's playable files, reconcile removed ROM entries, import supported
  local metadata/artwork, automatically prepare missing name catalogs, then enrich online if automatic matching is enabled. Auto-detected folders get a targeted checksum pass for newly discovered cartridge catalogs within the same scan.
- **Metadata/artwork:** enrich its already indexed games without repeating the ROM scan or
  rereading ROM data. This explicit action works with automatic matching disabled, provided
  an online provider is selected.
- **Cancel:** stop the current scan or enrichment.
- **Remove:** cancel pending work and remove the assignment, its catalog entries, metadata,
  lookup results, manual choices and managed artwork that no remaining folder references.

Removal does not delete source ROMs, original image files or the underlying file source. If
two assigned folders overlap, games still referenced by the other assignment retain their
metadata and artwork. Removing the last reference releases those records and images. Saved
games, installed cores and BIOS management continue to use their existing separate settings.

## Local sidecar scope

An explicit scan looks for `gamelist.xml` at the assigned folder root and reads recognized
`game` fields: path, name, description, release date, genre, developer, publisher, players,
region, image/thumbnail and fanart/screenshot. Image references must remain within that folder.
Absolute paths, parent traversal and external URLs are not opened. Existing explicit locks
retain priority over a newly imported value; unlocked automatic values can be replaced by a
curated sidecar value.

For images stored independently of XML, the importer supports conventional locations under
the assigned folder, including `covers`, `screenshots`, `media/covers`, `media/screenshots`,
`downloaded_media/<Ambient-system-id>/covers`, `downloaded_media/<Ambient-system-id>/screenshots`
and `images/<ROM-base>-image`. PNG, JPEG and WebP are supported. The ROM-relative base includes
subfolders. These are scoped imports, not an automatic search of a separate ES-DE media tree
outside the selected folder/grant.

## Performance and storage

- Browse queries select only the title, year and local image paths; the full description and
  credits are decoded for the selected game.
- Online enrichment reads a folder in bounded SQL pages. Local metadata resolution uses the
  indexed catalog, while image discovery reuses a scan session and bounded directory cache.
- During an active folder enrichment, the focused TV game is considered first, followed by
  games currently visible in Library or Home. Only games belonging to that folder are promoted.
  The next request follows focus changes without cancelling an image already being fetched.
  After up to three promoted games, ordinary folder order advances; a bounded record prevents
  repeated downloads or progress counts when that order catches up. Browsing alone starts no
  metadata scan and does not change automatic-matching or provider settings.
- After an app restart, visible Library and Home cards reuse their saved cover files. If an
  automatic image was evicted by the disk budget, or its file is missing, a single background
  worker restores that exact stored image address, with the focused card first. It does not
  identify the game again, scan ROM folders or wake the NAS. Automatic matching may remain off;
  selecting the provider **Off** also disables this online cover recovery. Images with no
  stored remote reference are not searched for. Failures and repeated cache eviction are
  throttled, and offscreen cards do not start downloads.
- Reopening or rebuilding the view within the same app session also rechecks missing covers.
  Visibility carries its own generation, so a quick layout/navigation change cannot reuse a
  completed request from the old view merely because the same game IDs appear again. Focus
  changes and ordinary catalog updates retain the existing protection against repeated loads.
- Uncached game-cover display requests wait until the card is visible. TV cards retain the
  shared image loader's focus priority; memory hits and loads already admitted stay usable.
- Bulk revisions and progress are coalesced; manual changes remain immediately visible.
- Known metadata misses are cached for seven days and successful matches for thirty days.
  File version, locale, provider identity and DAT revision/name changes produce a new lookup fingerprint.
  A cached successful lookup is reused only if the stored canonical identity still matches
  the current query; the next permitted enrichment rematches an outdated identity.
  Missing images have independent cache entries so one missing cover cannot erase a match.
  The Libretro artwork policy also versions its lookup/image caches, so misses from the old
  server-only path cannot postpone a permitted lookup through the official repository.
- Image previews load only for visible choices. They have a 24-hour lifetime, a 48-image /
  32 MiB budget, and are removed with their folder. Automatic owned artwork has a 128 MiB
  budget; explicitly selected/pinned images are preserved until their game is removed or
  the image is replaced. Each accepted image is at most 8 MiB and 16,777,216 pixels.
- Folder/source reconciliation and per-file generation tickets prevent late network or scan
  results from recreating deleted data. The database migration preserves existing catalog,
  favorite and play-history data.

Implementation audits: [providers](audits/2026-11-22-game-metadata-providers.md),
[storage](audits/2026-11-22-game-metadata-storage.md),
[UI](audits/2026-11-22-game-metadata-ui.md),
[integration](audits/2026-11-22-game-metadata-integration.md).

## Upstream references

- [RetroArch thumbnail documentation](https://docs.libretro.com/guides/roms-playlists-thumbnails/)
  and [Libretro thumbnail repository](https://github.com/libretro-thumbnails/libretro-thumbnails).
- [ScreenScraper API documentation](https://www.screenscraper.fr/webapi2.php).
- [TheGamesDB API reference](https://api.thegamesdb.net/) and
  [current API schema](https://api.thegamesdb.net/spec.yaml).
- [ES-DE user guide](https://gitlab.com/es-de/emulationstation-de/-/blob/master/USERGUIDE.md)
  and [Batocera gamelist format](https://wiki.batocera.org/systems:emulationstation#metadata).

Downloaded game artwork is attributed to its source. The application does not bundle a mass
copy of these catalogs or claim that a frontend software license licenses the game artwork.
