# Persistent audio tags and progressive music folders

Date: 2026-11-22. Base: `b84c2391`. This feature was audited after implementation, including its callers.

## Delivered behavior

`AudioTagCache` stores the small display fields needed by the Files browser and player in `audio-tags-v1.db`. The folder browser reads cached fields first, then fills missing fields in batches. A successfully read file with no supported tags is a cache hit too; a failed or truncated network read is not.

The scope includes source id, root, account and transport/server identity. Scope and file identifiers are length-framed SHA-256 digests; the table does not store the host, username, path or credentials. Entries additionally match file size, modification time, format and parser revision. A provider without a usable modification time or size receives a six-hour lifetime, and a backwards clock does not extend that lifetime indefinitely.

The cache is disposable. It adds no preference, credential, backup field or TV-sync payload. Android backup already excludes databases, and the app's explicit settings backup remains unchanged.

## Performance design

| Area | Change and bound |
| --- | --- |
| Cold/recreated view model | SQLite survives process recreation; unchanged, cached tracks do not require tag-file opens. The normal directory listing and selected-track stat/cover lookup still occur. |
| Folder lookup | One indexed lookup per 300 requested entries; no whole-cache JSON decoding or hydration. |
| Tag readers | Four workers per pass and a singleton semaphore shared with playback limit actual tag-file reads to four across retained browsers and the player. Unsupported formats skip opening the file altogether. |
| UI and disk updates | At most 12 read completions per batch, with a 100 ms deadline for a small partial batch. One map copy and one short transaction per batch, not per file. A bounded result channel provides back pressure. |
| Album ordering | Intermediate batches update labels in place. Only a completed pass sorts tracks. File ids and non-audio slots remain unchanged. Track ids are encoded once per track before sorting, rather than during every comparator call. |
| Cover selection | A single minimum selection replaces sorting all candidate cover images. Offline snapshot tag restoration does not register a new network cover request. |
| Capacity | At most 20,000 rows and 32 MiB of accounted metadata/record payload. SQLite page/index/WAL overhead is additional. LRU eviction removes old rows selectively. |
| Maintenance | Trigger-maintained counters make capacity checks constant-time. Hits normally do no write; LRU touches are batched at most once per six hours per row. Old source scopes are cleaned once per observed scope, not once per tag batch. |

The existing 300-tagged-files-per-folder budget remains in place. This feature does not crawl subfolders, create a global music library, contact a new service or wake a NAS automatically.

## Integration audit and fixes

1. **Playback was rereading the same tags.** `FilesRepository.getMetadata()` now uses the same versioned read-through cache and source-identity check as the browser. Cached negative results are reused too. The existing source lock still governs final playback registration and progress binding.
2. **Playback progress was repeatedly listing the NAS.** `FileStore.record()` changes the general store revision. The old Files view model refreshed the remote folder for every such revision, including favorite changes. The observer now refreshes remote content only when source or ROM-folder configuration changes; progress and favorites update local UI state. This also prevents repeated cancellation/restart of tag work during playback.
3. **Old work could outlive navigation.** Refresh, cancellation, landing-page transitions and each new tag pass now invalidate a generation and cancel the previous job immediately. Workers check ownership before publication and writes. Cancellation interrupts blocking tag reads, and transactional writes roll back if validity is lost.
4. **Account changes could retain the old folder UI.** The browser now checks the same source/account scope on source revision, and accepts listing/snapshot results only for the current complete source configuration. New accounts/roots cannot inherit old tag results.
5. **Offline snapshots lacked tag metadata.** Snapshot restoration runs a cache-only pass before the directory request finishes; it never reads missing tracks or registers a new cover. A partial cached album is not marked complete or reordered prematurely.
6. **Tagless and failed files were indistinguishable.** `AudioTagReader.readForCache()` preserves IO/truncation failures. Real empty/unsupported tag results can be persisted, while connectivity errors remain retryable. Metadata text is capped at 512 characters per field, and small files allocate only the bytes actually available.
7. **Source removal and stale covers.** Removing a source through Files deletes its audio-tag rows. Rows left by other source-replacement/import paths remain scope-isolated and capacity-bounded. A live folder with no cover clears the prior cover URL. No cache content becomes portable user data.
8. **Independent integration review:** the source/configuration baseline is captured with the initial revision before launching its observer. A source edit between view-model construction and coroutine collection cannot be mistaken for an unchanged configuration. The review also checked offline pass ownership, the player read-through path, cancellation rollback and preservation of the selected track's queue order.

## Verification

- Local Kotlin 2.4.21 compilation passed for the actual cache, loader, parser and file models against real Android, coroutine, serialization and Hilt APIs. Unchanged transport-facing declarations (`SeekableMedia`, the WOL packet helper, archive extension constant) were extracted verbatim for this isolated compile; this is not an app/DI build.
- **12 JVM tests passed locally:** the existing tag/parser/album tests plus seven loader tests and the new IO/truncation regression. They cover cached-first publication, the four-worker limit, positive/negative hits, retry after network failure, invalidated-generation cancellation, cache-only offline restoration, partial publication before a slow track finishes, player reuse, and final album ordering.
- **Nine API-29 Robolectric cache tests are included for the full CI run.** They exercise process-style reopen, version/parser/source isolation, unknown-version expiry, selective LRU/byte eviction, exact size counters, rollback on generation change, the shared four-read budget, and a folder query within a 3,000-row catalog. They have not been executed by the isolated local harness.
- A host SQLite check executed the production schema and triggers against **20,000 rows**. The folder query hydrated **300 rows** and used `SEARCH audio_tags USING INDEX sqlite_autoindex_audio_tags_1 (scope=? AND file_key=?)`. Trigger counts/bytes matched `COUNT/SUM` after insert, update and selective deletion. This is SQL-shape evidence, not an Android/device speed measurement.
- `git diff --check` passed for the feature.

Full app/Hilt compilation, Robolectric execution and physical NAS/phone/TV performance remain explicit integration gates. No percentage speedup or device latency is claimed from the isolated tests.
