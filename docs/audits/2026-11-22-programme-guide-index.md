# Indexed programme guides for IPTV and TVHeadend

Date: 2026-11-22. Base: `b84c2391`. This feature was audited after implementation, including the repositories, source registry, upgrade path and existing cache policies.

## Delivered behavior

`ProgrammeGuideIndex` is a shared, disposable SQLite store in `programme-guides-v1.db`. IPTV imports XMLTV into staging generations and queries the committed generation by time window and, where requested, channel. A process restart no longer requires parsing a still-fresh guide again. TVHeadend uses the same index for its persisted offline guide and merges refreshed channel windows inside SQLite.

The database contains programme fields and generation metadata, not raw XML or whole-guide JSON. Downloaded XML/XML.GZ files remain in the existing private file cache. The feature does not add a background discovery pass, automatic library scan or new wake request. Provider requests remain driven by the existing screens, playback and explicit refresh/reconnect paths.

## Performance and capacity

| Area | Behavior and bound |
| --- | --- |
| IPTV import memory | A streaming parser holds one programme and at most 256 pending SQLite records. It does not build a whole-guide programme list. Matched channel aliases are capped at 20,000; unrelated channels do not accumulate in that map. |
| XML input | Existing limits remain: 20 configured IPTV sources, at most three playlist-provided guide URLs, 20 MiB playlist downloads and 400 MiB per raw guide download. Expanded gzip input is now also capped at 400 MiB. |
| Parser limits | At most 100,000 accepted programmes per guide file, at most 300,000 distinct records per combined source generation, depth 64, bounded tags/sections and programme text. Exceeding an import bound aborts that generation instead of publishing a silently truncated guide. |
| Indexed rows | Programme text, identifiers and artwork URLs have explicit field bounds. Each generation is limited to 64 MiB of accounted record payload; committed caches target 256 MiB globally. SQLite page, index and WAL overhead is additional. |
| Replacement space | Old usable rows remain while a replacement is staged. A hard combined accounted-payload limit of 320 MiB permits one 64 MiB replacement above the 256 MiB target. Concurrent imports can be rejected at this bound; they cannot erase their old guide to make space. |
| Read path | `guide_window(owner,generation,start_epoch,ordinal)` and `guide_channel_window(owner,generation,channel_id,start_epoch,ordinal)` support exact window reads. Only selected rows become Kotlin programme objects. A guide-wide UI request still hydrates its requested guide-wide window. |
| Writes | Transactions contain at most 256 emitted records, followed by a short atomic publication transaction. Generation counts/bytes update once per batch. A reader keeps one head/row snapshot while hydrating its requested result. |
| IPTV freshness | The existing six-hour playlist and twelve-hour guide policies remain. The default indexed guide covers six hours back and 48 hours ahead, extended when a requested one-to-72-hour window needs it. Unrelated preferences and cosmetic source names do not change the guide fingerprint. |
| Offline retention | Committed guide data is readable for at most 30 days and never when its timestamp is in the future. Expired rows are removed during subsequent successful maintenance/imports; capacity limits remain applicable meanwhile. |
| TVHeadend | The persisted guide retains the existing 12-hour window and earliest 8,000-event limit. Fresh event ids win; other channels' overlapping rows are copied within SQLite. Provider pagination and its existing completeness logic are unchanged. |

The two indexes narrow start time and channel while the stop predicate filters actual overlap. This is not a claim that SQLite can avoid examining every older overlapping candidate for arbitrary very long programmes. The bounded persisted horizon and channel-specific index keep ordinary channel queries small.

## Atomicity, ownership and source identity

A generation is invisible until all selected guide files parse successfully and the publisher switches its head in one transaction. Truncated XML, a later feed failure, cancellation, count/byte limits or a changed source discard staging rows. The prior committed guide stays available. A successful download that falls back to a prior raw file is marked as fallback and retried under the existing reconnect policy. Ordinary repeated requests during an outage are throttled for one minute; manual refresh and a new reconnect revision bypass that throttle.

Each import advances an owner epoch. If two repository instances overlap, the later import invalidates the earlier producer; the earlier producer cannot overwrite the newer generation. Removing a source advances the same epoch and deletes its versions through foreign-key cascades. Cancellation cleanup removes only that writer's unpublished generation. Old staging rows left by a killed process are reclaimed on a later import.

IPTV fingerprints use length-framed SHA-256 over source/configuration inputs, guide URLs and the playlist channel identities/names used for matching. Account-bearing configuration is not stored as plaintext scope metadata. IPTV registry cleanup retains only enabled source owners, rechecking the current source set before removal. Old repository results and imports check the current semantic source configuration. TVHeadend reuses its existing server/account scope, validates that scope around migration/merge and removes its indexed guide with the existing disable/clear action.

The database is excluded from Android backup by the existing database exclusion. No cache field is added to portable settings, credentials, explicit backup or companion synchronization.

## Artwork cache contracts

IPTV's existing private raw XMLTV cache already contains provider artwork URLs, including signed/query URLs. The indexed programme retains those bounded URLs so covers continue to work without another provider request. The payload is source scoped, capacity bounded and retention limited; no raw provider/artwork URL is added to logging.

TVHeadend follows its existing authenticated display-snapshot contract: persisted artwork removes embedded user information, query parameters and fragments, and the current authenticated image path handles access. Applying that normalization to arbitrary IPTV artwork would break signed covers, so the store deliberately preserves the two existing provider contracts.

## Integration audit findings and fixes

1. **The previous IPTV convenience parser materialized all programmes.** Production import now uses a callback stream. The convenient list API remains for small callers and preserves its previous maximum-result behavior. Production strict imports fail on excess records. The parser now requires a complete balanced `tv` document; EOF inside tags, comments, CDATA or declarations fails. It remains a bounded custom XML tokenizer, not a full XML-schema validator.
2. **A partially downloaded/parsed guide could replace usable data.** Generations are staged separately and published only at successful completion. Multiple guide URLs belong to one publication. The test covers a later malformed feed after an earlier feed was already parsed.
3. **A provider HTML playlist response could silently change the guide scope to an empty playlist.** Playlist responses are now parsed and checked before replacing the last good raw playlist. A malformed response falls back to the prior valid playlist. A legitimate empty `#EXTM3U` remains supported, as do valid headerless channel lists.
4. **Recreated repositories reparsed the entire XML guide.** The scope is deterministic across process/repository recreation and cosmetic label changes. A fresh indexed generation serves channel/window requests even after its raw XML file has been removed. Source/account, guide URL, user agent or channel-matching changes use another scope.
5. **Source changes and concurrent refreshes could publish old work.** Repository validity, refresh revisions, coroutine cancellation and store owner epochs are checked before writes/publication. Independent review identified the overlapping-import risk; a newest-import-wins test now covers it.
6. **Clock rollback could make a persisted timestamp indefinitely fresh.** A future `loaded_at` is now a cache miss. Raw-file and playlist RAM TTL checks also require a nonnegative age. A dedicated index test covers clock rollback.
7. **Opening one TVHeadend cache key hydrated every saved JSON record.** `TvheadendDiskCache` now reads individual keys through `BrowseDiskStore.read(scope,key)`, with striped restore locks and cached missing keys. Existing A-to-B-to-A and clear-during-decode identity guards remain. A new test fails if a one-key read calls the whole-scope reader.
8. **TVHeadend guide refreshes repeatedly decoded/rewrote an 8,000-event list.** Normal persistence now merges directly in SQLite. A one-time lazy migration reads the prior EPG JSON, commits it to the index and replaces that legacy payload with an empty list. Migration also runs before a first forced per-channel refresh, so another channel's old window is not lost. Other channels, recordings, timers and NOW display snapshots keep their existing storage and fallback behavior.
9. **Provider boundary and ordering semantics differ.** IPTV retains exclusive overlap boundaries; TVHeadend fallback retains its existing inclusive boundaries. Start time plus emission ordinal provides deterministic ordering, and the first occurrence of a duplicate IPTV event id wins as before. Fresh TVHeadend events win over stored ids. Pagination is untouched.

## Verification

- The isolated Kotlin 2.4.21 compile uses the actual index, IPTV repository/parser, TVHeadend disk cache and shared models against real Android, OkHttp, coroutine, serialization and Hilt APIs. Only unchanged transport/configuration declarations were extracted; the `BrowseDiskStore` interface seam is compile-only and is not executed. This is not a full application/Hilt build.
- **26 JVM tests** cover parser streaming and limits, malformed/truncated input, cancellation, text bounds, refresh/reconnect behavior, semantic source identity, TVHeadend display-cache policy and per-key restoration races. Local execution results are recorded in the feature validation manifest and JUnit log.
- **Thirteen API-29 Robolectric index tests are included:** staging visibility/reopen, failed/cancelled imports, exact provider window boundaries, row/byte limits, source removal, overlapping import rejection, duplicate/merge semantics, source cleanup/artwork contracts, future timestamp expiry and channel query-plan selection.
- **Five API-29 Robolectric repository tests are included:** process-style index reuse without XML, forced truncated-feed recovery, malformed-playlist fallback, multi-feed atomicity and source change during a real HTTP request. The existing guide-URL-change regression was also wired to the real index.
- A host SQLite check executes the production schema, insert and SQL merge statements against a **100,000-record fixture**. A two-hour, one-channel query hydrates **four rows** using `SEARCH ... guide_channel_window`; a guide-wide window uses `guide_window`, with no temporary sort tree for the channel query. The check also verifies fresh-event precedence, other-channel retention, window/count trimming, generation counts/bytes, publication rollback and foreign-key deletion.
- `git diff --check` passed for the feature.

At the feature checkpoint, the standalone Android tests and full app/Hilt build remain CI gates; the host SQL check and isolated JVM compile are not substitutes for those gates. Phone/TV memory, device latency and NAS traffic have not been measured. No percentage speedup or real-device benchmark is claimed.

## Independent integration review

Root review checked repository queries, raw-cache fallback, imported/multi-feed ownership,
TVHeadend legacy migration, registry removal, SQL publication and limits. It found and fixed
a per-record source-validation cost: the source callback reads encrypted IPTV configuration,
so repeating it for every programme defeated part of the streaming benefit. Validation now
runs at both ends of each bounded transaction, periodically through parser events (including
unmatched feeds), and before publication. Coroutine cancellation remains checked for every
parser event and input read. A regression asserts the bounded transaction check count.

The review also found that an asynchronous TVHeadend clear could erase a different account's
new generation. The display-cache clear now returns its captured scopes; SQL removes only those
scopes. Foreign-key constraints prevent a deleted staging generation from republishing, while
a different account's current writer keeps its epoch. Two tests cover the old-scope/new-writer
race and non-resurrection of a cleared writer. The existing TVHeadend account-switch policy
remains unchanged: its display cache deliberately discards the prior active account scope.
