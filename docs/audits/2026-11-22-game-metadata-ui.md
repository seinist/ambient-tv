# Game metadata, details and folder UI audit — 2026-11-22

## Feature boundary

Games opened from Home or any library view now enter a game detail page. The page uses the
existing media detail components and dialog chrome: a cover, optional background, title,
system, release year, players, region, genres, description, developer/publisher and source
credits. Play retains the shared source wake workflow and selected playback target. Opening
the page reads the local catalog; it does not list the ROM source, hash a ROM or search a
metadata service. It may fetch a missing background already identified by stored metadata.

The editor has separate game identification, cover, background and field pages. Text changes
lock the edited fields against automatic replacement. A field can be unlocked for later
updates. Choosing an image changes only that image; an independent image source remains
visible in the detail credits. The document picker copies a chosen local image into the
owned artwork store. It remains usable without provider credentials.

Each assigned folder has its own scan, metadata/image enrichment, cancel, Files visibility
and remove actions. A file scan also imports the supported local sidecar and then invokes
online enrichment if automatic updates are enabled. Explicit enrichment uses indexed games
and does not repeat a source listing or ROM hash. Removal is confirmed with the exact scope:
Ambient TV's catalog entries, metadata and owned image copies; source ROMs/source images
remain untouched, and games retained by another assignment keep their data.

## Integration audit and corrections

| Area | Result |
| --- | --- |
| Home and library navigation | All three library routes and game Home rows open the same encoded detail route. `launchSingleTop` prevents a repeated selection from adding duplicate top entries. File IDs containing separators, spaces and query characters remain one route argument. |
| Detail chrome | The route is included in detail/fullscreen chrome, receives ordinary media-detail page transitions, and shows the background playback indicator. Audit caught and corrected the initial omission from the transition exclusion set. Player return uses the existing fullscreen focus handoff. |
| Playback and remote target | The detail Play action runs the existing NAS wake check; the resulting game-player route passes through `playOnSelectedTarget`. Merely opening a tile no longer invokes the Home route's old source probe. |
| TV focus and Back | Play receives initial TV focus. Dismissing the editor restores Edit focus. Editor subpages return to their overview; the overview returns to details. Existing AppDialog focus containment and keyboard-resize behavior are reused. |
| Short phone layouts | Action groups use the existing wrapping `DeviceActionRow`. The cover and text stack below 600 dp. Search controls and image choices share one bounded lazy viewport; audit removed the fixed controls above a result-only scroller, which could leave no result space in landscape or with the keyboard open. |
| Metadata precedence | Edits use the coordinator and supported editable field names. Invalid year input disables Save rather than silently clearing the previous year. Explicit empty years remain valid. Provider match identity is separate from the displayed/custom title. Audit preserved pre-metadata custom names as title locks, so editing a year or unlocking an image cannot erase an older rename. The editor uses the metadata title even when the library is displaying filename/internal-title mode. |
| Artwork choices | Stored matches request the provider's alternatives for that identified game. Name search remains available for another match/provider. Different covers of the same provider game use distinct stable keys, avoiding lazy-list collisions. Choosing a cover/background does not change the other artwork or text match. |
| Credentials and credits | Coil receives only owned local image paths or local preview paths. Preview generation carries the file ID so folder removal cancels and removes previews too. Choices display the provider and public source host; details keep separate text/cover/background credits and public source links. |
| Stale work | Search generations prevent an old canceled request from publishing results or clearing a newer loading state. Detail generations prevent an action reload and revision reload from publishing in reverse order. Coordinator tickets protect persistence if a folder disappears during provider/image work. |
| Folder deletion | The UI cancels and joins an active scan of that folder, awaits coordinator folder cancellation, then removes local catalog data on IO. Row actions are disabled while removal is pending. Failure leaves an error visible; confirmation is never treated as successful deletion by itself. |
| Scan policy | Scans are serialized to avoid simultaneous NAS listings. Every folder is individually selectable. Canceling/removing the current folder during Scan All cancels that Scan All job; starting another row scan remains an explicit action. Metadata-only enrichment uses the coordinator's explicit/manual mode. |
| Settings and feedback | The same metadata settings panel is reachable from global Settings → Games, the library and details, including before any game is scanned. Closing detail settings refreshes provider availability and permits retrying an optional missing background. A manual refresh that applies no match shows a localized next-step notice instead of silently ending its spinner. |
| Localization | All newly introduced UI labels and confirmations are supplied in English and German with matching formatter placeholders. |

## Performance audit

- Grid/list/Home use the lightweight catalog projection (`title`, year and local artwork
  paths). Full descriptions and credits are read only for the selected game. Home retains
  its existing bounded queries rather than hydrating a complete library.
- Library cover images reuse the shared lazy/staggered artwork loader, with the focused grid
  image prioritized. No image decoding or provider fetch occurs in SQLite projection code.
- Preview downloads start only for composed lazy rows and are canceled with their composition.
  A credential-bearing artwork URL is never used as a Coil request/cache key.
- Optional detail background acquisition is attempted once per stored background identity per
  detail session; ordinary state recompositions and metadata revisions do not start duplicate
  downloads. A settings change permits a deliberate retry.
- Metadata-only folder enrichment does not reread ROMs. An actual file scan is the boundary for
  local sidecar/image source access. Catalog changes, favorite edits and deletion stay off the
  UI thread.
- No device latency, memory or frame-time improvement is claimed. Batch revision coalescing
  and storage/network limits are audited with the coordinator/storage implementation.

## Verification

Source checks completed:

- `git diff --check` passed after the UI integration changes.
- Parsed both new resource files: 49 matching English/German keys, no duplicates, matching
  positional formatter types, and all referenced game UI strings resolve in default resources.
- Checked the settings panel's 30 English/German keys and copied both default English files
  into `values-en`, giving all 79 new labels explicit English overrides as well.
- Checked all `GameLibraryScreen` call sites, the detail route, coordinator method signatures,
  and settings panel package/signature against their implementations.

Regression coverage added:

- `GameDetailNavigationTest`: exact encoded file-ID round trip, one route argument, detail
  chrome/page-motion policy, and focus handoff when returning from game playback.
- `GameMetadataEditorPolicyTest`: valid/empty versus malformed/out-of-range years, distinct
  keys for alternative covers of one identified game, and preservation of legacy custom names
  when another metadata field is edited.
- `GameHomeRowsTest`: local cover/background URI handling (including spaces and `#`) and
  metadata year projection.
- The real repository fixture in `GameLibraryRepositoryQueryTest` covers cold cached detail
  loading, favorite/revision updates and disappearance after the last folder is removed; its
  source wake stub rejects unintended NAS wake calls.

### Combined host compilation

The recovered cached Kotlin 2.4.21 compiler successfully compiled the complete changed
coordinator, metadata providers/settings/parsers/storage/importer, catalog/query code and both
actual detail/library ViewModels together. It used real Android 14, serialization, coroutine,
OkHttp and Hilt APIs. The harness used explicitly marked compile-only interfaces for unchanged
FileAccess/FileStore/Wake services and AndroidX ViewModel/SavedStateHandle/scope, unrelated
preference fields and generated resource/BuildConfig constants. Changed preference/token
sections and the two pure editor functions were extracted verbatim.

All **21 pure JUnit tests** in that combined run passed: editor validation/title preservation/
image keys, metadata policy, storage merge and gamelist parser. The manifest records exact
source hashes and limitations. The harness does not instantiate Android ViewModels or run
SQLite, image decoding, transport, Hilt generation or Compose.

Full Android/Compose compilation and the Robolectric state/navigation tests therefore remain
the repository CI gate. The workspace's Android SDK/Gradle distribution is unavailable; the
cached compiler check is a narrower independent validation. No physical phone/TV visual or
D-pad test was available. Review the final PR CI results and test on a short landscape phone
viewport plus a TV D-pad before treating layout/focus as device-verified.
