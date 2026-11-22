# SmartTube / Radio Browser availability and NextUp seek readout

Baseline: `4ac471679e80c75a21f7606f91ca31363833903b`.

## Report and confirmed causes

SmartTube was reported offline in the menu when Plex/NAS services were unavailable;
the same symptom was not observed on the Philips TV. Radio Browser was subsequently
reported offline while a network connection was available. The user explicitly
confirmed that WireGuard was **off**. A WireGuard/DNS change is therefore not part of
this diagnosis or correction. The device-specific trigger has not been reproduced
on hardware.

NAS host health and public-service health already use separate source sets.
Physical WLAN/Ethernet presence, Android's default route, Android Internet
validation and successful service requests are distinct signals. A local network
connection alone does not prove Internet access. Conversely, a working public API
response must not remain hidden behind an older missing-route snapshot. The earlier
correction for an unvalidated default route remains present: it is unknown, rather
than proof of an outage.

The remaining false-status paths were:

- `IncompleteYouTubeFeedException` inherited `IOException`, so a bounded feed's
  time/emission limit counted as a transport outage. Home reported that failure
  even after receiving and retaining fresh partial rows.
- Concurrent Home/view requests reported by completion order. An old failure could
  overwrite a newer success without route or request identity checks.
- Network reads could replace concurrent request evidence with a copied snapshot.
  Initial observation discarded early replies, unvalidated recovery left failures
  latched, and loss of an unvalidated route could retain stale successes.
- Generic IO wrappers around HTTP rejections/rate limits counted as outages.
  Ignoring a later HTTP rejection also left an older transport failure displayed.
- The shared offline calculation treated `hasNetwork == false` as an unconditional
  block even after accepting a current live service response. A stale
  `activeNetwork == null` observation could keep public services offline without a
  recovery callback. Repeated identical null reads also cleared newer evidence.
- Radio Browser's unscoped reports allowed cancellation or a delayed failing mirror
  sequence to overwrite another request's success. Pool clearing could erase a
  newly recovered preferred mirror; discovery and success updates shared a lock.

## Shared availability correction

`OnlineServiceReports` serializes route and request evidence. Tokens carry the route
generation and an ordered start marker. A successful reply protects against failures
of that request and requests already in flight; a newly started request can still
establish a later outage. Physical route changes and known outage transitions
invalidate obsolete results. Initial observation of an existing route preserves
early replies. Android validation clears stale startup failures without equating
an unvalidated route with an outage.

The shared `internetUnavailable` calculation now requires an unavailable platform
observation with no accepted current reachability evidence. Menu availability and
Internet notices both use it. A fresh response can disprove an outdated missing-route
snapshot. Real default-network loss immediately invalidates outstanding tokens and
clears successful evidence, so a pre-loss response cannot restore the new generation.
Re-reading the same null route does not repeatedly erase later successes.

Foreground entry re-reads local connectivity and retries failed callback
registration. This adds no Internet probe and does not equate WLAN presence with
proven Internet reachability. The sidebar uses one merged flow for the existing,
separate local-host and public-service source sets; menu IDs and navigation remain
unchanged.

### SmartTube

The shared content-request boundary used by Home, feeds, search and channel pages
reports actual provider emissions before UI callbacks or collection limits.
Home/YouTubeViewModel no longer publish duplicate unscoped reports. The incomplete
feed exception preserves an underlying transport cause without itself asserting
an IO failure. Explicit authentication failures retain account-attention behavior.

HTTP 401/403/429 and other non-outage HTTP replies establish reachability with the
same token, then rethrow the original exception. HTTP 408/5xx and transport failures
retain unavailable handling. Cached catalogue entries, local account checks and
local persistence do not publish successful network evidence.

### Radio Browser

One token covers discovery and the complete mirror sequence. The interceptor checks
cancellation before work, between mirrors and after a response. Cancelled responses
are closed; cancellation neither reports an outage nor clears the pool. Only replies
with an OkHttp network response establish reachability. Cached HTTP/catalogue results
and repository cache fallbacks do not establish successful network evidence.

Live HTTP 2xx–4xx replies, except 408, prove reachability; only usable 2xx replies
promote a preferred mirror. HTTP 408/429/5xx retain mirror retry behavior, while 429
itself does not declare the service offline. A final transport/server failure can
report an outage only with a current token and without a newer protective success.

Pool clearing also checks a successful-mirror generation, so an older failure
cannot erase a mirror another request recovered. DNS discovery has a separate lock,
allowing success updates without waiting for another lookup. Existing discovery,
fallback hosts and cache duration are retained.

### Remaining reporters

Weather, Trailers and Telegram report sites now use scoped tokens. Tokens are
captured before the existing provider work, after cache return paths where
applicable, and reused for success/failure reports. This closes
the pre-loss-response race without changing data paths or issuing extra requests.

Telegram page work can return cached Monogram data. Its scoped success therefore
uses `confirmsInternet = false`: it can clear Telegram's own failure and advance its
request-ordering barrier, but does not add reachability evidence or override a
missing-route observation. The token begins before page work/queueing, conservatively
rejecting work started before a route loss. Native RPC and cache paths are not being
rewritten. A regression covers a cached Telegram result while no route is present;
the page result must not declare Internet recovery.

## NextUp seek readout

The additional report was that, after NextUp starts another video, seeking could
move the bar while the elapsed timestamp did not change. The shared TV and handheld
bars had a concrete split: the fill used the accepted visual target, but elapsed
text used the prior published position while awaiting renderer acknowledgement.
This UI hold is bounded by the existing 2.5-second timeout; it does not establish
an indefinite decoder freeze.

Committed seeks now display the same accepted target in the fill and elapsed text.
An uncommitted drag/held remote preview still displays the live clock and a separate
target. Acknowledgement and timeout return the UI to the published playback clock.

The persistent OSD can survive NextUp with a positive, even identical, duration.
Item-specific previews, pending targets and acknowledgement state are therefore
keyed by media timeline identity. YouTube supplies its video ID; the shared media
player supplies its existing item/version identity. Pointer handlers use matching
keys so replacement cancels an outgoing gesture and later input writes the current
item's state. Focus/chrome lifetime remains unchanged. No decoder or seek-coordinator
behavior changed; the suspected Crossfade capture problem was not reproduced.

## Performance and review

No additional network requests, startup probes, provider initialization, polling
loop or visible-row reloads were added. The store has at most five public service
keys and short serialized updates. One redundant sidebar collector was removed.
Network reads retain the 1.5-second settling delay; initial route observation and
foreground reconciliation read local platform state. Radio pool invalidation is
now guarded against obsolete failures rather than made more aggressive.

Independent review covered request/callback ordering, exception propagation,
initialization, Hilt constructor compatibility, radio cache/cancellation/mirror
ordering, sidebar integration and seekbar state/gesture lifetime. HTTP recovery and
pointer-lifetime findings were corrected and re-reviewed without blocking findings.
The remaining reporter migration was independently reviewed, including native-cache
evidence; the expanded head still requires full application CI.

## Validation

At this checkpoint, 75 focused connectivity tests and 45 player-focused tests passed
locally. Connectivity coverage includes sleeping NAS isolation, partial feed success,
overlapping requests, later genuine outages, route generations, unvalidated recovery,
stale missing-route observations, HTTP/auth/cancellation wrappers, cache-only results,
radio failover/pool ordering, native cached-page neutrality and concurrent
network-read/success ordering.

Five new Compose/Robolectric regressions await full application CI. They exercise the
actual TV and handheld bars: committed elapsed text before engine acknowledgement;
same-duration NextUp replacement and a second seek; acknowledgement and subsequent
clock ticks; held remote preview/release; timeout fallback; and discarding an outgoing
held preview before its key is released on the next item.

Focused local harnesses compile relevant production code against Kotlin 2.4.21 and
actual Android/library classes. The sidebar harness uses exact production helpers,
excluding the unrelated preference-submenu test; generated resource IDs and the
unused diagnostic sink are scaffolded. These results are not a full APK build, a
completed Compose/Robolectric run or a hardware callback test. The normal PR workflow
supplies full application, Android-test and JVM-suite compilation; record its final
result separately.

Hardware acceptance: with WireGuard off, cold-start with NAS off and Internet
available and check SmartTube and Radio Browser; repeat with NAS on. Disconnect and
reconnect Internet, then suspend/resume: genuine offline status must appear and
successful recovery must clear it without re-login. Compare the affected device and
Philips TV using the same build. For NextUp, seek before and after a video change,
including a held remote key and equal known durations; verify elapsed time follows
committed seeking and continues ticking after acknowledgement.
