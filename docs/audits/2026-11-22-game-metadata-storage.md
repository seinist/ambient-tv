# Game metadata, artwork ownership and folder cleanup audit

Date: 2026-11-22. Feature branch: `feat/game-metadata-artwork`.

## Scope and outcome

The game catalog remains the offline source for Home, library and detail navigation. Schema v6 adds full metadata, owned artwork, lookup results, request guards and folder/source scope tracking to the existing `game_library.db`. Versions 4 and 5 migrate without deleting the ROM catalog, DAT entries, favorites, custom names or play history. The existing display projection is rebuilt once when its policy changes; no ROM scan is required for migration.

Implementation reviewed: `GameLibraryRepository`, `GameLibraryQuery`, `GameLibraryQueryIndex`, `GameMetadataStorage`, `GameArtworkFiles`, and the streaming/lookup portion of `GameMetadataLocalImporter`. Provider credentials, provider HTTP parsing, Compose detail/editor flows and local-field merge policy have separate integration reviews.

## Persistence and browse integration

- Full bounded metadata is stored once per ROM in `game_metadata.payload`. Separate title/year columns feed the display projection; Home/library cursors read four optional scalar fields: metadata title, year, cover path and backdrop path. They never select or decode summaries, genres, provider payloads or other full detail fields for every tile.
- Automatic display-title order is custom/portable title, metadata title, DAT title, ROM header, then filename. Provider canonical title and ID remain independent of display renames. The explicit internal-title/filename display preferences still behave as chosen by the user.
- Automatic scraping stays in SQLite. Only explicit edits and selected matches enter portable overrides; title/favorite/play-history edits preserve those metadata overrides. Portable restoration preserves later enrichment while reapplying explicit locks.
- `metadataTicket()` exposes the already stored archive CRC. Metadata/detail browsing does not hash, open, list, probe or wake a ROM source.
- Automatic responses preserve locked fields, locked image provenance and manually selected provider identity. A locked local image retains the absence of a remote image/provider/source instead of acquiring an automatic provider's attribution.
- `GameMetadataTicket` has value equality, including source/folder/file version and epoch. Separately issued unchanged tickets therefore validate image previews correctly. Regression coverage protects this contract.

## Removal, overlap and stale work

Folder removal reconciles catalog membership, the display projection, full metadata, artwork references, lookup results and request guards under the repository lock and one SQLite transaction. Only ROM identities with no remaining eligible projection row lose metadata and owned images. Removing a deeper overlapping assignment therefore leaves the parent's game and image intact. Last-reference removal purges the corresponding portable override too, including imported overrides that have never been scanned on this device.

Owned files are deleted after the successful database transaction, while the repository lock still prevents a re-added ROM from racing the old file cleanup. A process crash between file rename/commit or database removal/file deletion leaves an orphan; the next local reconciliation collects it. Source removal/location changes and restored folder configuration use the same reconciliation path. The coordinator schedules that local work on its conflated IO queue.

Metadata guards combine the selected folder, its removal epoch, source-location scope, and ROM identity/version. Folder removal followed by restoring the same IDs cannot accept an earlier request. Scan commits also recheck folder configuration, epoch and source location immediately before the transaction. Changed ROM size, modification time or system clears automatic metadata, automatic artwork, lookup records and guard epochs; explicit portable choices and pinned images are intentionally retained.

Sidecar import captures the origin folder token before source IO, then issues origin-scoped tickets per ROM. It cannot obtain a fresh valid ticket for an old sidecar after a folder is removed and recreated, including when another overlapping assignment still displays the ROM.

## Artwork lifecycle

`GameArtworkFiles` writes a unique temporary copy, validates it, then renames it into a SHA-256-named directory owned by the ROM. The stored file is a copy; local source images, sidecars, ROMs and shared NAS/SAF roots are never removed. Deletion verifies app-owned directory boundaries, including canonical paths, before touching files.

Input limits are 8 MiB compressed, PNG/JPEG/WebP, at most 8192 pixels on either edge, and at most 16,777,216 total pixels. Staging errors, canceled/stale results and attempted automatic replacement of a pinned image discard the temporary file. Header/dimension validation is bounded; rendering still needs to tolerate a subsequently undecodable image.

Automatic artwork has a 128 MiB budget, evicted by oldest update time. Explicitly selected/imported images are pinned and exempt from automatic eviction. This is age-based cache retention, not an access-based LRU. A manual metadata save synchronizes image pins with the image-field locks transactionally; unlocking keeps the current file but permits its next automatic replacement. Removing the final ROM reference removes pinned files as well.

## Performance and lookup correctness

- Folder enrichment reads stable keyset pages, bounded to 512 records (default 128). Folder counts use SQL and respect the assigned system.
- XML import streams one record at a time and performs indexed exact path lookups. Conventional media import streams pages of 256 games. Neither path accumulates an entire `LibraryGame` map for a large folder.
- Exact sidecar paths use the existing source/path index. SAF's unique bare-filename fallback uses the new folder/filename index. Two identically named games are ambiguous; nested network paths never fall back to a basename. Absolute paths, URLs and traversal are rejected before lookup, preventing metadata for `EU/Game.sfc` from being assigned to a different `US/Game.sfc`.
- Lookup cache identity is `(file_id, provider, fingerprint)`. A successful metadata lookup and a missing cover coexist instead of replacing each other. Expired entries and old identity/locale variants are pruned per ROM, with a transactional cap of 32 results; this avoids whole-table expiration scans. Network/credential failures cannot be stored as `not_found`.
- Metadata batch notifications are nest-safe and can be flushed periodically/finally by the coordinator. SQL writes remain immediately visible, while automatic changes avoid reloading the full library after every ROM. Favorites/settings/removal and explicit manual metadata saves publish immediately.
- Unchanged automatic metadata, compared without its timestamp, produces no payload rewrite or revision. Existing projection stamps avoid rewriting unchanged sort/search indexes.

## Validation

Local checks completed:

1. Cached Kotlin 2.4.21 compiler compiled the actual modified repository/query/storage/artwork files, shared model/URL/policy code, local importer and gamelist reader against real Android API signatures, serialization runtime/plugin, Hilt/inject and coroutines. Unchanged FileAccess/FileStore/WakeOnLan/preferences/archive services were compile-only API seams and were not executed.
2. **23 JUnit tests passed** across `GameMetadataStorageTest`, `GameLibraryQueryTest`, `GameMetadataPolicyTest` and `GameGamelistReaderTest`. They cover lock/identity preservation, local artwork attribution, ticket equality, safe sidecar fallback, query/search contracts, provider identity, bounded XML and DTD rejection.
3. A host SQLite exercise executed the source DDL/migration statements and SQL emitted by the compiled query builder. Both v4→v6 and v5→v6 preserved catalog/favorites/CRC/DAT data; the browse result remained 18 columns and excluded the payload. Overlap retained metadata until the last reference. `EXPLAIN QUERY PLAN` used the title index for the ordinary library and the composite folder/file membership index for folder pages, without a title-sort temporary tree.
4. `git diff --check` passed.

Reusable local commands and evidence:

```text
python3 /workspace/scratch/ec56204ef533/game-metadata-storage-validation/run.py
python3 /workspace/scratch/ec56204ef533/game-metadata-storage-validation/sqlite_audit.py
```

The same directory contains `manifest.json` with exact compiler/JUnit arguments and source hashes, `compile.log`, `junit.log`, and `sqlite-audit.json`.

`GameLibraryRepositoryQueryTest` additionally contains real Robolectric/SQLite integration regressions for v4/v5 migration, warm-query behavior, overlap removal, owned/original image separation, remove/re-add and source/file version races, portable cleanup, pins/unlock, cache coexistence/expiry/bounds, folder pages/point lookup ambiguity, automatic artwork eviction, batch notifications, cached detail/favorite/removal and cold library navigation without NAS wake. The real coordinator fixtures close their preference watcher after each VM test.

The Android/Robolectric integration suite, Compose/Hilt wiring and full Gradle build still require CI: the local Gradle wrapper could not obtain Gradle 9.8.1 and no local Android SDK is installed. Host SQLite and API compilation do not substitute for those gates or for device lifecycle/UI testing.
