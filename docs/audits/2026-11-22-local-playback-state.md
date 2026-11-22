# Local playback state: row persistence and integration audit

Date: 2026-11-22

## Scope and resulting behavior

Files favorites/history, DLNA resume state and YouTube local resume state now use
`local_playback.db`. Each favorite/history item is an independently replaceable
record. Source definitions, credentials and portable settings remain in their
existing stores.

This data is local user state, not an expiring browse cache. The existing limits
are preserved: 300 Files favorites, 500 Files history items, 500 DLNA progress
items and 400 YouTube entries. YouTube keeps exact milliseconds, known duration,
completion, the saved remote percentage and the delayed-echo set. Its existing
device-local scope is unchanged; this feature does not introduce account-scoped
YouTube history or change remote-history behavior.

## Storage and performance decisions

`LocalPlaybackDatabase` owns one process-lifetime scheduled IO writer. SQLite is
opened, migrated, read and written on that writer; constructing a store or reading
its synchronous memory front never opens SQLite on the UI thread. WAL is enabled.
Files/DLNA memory fronts remain bounded and cache their sorted snapshots. Point
progress lookups use a map instead of decoding/searching a JSON history array.

The primary key is `(namespace, item_key)`. A second index covers namespace and
descending recency. No speculative source index is maintained: source changes
already identify the bounded affected keys in memory, and use primary-key,
scope-qualified deletes. Payloads are per-item JSON so existing FileEntry and
watch-state formats remain compatible without SQLite JSON-extension requirements.

An ordinary warm report changes one memory record, encodes one payload and queues
one row replacement. A 250 ms batch window coalesces repeated reports for the same
key. It is not an indefinitely postponed debounce: continuous reporting still
commits during successive windows. A committed-key cache lets replacements skip
the retention query completely. Retention runs during initial loading or when an
insertion would exceed capacity, inside the same transaction as that insertion.

Before priming, mutations are replayed in their original global order. Fully
overwritten field patches are coalesced: 10,000 unprimed position updates for one
title retain one pending patch and preserve unreported duration/completion from
disk. Production mutation callers await readiness; the defensive unprimed queue
also has a hard 4,096-operation bound during persistent initialization failure.

## Migration and malformed legacy data

Each namespace imports its previous preference once. Imported rows and its
migration marker commit in one SQLite transaction. Only after that commit is the
old aggregate preference removed. A process ending between commit and preference
cleanup only repeats cleanup, not the import. A failed transaction leaves the
legacy preference and migration marker untouched for retry.

Malformed optional legacy JSON has a non-destructive fallback. The exact original
string and its original source-scope snapshot are retained in `playback_recovery`
before that namespace starts empty. The recovery row and migration marker commit
atomically; cleanup still occurs only after success. There is at most one recovery
record per namespace, with `INSERT OR IGNORE` preventing repeated copies on retry.
Other namespaces and new playback remain usable. Recovery contents are local and
are not included in settings export or TV sync; this change adds no recovery UI.
Diagnostic output records only the decoder's exception type, never its message
or embedded original JSON; the complete payload remains in the recovery row.

Source edits before migration do not turn an unreadable string into an empty
array. They preserve it and capture its old scopes. If the string later becomes
readable before import, only still-matching original source scopes qualify. A
storage failure while writing recovery still fails readiness and retains the
original preference; it is not misreported as a successful empty migration.

## Source identity, imports and delayed producers

Files source epochs live only in `file_playback_scopes`. DLNA source epochs live
only in `dlna_progress_scopes`. They are seeded for existing installations without
treating the initial source list as an edit. Every source-list mutation writes its
new epoch map in the same preference transaction as the definitions.

- Files retains an epoch when the existing `sameLocation` contract holds. Rename,
  credentials and wake-setting edits keep the data. Host, share, root or SAF-tree
  replacement changes the epoch.
- DLNA retains an epoch for the same saved ID and UDN. Rediscovery at a different
  IP or ContentDirectory URL keeps progress. Replacing the UDN changes the epoch.
- Removing and re-adding even the identical source ID/location gives it a new
  epoch. Pending rows, old disk rows and old producers cannot recreate its history.

Files captures its source epoch before stat/tag/subtitle IO and validates it again
under the source lock before publishing metadata, relay URLs or history. Relay URL
bindings carry that epoch. Android Auto now keeps its original Files relay URL in
`PlaybackMetadata`, instead of borrowing a later UI metadata cache entry.

DLNA likewise captures the epoch before network IO and validates publication under
the store lock. Fresh `MediaItem` objects carry a serialization-transient local
scope. PlaybackService uses that original Ready/Auto scope for a dedicated DLNA
progress report, including completion. Manual watch/unwatch commands deliberately
operate on the currently configured source. The general repository reporting API
is not used by PlaybackService for DLNA.

Pending deletes include the old epoch. They cannot erase a replacement PUT with a
different epoch, including the interval in which a failed in-flight transaction is
being requeued. Newer queued values take precedence over an older failed batch.

SettingsBackupManager still exports source definitions rather than local
favorites/history/progress. Companion settings continue using the same logical
backup contract. Epochs, transient metadata scopes, SQLite state, recovery rows
and pending writes are not portable. Android cloud backup/device transfer already
exclude databases and preferences; those rules remain unchanged.

## Cold start, UI and lifecycle integration

FilesRepository and DlnaRepository await local priming before resolving resume
positions or reporting changes. Files favorite actions, Games' Files-history
recording and Android Auto's stored favorites/recent/search pages do the same.
Auto cannot permanently cache an initial empty history page. Files landing pages
already observe `FileStore.revision`; hydration publishes a revision and updates
stored rows without a new NAS listing. Home's Games rows use the game library
index, not Files-history JSON.

SmartTubeEngine primes local progress before fresh card mapping, playback resume
selection or history reporting. Cached card percentages observed before hydration
do not overwrite persisted exact time. Hydration publishes the existing progress
revision. A fresh server response still follows the original acknowledgement,
delayed-echo and other-device conflict rules.

Pause, completion, explicit watch-state edits and terminal playback reports request
an immediate drain. Waiting for it suspends the caller for at most 1,500 ms; it
does not block the main/lifecycle thread. The writer outlives PlaybackService, as
does its existing final-report coroutine scope. YouTube flushes local state before
history-paused or network-report early returns. A failed/timed-out explicit flush
becomes a repository failure/exception, and PlaybackService unwraps the local
results into its report error handling. The existing local-source notification
policy is unchanged: these failures are logged and remain retryable.

Write failures retry up to three times with bounded backoff. Pending latest values
are retained afterward for a later update/flush. A new readiness attempt always
owns a fresh Deferred: an earlier attempt finishing its failure notification
cannot poison a successful retry. Favorite-action readiness/write failures use
the existing Files error surface instead of escaping the ViewModel coroutine.

## Audit findings corrected before completion

The implementation and its integration received an independent read-only review.
The review and local audit identified and corrected these issues:

1. Retention SQL was initially executed on every report; it now runs only for
   initial loading or actual over-capacity insertion.
2. Repeated cold reports originally accumulated replay closures; overwritten
   field patches now coalesce without losing duration/completion.
3. Readiness retry could reuse a Deferred whose previous failure had not yet
   completed; each attempt now creates its own completion object.
4. DLNA delayed reports originally used the current source epoch; player metadata
   now carries and enforces the original one.
5. Android Auto's Files reports could borrow a newer metadata cache identity;
   the original relay binding is now retained.
6. Files metadata could accept identical remove/re-add during network IO; its
   epoch is now captured before IO and checked before publication.
7. Flush failures were initially discarded; terminal paths now propagate them.
8. A retired-scope delete arriving during failed-batch requeue could suppress a
   replacement PUT; retry merge now applies the same scope protection as enqueue.
9. Tolerant legacy decoding could discard unreadable originals; transactional
   local recovery now preserves them while allowing normal use.

## Validation and evidence

Local API compilation succeeded for the actual row writer/front, FileStore,
DlnaStore, YouTubeLocalProgress, their models and qualifiers, against real Android,
serialization, Compose, network and coroutine APIs. The changed AppPreferences
section and unchanged credential/WakeOnLAN/resume helpers were extracted verbatim
for this isolated run. Unrelated Android application wiring and logging use narrow
compile support, so this is not a substitute for the full application build.

The local JVM run passed 21 tests: six new `LocalPlaybackRowsTest` cases plus all
15 existing `YouTubeLocalProgressTest` conflict/exact-time cases. The new row tests
verify one-row encoding/writing among 500 records, bounded snapshots, cold patch
coalescing, cross-key ordering, retired-source pruning and non-inherited fields.

The checked-in Android/Robolectric suites cover the actual SQLite and integration
paths (to be executed by full CI):

- `LocalPlaybackDatabaseTest`: transactional one-time import, cleanup order,
  restart, 100 reports coalescing into one row, no warm retention query, write and
  migration failures, atomic capacity eviction, scope-qualified deletes,
  overlapping readiness retry, failed-batch/retired-delete race and recovery
  transaction rollback/reopen.
- `LocalPlaybackStoresPersistenceTest`: legacy Files/DLNA import, exact time,
  ordering, orphan exclusion, one-row updates at 500 entries, source removal and
  identical re-add, replacement UDN, source import/backup boundaries, transient
  metadata, malformed originals and original-scope recovery.
- `YouTubeLocalProgressPersistenceTest`: exact milliseconds within one percent,
  legacy percentage-only entries, completion with/without duration, one-row update
  at 400 entries, cached-card/cold-load ordering and malformed-state recovery.

SQLite query-plan evidence used the actual schema and read statement extracted
from `LocalPlaybackDatabase.kt`, with 500 fixture rows. The bounded history read
uses `playback_rows_recent (namespace=?)`; scoped deletion and point lookup use
`sqlite_autoindex_playback_rows_1 (namespace=? AND item_key=?)`. No full-table sort
or scan is required for those operations. The actual writer's row-count/retention
assertions are in the Robolectric tests, not a separate reimplementation.

No Android device frame-time, disk-latency or NAS benchmark is claimed. Device
process-kill testing and full app/DI/lifecycle validation remain release checks;
an immediate OS process kill before a debounce/flush completes cannot be made
durable by an asynchronous writer. Permanent storage failure is surfaced and never
triggers destructive database recreation or silent replacement of saved state.
