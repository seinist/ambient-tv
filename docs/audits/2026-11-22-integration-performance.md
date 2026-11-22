# Kodi / RetroArch follow-up implementation and audit

Base: `b84c23914e5602160da96883e3238c5eb4fd9f6a` (PR #445).

## Acceptance contract

Every completed feature receives a focused functional, integration and performance
audit before it is considered finished. Measurements distinguish local fixtures,
CI verification and physical phone/TV acceptance; no estimated speedup is presented
as a device measurement. Caches must not wake a NAS or start a library scan.

Permanent user choices remain portable through the existing logical backup and
companion contracts. Rebuildable caches are device-local. ROMs, artwork bitmaps,
save-state binaries and credentials keep their existing appropriate storage.

## Work packages

1. Targeted game-library database queries, indexed lookups and display ordering.
2. Persistent audio tags and cached-first, bounded incremental folder enrichment.
3. Shared reactive controller selection and configurable system-aware touch UI.
4. Selective artwork analysis eviction and explicit image refresh integration.
5. Row-based local playback state, preserving migrations and source identity.
6. Bounded indexed programme-guide storage and time-window reads.
7. Game save-state actions, off-main-thread persistence and core input contracts.
8. Integrated performance checks, migration/backup review and regression validation.

The comparison's longer-term optional features (a new NAS video/NFO library,
external game-cover services and speculative execution such as run-ahead) depend
on separate product/core acceptance and are not prerequisites for these existing
integration improvements.

Feature-specific documents record changes, findings, fixes, tests and outstanding
device-only checks. This index is updated as those audits complete.

## Feature audit index

| Feature | Functional and integration audit | Performance evidence |
| --- | --- | --- |
| Game-library queries | [Library query audit](2026-11-22-game-library-queries.md): migration, effective titles, imported favorites, source/folder identity, initial focus and scan revisions. | A 100,000-game SQL fixture exercises bounded 24-item home rows, one-game lookups and indexed system/search sections. The complete library has no new arbitrary item limit. |
| Audio metadata | [Audio-tag audit](2026-11-22-audio-tag-cache.md): file-version identity, negative caching, source changes, cancellation and cached-first folder updates. | Four concurrent tag readers; bounded 20,000-record/32-MiB accounted cache; cached tags avoid another tag-file open. |
| Phone/TV controls | [Controller UI audit](2026-11-22-game-controller-ui.md): shared layouts, system variants, input-triggered visibility, companion relay and lifecycle cancellation. | 5,904 geometry cases check reachable, non-overlapping targets; unchanged aggregate input suppresses downstream callbacks. |
| Artwork analysis and refresh | [Artwork audit](2026-11-22-artwork-analysis.md): account/image identity, selective eviction, same-URL changes and Coil request equality. | Indexed 2,000-row analysis cache, 256-entry hot cache, successful-negative reuse and zero writes for repeated ordinary reads. |
| Local playback rows | [Local state audit](2026-11-22-local-playback-state.md): transactional migration/recovery, source epochs, cold hydration, Android Auto and terminal reports. | One warm report encodes and replaces one row; a 250-ms writer coalesces repeated reports. Existing 300/500/500/400-item limits remain, and replacements skip retention SQL. |
| Programme guides | [Programme-guide audit](2026-11-22-programme-guide-index.md): staged imports, source changes, malformed responses, offline fallback, TVHeadend migration and scoped removal. | 256-record import batches; a 100,000-programme fixture returns four rows for a two-hour single-channel query using its channel/time index. |
| Save-state actions | [Save-state audit](2026-11-22-game-state-actions.md) and [core serialization audit](2026-11-22-core-state-contracts.md): rename/delete/UNDO, compatibility, checkpoints, core quirks and focus return. | Native capture/probing waits on a worker; file/JPEG operations run on IO; a busy gate prevents duplicate expensive operations. |
| Core input | [Core-input audit](2026-11-22-core-input-contracts.md): owned descriptors, fractional triggers, short taps, coherent snapshots, companion metadata and netplay v5. | One JNI delivery per changed player, fixed native bit masks, reusable capture arrays and one-pass Kotlin device aggregation. Native sanitizer tests exercise the production input code. |

## Shared integration boundaries

### Settings, backup and synchronization

The controller style and per-system variants are fields of the existing
`RetroInputConfig` map. `SettingsBackupManager` exports that complete map as
`retroInputSettings` and reloads it after import. `CompanionBackupPolicy` already
includes the same field in the portable preferences group. No independent device
setting or second synchronization path was added. The configuration round-trip
test includes every new style value and multiple system variants, and old maps
retain default values.

Game-library presentation still uses the existing portable folder/override
contracts. The database projection can be rebuilt from those values. Companion
filtering continues to omit device-specific SAF permissions and unrelated game
metadata. Save-state binaries remain local files with exact core-build checks.

Artwork analyses, image invalidation metadata, audio tags and programme guides are
device-local derived data. The existing Android extraction rules exclude all
databases; the logical settings backup does not acquire these caches. Local Files,
DLNA and YouTube playback records retain their prior device-local backup boundary.
Credentials retain their existing encrypted configuration storage. A provider's
already cached signed artwork URL is not treated as a newly portable credential.

### Network work, cancellation and lifecycle

The caches add no timer that discovers or scans a NAS library. An uncached audio
tag is requested only by the existing folder/detail workflow, with bounded
concurrency; progress/favorite changes do not repeat the directory listing.
Programme-guide requests retain the existing view/playback/manual-refresh entry
points and reconnect behavior. Explicit artwork edits invalidate only affected
configured-provider images; ordinary drawing does not initiate a global refresh.

Source/account identity is part of cache and producer validation, including
asynchronous completion. An old source must neither publish into a replacement
source nor clear its data. Cancellation discards unpublished guide generations;
failed multi-feed parsing leaves the committed guide available. Input pause,
disconnect and session changes release or suppress held controls through the
existing local and companion lifecycle paths.

### What the performance evidence establishes

The fixtures and query plans establish less unnecessary work: selected rows instead
of full-list hydration, per-item writes instead of aggregate serialization, reuse
instead of repeated parsing/analysis, and bounded batches instead of unbounded
parallelism. They do not establish a percentage FPS, startup, battery or network
latency improvement on a particular phone, TV or NAS. Emulator core execution and
provider response latency remain external to these workload reductions.

## Integrated verification and remaining device acceptance

The first combined run, [45009900249](https://github.com/seinist/ambient-tv/actions/runs/45009900249),
compiled the Android application and instrumentation sources, applied the pinned
LibretroDroid patch stack, passed native sanitizer contracts and all additional
ABI/Slang/preset jobs, and executed 5,480 app tests. Only the two newly added
save-state dialog tests failed. Their fixture used TV controls despite phone
dimensions and skipped the explicit edit gesture; the corrected fixture retains
and strengthens its behavior assertions. Earlier compilation findings also
removed obsolete scan reload calls and an unsupported top-level Compose import.

The local-state migration package and the final artwork integration corrections
are included in the next combined gate. The merge criterion is a successful CI
run for the exact final PR head, comprising:

- All application unit/Robolectric tests and Android instrumentation compilation.
- LibretroDroid unit tests and the ARM64 Android/native build.
- The remaining ARM32, x86 and x86-64 native builds, including ELF alignment checks.
- Native input contracts under AddressSanitizer/UndefinedBehaviorSanitizer.
- All four Slang runtime builds and bundled preset/API validation.
- Repository script checks and shader-source validation.

The final commit, run and outcome are recorded on
[PR #446](https://github.com/seinist/ambient-tv/pull/446), so this audit does not
mistake an earlier feature checkpoint for final integration success. Each feature
document separates its locally executed checks from Android and device tests.

Physical-device acceptance remains: a large library on the user's NAS; cold and
warm folder/guide opening; background/foreground and TV standby during gameplay;
a large-core save/load; direct and phone-relayed controllers with partial triggers;
and netplay between two version-5 peers. The workspace has no physical phone/TV,
so no measured device frame-time, battery, thermal or end-to-end latency result is
claimed. Optional new NAS-video/NFO products, online cover providers and run-ahead
remain outside this existing-integration change.
