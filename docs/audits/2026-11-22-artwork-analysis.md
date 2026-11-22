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
