# Game metadata integration audit — 2026-11-22

## Scope and acceptance

This change joins three audited feature areas: provider/build/account configuration,
SQLite metadata/artwork ownership, and game detail/editor/folder UI. The user requested
optional compiled application keys using the existing Monogram pattern, media-style detail
and editing, and individual folder scan/removal including owned data and images. Performance
and an audit after each feature are explicit acceptance criteria.

Companion controller input, player lifecycle, ROM execution and native rendering are outside
this change. The existing Play route and selected-device dispatch remain in use. RomM and an
IGDB backend are not part of this implementation.

## Cross-feature findings and fixes

| Boundary | Audit result and correction |
| --- | --- |
| Query identity vs displayed title | Automatic matching derives identity from saved provider ID/canonical title, verified catalog name or filename. An explicit search clears the old hash/ID so a manual rematch cannot accidentally query the old game. Renaming stays a display choice. |
| Old custom names vs new editor | Existing custom titles become protected metadata titles. Editing another field or unlocking an image cannot clear a pre-existing rename. Explicitly unlocking title still permits replacement. |
| Manual identification vs missing keys | A locked provider identity is never replaced by the current default provider when its credentials disappear. The operation reports the missing credentials. Automatic candidates with another identity are rejected before any image write. |
| Static Libretro candidates | Candidate URLs alone are not a successful match. An actual validated cover, screenshot or title image is required, including when a user has locked a separate local cover. |
| Query cache vs image cache | Audit found that a single provider slot let a successful metadata lookup erase a missing-image result. Query and image fingerprints now coexist, expire independently and have a bounded per-ROM history. Temporary service errors are not cached as absence. |
| Preview validity | Audit caught reference-equality comparison of newly created tickets, which would have rejected every valid preview. Tickets have immutable value equality; both cached and downloaded previews revalidate ownership. |
| Folder removal and stale work | UI joins scan/cancel work before removal. Metadata writes require matching folder and file generations. Local sidecar imports capture the originating folder before IO; removal/re-add cannot adopt the earlier session. Source/backup reconciliation also cleans orphan preview directories. |
| Overlapping assignments | Storage keeps game data while another eligible assignment still owns the ROM identity. Removing the final reference purges metadata, lookups, portable manual choices and owned image copies; original media files are outside this ownership. |
| Local curated metadata | Audit found that local XML could lock an old unlocked online value instead of importing its own value. The merge now imports supplied local fields over unlocked automatic fields and preserves all explicit locks, including deliberate blank values. |
| Local path matching | Exact relative paths do not fall back to another directory's same-named ROM. Unsafe paths are rejected before lookup; opaque SAF identities permit only an unambiguous bare-filename fallback. Image resolution stays inside the selected folder. |
| Portable metadata and keys | All restored/persisted artwork and attribution URLs pass the same credential scrub, including encoded query parameter names. Provider downloads apply their additional host restrictions. Credentials exist only in provider request construction and the established encrypted/build-key paths. |
| Image validation | Providers, previews and permanent storage use the same 8 MiB / 16,777,216-pixel limits. Download and staging cleanup run on cancellation/failure. UI receives local paths, never authenticated request URLs. |
| Update pressure | A folder scan/enrichment groups metadata revisions and emits progress at bounded intervals. Manual edits publish immediately, even while a bulk operation runs. Descriptions stay out of browse projections. |

## Performance and lifecycle checks

- Online enrichment reads 64-game pages. Local XML resolves a record with indexed point
  queries; conventional artwork discovery reads 256-game pages instead of retaining a
  second full-folder map.
- Local import reuses one source session and at most 24 cached media-directory listings.
  The XML reader bounds bytes, nesting, records and field size and rejects document types.
- Metadata-only actions reuse existing checksums and never read ROM bytes. A file scan is
  the explicit boundary for NAS listings and local sidecar reads.
- Provider concurrency is bounded. ScreenScraper has its own conservative lane, and network
  cancellation propagates to OkHttp. No timer starts background ROM scans.
- Detail opening uses indexed data. Optional background acquisition uses a stored reference
  and is attempted once per background identity in that detail session.
- Image previews have a 48-image / 32 MiB / 24-hour bound. Automatic permanent art has a
  128 MiB bound, while user pins retain ownership until replaced or the game is removed.
- Maintenance listens only to folder/source/scope configuration changes; it runs on IO and
  coalesces requests. Preview reconciliation queries only folders with existing previews.

## Validation evidence and limits

The local Gradle wrapper download is unreachable and no full Android SDK is installed in
the workspace. Validation therefore separates actual-source compilation/pure tests from
the required repository Android CI gate.

- The combined cached Kotlin 2.4.21 harness compiled the actual coordinator, providers,
  settings/parser code, storage/importer/catalog/query code and game detail/library
  ViewModels together against real Android 14, coroutine, serialization, OkHttp and Hilt
  APIs. The harness explicitly substitutes unchanged FileAccess/FileStore/Wake,
  AndroidX lifecycle signatures, unrelated preferences and generated IDs. It is not a
  Compose, DI-generation or Android SQLite runtime test.
- The focused provider and combined policy suites passed. They cover parsing, platform
  namespaces, matching/field-lock policy, credential handling, XML bounds and safe paths.
- The storage audit exercises the actual schema/migration statements and compiled query
  SQL with host SQLite, including preservation, overlap deletion and query-index use.
- Source audit was compared with base commit `933aaa811e876962ff3dcbc2a9251fd70cdaed18`:
  both report the same 356 pre-existing localization findings, with **zero new findings**.
  New UI/settings strings are present in default English, explicit English and German.
- `git diff --check` passes. Added repository tests cover migration/cleanup, stale tickets,
  independent lookup caches, portable settings/accounts, detail state and navigation.

The PR Android unit/Compose/DI compilation and Robolectric suite, device-audit compilation,
native ABI builds and shader checks remain the release gate. The final PR result records
their outcome. No physical phone/TV test or authenticated live ScreenScraper/TheGamesDB
account test was performed in this environment; those must not be represented as verified.

The first full PR run compiled production/Compose/Hilt and device-test sources successfully;
all nine native/shader/detection jobs passed. Its 5,590-test app suite exposed six new fixture
failures: the XML test needed the same Robolectric runner as the existing DAT parser tests,
and five image-storage tests shared a PNG with an invalid IDAT checksum. The fixtures now use
the Android parser and a verified PNG with native Android image decoding. A direct decoder
regression checks the fixture's MIME type, dimensions and decoded bitmap. Production image
validation was not relaxed to accommodate invalid test input.

Feature audit reports:
[providers](2026-11-22-game-metadata-providers.md),
[storage](2026-11-22-game-metadata-storage.md),
[UI](2026-11-22-game-metadata-ui.md).
