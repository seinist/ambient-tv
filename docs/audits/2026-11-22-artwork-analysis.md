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

## Independent refresh integration audit and corrections

The follow-up audit traced the actual Compose placeholders, both Coil loaders, the network auth interceptor, metadata-edit polling and image variants used by the playback settings panel. It found four functional gaps and two related race conditions, now addressed:

1. **Reverse proxies could select different providers for authentication and cache scope.** Both paths now use one resolver with scheme, normalized host, port and the longest complete configured base path. Jellyfin and Emby paths are encoded through the same `HttpUrl.encodedPath()` operation as their request builders, including spaces and Unicode. Ambiguous root mappings do not guess between accounts. Explicit `Authorization` still takes precedence; explicitly selected credentials use a digest of their headers and complete URL instead of inheriting the currently selected automatic account. Signed external URLs retain their query semantics.
2. **A source change between key selection and network authentication could cross scopes.** Managed requests carry an internal hashed scope fence, removed before network dispatch. The auth interceptor derives the checked scope from the exact token it will inject via `BrowseScopes.forMediaProviderToken()`. It never validates one token using a second token read. An obsolete scope fails before fetching bytes, and any legacy token query is updated to agree with automatic header authentication. A deterministic regression simulates token A on the first credential read and token B on the second.
3. **Global placeholders and in-place continuity were keyed by URL alone.** Reuse now requires the current image/account identity and its exact revision, including when an old in-flight request repopulates an old memory key. An unknown revision does not authorize reuse. Request extras also carry the selected identity, so Coil Compose observes an account change even when the URL is unchanged. Crossfade layers from another account/header scope are not drawn while the new layer is prepared. Crop state is reset with that identity as well.
4. **The playback panel's resized URL could survive a same-image refresh.** Invalidation now follows the underlying resource identity: Plex transcode dimensions and Jellyfin/Emby resize/encoding parameters do not divide the revision. Binary disk keys still keep those variants distinct, preserving decode-size behavior. Image ids and provider tags remain distinct. All loaded RAM variants share the revision identifier and are selectively evicted; later requests for another size use the newly committed revision too.
5. **A SQLite write failure silently retained the old image.** A failed transaction now creates a targeted in-process revision, disables persistent binary reads/writes for that revision and still emits the reload event. The caller receives an explicit nonpersistent result and explains that the user should refresh again later for the change to survive a restart. It does not claim the image download already succeeded. A later successful refresh clears the transient state and persists a new revision. A `PRAGMA query_only=ON` test exercises this path on real Android SQLite. Transient invalidation records, like durable tombstones, are created only by explicit edits and are retained for the process lifetime rather than allowing old images to become current again.
6. **Two edit events could conflate while an image's initial revision was still unknown.** The observer now compares that image's retained revision in addition to the latest event's URL set. A current nonempty revision differing from an unknown previous value triggers a reload; unrelated empty revisions do not restart ordinary images. A pure regression covers both cases.

Metadata-edit polling captures the edited provider scope and checks it around each response and refresh. Waiting behind another refresh cannot invalidate or replace the newly selected account's UI. An analysis result without a disk key now includes the memory key's identity/revision extras in its derived focus identity, so a temporary cache-disabled retry cannot reuse the previous image's crop result.

### Performance audit of the corrections

- Ordinary composition performs no SQLite operation. Addresses are remembered by model/header/source revision and retained in a bounded 512-entry cache; provider scopes and endpoint configuration are evaluated once per source revision. A filtered preferences/credential listener observes only fields that affect media-server identity. Playback progress, theme-music preferences and unrelated image-budget changes do not invalidate this lookup.
- Memory revision hits retain the short `LruCache` lookup path. A table miss stays on `Dispatchers.IO`; no successful ordinary lookup writes metadata. Explicit refresh still uses one transaction and one selective traversal per RAM budget.
- Source/account changes update request identity directly. Normal recomposition and unchanged revision events neither restart network requests nor repeat token decryption/profile parsing.
- The persistent revision and resource revision remain separate from image size. This preserves the small-card/backdrop memory budgets and avoids aliasing a smaller decoded bitmap to a larger request.

### Verification of the corrections

The final isolated compile covers the actual refresh store/coordinator, provider resolver, auth and Coil interceptor, `BrowseScopes`, focus helper and their pure tests against Android API signatures, Coil **3.6.3**, OkHttp **5.5.0**, coroutine, Hilt and Timber artifacts. AppPreferences/TokenStore, branding and application access use explicitly nonexecuted API seams in this harness; the unchanged URL sanitizer is extracted verbatim. It does not compile Compose or replace full Hilt/application CI.

**16 pure JVM tests passed** in the focused run: provider/base-path resolution, resize-resource grouping, canonical credential hashes, source-change filtering, cold/conflated refresh decisions, normalized disk identities, explicit-auth detection and focus-identity isolation. The manifest, compile log and JUnit output record the production source hashes used.

**Six additional API-35 integration tests** exercise real preferences/TokenStore/BrowseScopes with the production refresh coordinator and OkHttp auth interceptor: variant/account separation, explicit-header and signed-query identity, shared reverse-proxy provider choice, account change before network dispatch, explicit authorization precedence and the two-read credential race. The existing refresh test class adds a real SQLite write-failure/recovery test and a placeholder/continuity identity regression. These Android tests and Compose/application compilation remain CI gates at this audit checkpoint; no local Android or device performance result is inferred from the isolated JVM run.
