# Artwork analysis cache audit

## Feature

Face-crop results now use a bounded SQLite table. The 2,001st result evicts one cold
record rather than clearing all 2,000 previous analyses. A successful negative
result survives process recreation and avoids decoding the same image again.

Image identity includes URL revisions, canonicalized request headers and the
successful Coil result's cache key (including its provider/account scope). Only a
SHA-256 digest is persisted. The old URL-only preference cache is intentionally
not imported: it cannot prove which account or image revision it analyzed.

## Integration audit and fixes

- Database operations stay on IO; normal composition reads only the 256-entry RAM cache.
- Analysis is serialized and checks memory after admission: several visible layers
  cannot decode/analyze the same image concurrently.
- Both ordinary artwork and cross-fading backdrops pass Coil's actual cache identity.
- Invalid/expired entries behave as cache misses. SQLite errors do not fail the UI.
- Negative results and valid coordinates have distinct representations; NaN and
  out-of-range points are rejected.
- The original 1.5-second crop-readiness deadline and default alignment remain.
- This is rebuildable device-local metadata, excluded from logical backups and TV sync.

## Performance verification

Regression tests check selective eviction across reopen, negative-result reuse,
expiry, account/version separation and the actual SQLite query plan. A repeated
read fixture verifies 100 hits cause zero SQL writes; recency updates occur at
most once per hour per entry. Lookups use the primary-key index; eviction uses a
dedicated recency index. CI execution is recorded in the integration audit.

Local validation: the production SQLite helper compiled against real Android API
classes, and both JVM identity tests passed. The full Android SQLite/UI tests
are intentionally not represented as locally executed without an Android runtime.

No physical-device frame-time improvement is claimed. Explicit same-URL artwork
refresh is a separate integration operation; expiry does not override Coil's
network cache policy or force background downloads.

## Explicit same-URL refresh and integration audit

Accepted server metadata/artwork edits now invalidate the affected item's old and refreshed
poster/backdrop/logo/ancestor-image URLs after the existing metadata polling. A persistent,
account-scoped image revision is committed before selective RAM/disk eviction. Both Coil RAM
budgets participate; their shared image disk cache is processed once. Old in-flight requests
can finish under an old key but cannot make that key current again. The resulting Coil disk key
also versions face/crop analysis, so a new image cannot reuse an old crop decision.

Only configured Plex/Jellyfin/Emby image endpoints use this policy. External/signed CDN images,
photos and Telegram models retain their existing fetch/key behavior. The memory key now includes
the same hashed provider/account identity as the disk key, including when an in-place card reuses
a successful memory key across a token-free snapshot/live-response transition.

A bounded 512-entry RAM lookup avoids repeated SQL and dispatcher hops. Before the first explicit
edit, one empty-table check serves all subsequent images without per-image SQL. Reads do not
update timestamps or serialize a full JSON map. Only explicitly refreshed images create a tiny
SQLite revision row (a digest and random revision; no URL or credential). These invalidation
records are retained: expiring one would risk reviving an old disk key. Binary images stay in
Coil's size-bounded file cache. The SQLite metadata is local/rebuildable and is not part of
portable backups or TV setting synchronization.

The UI observes only explicit refresh events and restarts matching image requests. During audit,
creating a new otherwise-equal `ImageRequest` was found insufficient: Coil Compose compares
`memoryCacheKeyExtras`. A temporary refresh marker now changes that equality, and the interceptor
replaces it with the persisted revision before caching. This avoids per-session cache duplicates.
Crossfade backdrops create a new layer and retain the previous valid image until replacement loads.

Four added Robolectric regressions cover same-URL/account isolation across reopen, late-old-key
unreachability, no writes during 1,000 ordinary reads, indexed selective revisions and the actual
Coil Compose request-equality contract. They await the next full CI run. The original analysis-cache
feature's Android compile/unit-test CI passed in run 45006273694 at commit 3003571cbff011796a4ecf06f9b55ee6d8351862.

Primary API checked: [Coil 3.6.3 model equality](https://github.com/coil-kt/coil/blob/3.6.3/coil-compose-core/src/commonMain/kotlin/coil3/compose/LocalAsyncImageModelEqualityDelegate.kt)
and [request cache-key builder](https://github.com/coil-kt/coil/blob/3.6.3/coil-core/src/commonMain/kotlin/coil3/request/ImageRequest.kt).
This is workload/correctness evidence, not a measured phone/TV latency improvement.

Follow-up performance audit found that retaining the original request URL in the analysis identity repeated face detection across token-free snapshot/live URL variants even when Coil resolved them to the same scoped image. The analysis key now uses that resolved identity when available; URL/header isolation remains the fallback. A regression covers reuse, account isolation and unresolved-URL separation. Digest encoding also avoids per-field byte buffers and per-byte formatting allocations.

All three pure analysis-identity tests passed locally after this follow-up against the actual production helper compiled with Android API signatures.
