# Game library query and integration audit — 2026-11-22

## Feature and boundary

The library, game Home rows and game Home deep links now issue local SQLite queries for the
requested section, literal search, order and optional Home limit. A complete library page has
no implicit limit. An explicit ROM-folder scan remains the only way this feature reads ROMs,
lists a NAS or wakes a source.

`game_library.db` moves from version 4 to 5 without deleting its existing catalog, DAT data or
CRC/parser revisions. A rebuildable `game_query` display projection contains one eligible row
per file, choosing the deepest assigned folder before applying a system/favorite/recent filter.
The first query after migration builds this projection from the existing local catalog on IO.
Normal launches compare a persisted configuration signature and reuse it. The raw ROM index
and portable preference records remain the authoritative scanner/user data.

## Integration audit and fixes

| Area checked | Result |
| --- | --- |
| Overlapping folders | Deduplication happens before section and favorite filtering, so a parent assignment cannot leak into another system's results. Equal-depth candidates keep the previous raw-row order. |
| File/source eligibility | The existing `cachedGameMatchesFolder` contract still rejects foreign extensions, foreign/removed sources, changed assignments, firmware and core archives. No eligibility check opens the NAS. |
| Titles | Portable/manual names precede verified DAT and internal names. Filename/internal/automatic modes all retain those priorities. |
| Search and sorting | Literal substring search uses persisted Unicode case keys rather than ASCII-only SQLite `NOCASE` or wildcard `LIKE`. Locale.ROOT lowercase UTF-16 sort keys retain the previous title order, including supplementary characters. Recent ties retain the deepest-folder order. |
| Imports and TV sync | Existing JSON folder/override payloads and source-ID remapping remain unchanged. A folder/source import rebuilds the local display projection; changed override paths update only affected indexed file IDs. The cache is not exported. |
| Editing imported choices | Audit found that an existing favorite/title edit could overwrite the other imported fields with stale scanner values. Edits now start from the merged effective user state, preserving an imported title, explicit false favorite and newer play time. |
| Scans and DAT imports | Original metadata and display changes share a database transaction. Failed/truncated scans still leave the old catalog intact. Previous scan metadata is read with `WHERE folder_id=?`, not by loading every folder. |
| Removed assignments | Removing a child assignment reveals a still-valid parent assignment. It does not remove a playable file merely because its previous display winner disappeared. |
| Threading and menu | Audit found synchronous SQLite opens/migrations in sidebar construction and refresh lambdas. Initial menu state now uses explicit configured systems; a coalesced IO task obtains indexed systems. Library/Home queries and user edits also stay off the UI thread. |
| Invalidation and initial load | Catalog/config request identities exclude unrelated Files/DLNA/YouTube progress and video preferences. View/grid-only changes reuse the current list, and search changes reuse system IDs. The preference event stream receives an initial emission (the existing preference stream only emitted subsequent writes), so a new library ViewModel can load without waiting for an unrelated preference write. Home also observes imported game choices while suppressing unrelated preference updates. |
| Cancellation | Library request collection uses `collectLatest`; IO work is interruptible and row loops check interruption. An obsolete query cannot publish its results over a newer section/search. |
| Database concurrency | WAL permits a small sidebar reader while the display index or scanner writes. Game writes and projection publication remain serialized; WAL is not treated as a multi-writer mechanism. |

## Performance audit

The dominant change is the amount of work per request, not an asserted device-time percentage:

- Home requests at most 24 selected rows per enabled game row. A single-game deep link requests
  one row. The browser receives only matching games and their final order; it no longer filters
  or sorts the full catalog in Compose.
- Display cursors omit scanner-only CRC, DAT-generation and parser-version columns.
- The initial projection rebuild streams files in ID order and keeps only one overlap candidate
  at a time. It does not retain another complete `List<LibraryGame>` during migration.
- Automatic-title name/system ordering, favorites and recents have dedicated indexes. The
  favorite index is partial, so nonfavorites do not occupy it. Uncommon alternate title modes
  use SQLite sorting instead of storing another six copies of the title/file key in indexes.
- A projection fingerprint skips unchanged rows on rescans. Repeated queries and warm process
  reopening perform no projection writes. Reads never update a per-item last-access timestamp.
- The folder/file/source-path indexes support scanner metadata reuse, edits and imported override
  refreshes without a full catalog scan.

### Measured query shape

A local host SQLite 3.53.1 fixture contained **100,000 ROM rows**, with 20% assigned to GBA and
every eleventh file marked favorite. The executable SQL was exported by the actual compiled
`GameLibraryQuery.kt`; the schema statements came from `GameLibraryQueryIndex.kt`.

| Request | Rows returned for app hydration | Query-plan evidence |
| --- | ---: | --- |
| Previous complete-catalog path | 100,000 | All `game_entries` rows read before app filtering |
| Home: all | 24 | `game_query_title_auto`, then primary-key lookup of selected original rows |
| Home: one system | 24 | `game_query_system_auto` with `system_id=?` |
| Home: favorites | 24 | Partial `game_query_favorite_auto` index |
| Home: recents | 24 | `game_query_recent` index |
| Launch one game | 1 | `game_query` primary key, then original-row primary key |
| Full GBA section | 20,000 | System-index range, with no unrelated game hydration |
| Literal `09999` search | 11 | Indexed title-order scan, only matching original rows joined/hydrated |

Each 24-item Home request used about 500 SQLite VM instructions in that fixture (100-instruction
sampling). This is supporting query-shape evidence, **not** an Android/phone/TV latency or frame
rate benchmark. Literal arbitrary-substring search still scans the selected display index;
replacing it with tokenized full-text search would change the current matching semantics.

## Verification status

- Five pure JVM tests passed locally against the actual production query/key functions: Unicode
  case matching, UTF-16 sort compatibility, literal/parameterized SQL inputs, limit/order rules
  and unambiguous signature encoding.
- Twelve Robolectric tests are added for actual SQLite query plans (10,000 rows), title modes,
  recent ties, unchanged-write suppression, v4→v5 migration, overlap/source filtering,
  imported-state edits, assignment removal, warm reopening without writes, and an actual ViewModel cold-load/progress-invalidation audit using a read-only query counter. They require the
  Android CI test run; they were not executed by the local host-only harness.
- Scoped `git diff --check` passed. Independent integration review and the complete app/ABI CI
  results are recorded by the PR's final validation, not implied by the host fixture.

## Remaining practical limits

There was no physical phone/TV frame-time measurement. An explicit all-games page still loads
all matching rows to preserve alphabet navigation, complete search and existing focus behavior;
Home and normal system/favorite/recent requests no longer pay that unrelated-catalog cost.
Initial migration or a changed folder/source configuration necessarily rebuilds the derived
display projection once. The projection policy version must be increased if future eligibility
system-label or title-key rules change, so previously cached rows rebuild locally without requiring a NAS scan.

## Independent review

Root review checked projection construction/deduplication before filters, imported override merge and transaction boundaries, query cancellation, Home row limits, single-game lookup and sidebar startup. The broad invalidation and missing initial preference emission findings were corrected before commit; the ViewModel regression test covers cold start and unrelated progress writes. No remaining blocking finding was identified in this review. Complete Android CI remains the compile/integration gate.
