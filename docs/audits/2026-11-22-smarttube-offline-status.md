# SmartTube menu availability regression

Baseline: `4ac471679e80c75a21f7606f91ca31363833903b`.

## Report and confirmed causes

SmartTube was reported offline in the menu when Plex/NAS services were unavailable;
the same symptom was not observed on the Philips TV. Source inspection confirms that
NAS host health and public-service health already use separate source sets. The
earlier correction for an unvalidated Android default route is also still present.
The device-specific trigger has not been reproduced on hardware.

The remaining false-status paths were:

- `IncompleteYouTubeFeedException` inherited `IOException`, so a bounded feed's
  time/emission limit counted as a transport outage. Home reported that failure
  even after it had received and retained fresh partial rows.
- Concurrent Home/view requests reported by completion order. An old failure could
  overwrite a newer success, with no route or request identity check.
- The network reader assigned a previously copied snapshot while request reports
  updated the same flow independently. A concurrent successful reply could be lost.
- Initial route observation discarded early request evidence. Recovery to an
  unvalidated route left failures latched, while loss of an unvalidated route could
  retain stale successful evidence.
- Generic IO wrappers around HTTP rejections/rate limits were treated as outages.
  Ignoring a later HTTP rejection also left an older transport failure displayed.

## Correction and integration

`OnlineServiceReports` serializes route and request evidence. Request tokens carry
the route generation and an ordered start marker. A successful reply protects
against failures of that request and every request already in flight; a newly
started request can still establish a later outage. Physical route changes and
known outage transitions discard old evidence and obsolete results. Android
validation clears stale startup failures without equating an unvalidated route
with an outage.

SmartTube now reports at the shared content-request boundary used by Home, feeds,
search and channel pages. A provider emission is recorded before the UI callback
or collection limit. Home and YouTubeViewModel no longer publish duplicate unscoped
reports. The incomplete-feed exception preserves an underlying transport cause
without itself asserting an IO failure. Explicit authentication failures retain
their account-attention behavior.

HTTP 401/403/429 and other non-outage HTTP replies establish reachability with the
same request token, then rethrow the original exception. HTTP 408/5xx and actual
transport failures retain unavailable handling. Cached catalogue entries, local
account checks and local persistence do not publish successful network evidence.

The sidebar observes local-host and public-service state through one merged flow.
Its existing distinct source sets, menu IDs and navigation behavior remain intact.

## Performance and review

No additional network requests, startup probes, provider initialization, polling,
cache invalidation or visible-row reloads were added. The store covers at most five
public service keys and uses short serialized updates. One redundant sidebar
collector was removed. Network reads retain the existing 1.5-second settling delay;
an initial local connectivity snapshot establishes the route before startup work.

Independent review checked request/callback ordering, exception propagation,
initialization, Hilt constructor compatibility and sidebar integration. Its HTTP
recovery finding was corrected and re-reviewed with no blocking findings remaining.

## Validation

48 focused JVM tests passed locally: 25 report-store/request-pipeline tests,
11 availability/classifier tests, eight relevant sidebar tests and four existing
SmartTube account-health tests. Coverage includes sleeping NAS isolation, partial
feed success, overlapping coroutines, genuine later outages, route epochs,
unvalidated recovery, HTTP/auth/cancellation wrappers, cache-only results and a
200-iteration concurrent network-read/success regression.

The complete availability/report production classes compiled against Kotlin
2.4.21, actual Android API classes, Hilt, Retrofit and coroutines. The focused
sidebar harness uses the exact production helpers; it excludes the unrelated
preference-submenu test. Generated resource IDs and the unused diagnostic sink are
scaffolded. This is not a full APK build or hardware callback test; the normal PR
workflow supplies full application, Android-test and JVM-suite compilation.

Hardware acceptance: cold-start with NAS off and Internet available; repeat with
NAS on; disconnect/reconnect Internet and verify the offline badge recovers without
re-login. Compare the affected device and the Philips TV using the same build.
