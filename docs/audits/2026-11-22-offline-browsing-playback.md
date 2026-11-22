# Offline browse previews and verified playback

Date: 2026-11-22. Baseline: `afc50d05`. Local tested implementation: `56bd0669`.
Local commits: `7f564430`, `2f12d327`, `cafad493`, `9d80207c`, `56bd0669`.
The full application gate passed; exact results and hardware limits follow below.

## Delivered behavior

Saved display content remains useful while a server is unavailable. It does not
authorize playback: permission requires a successful current read from the selected
service/account. Cached items, an open TCP port and sibling services on the same
NAS cannot provide that proof.

The baseline used different policies: `homeRowHiddenByOffline` explicitly kept
cached Plex/Jellyfin/Emby posters, while `homeTvSourceVisible` filtered offline
Live TV services/TVHeadend. Different cache and empty-state paths then produced
Recently added beside offline/wake rows. This was an implementation inconsistency,
not a useful distinction between content categories.

| Surface | Offline/unverified behavior |
| --- | --- |
| Home and Plex/Jellyfin/Emby overviews | Retain saved Continue watching, Recently added, collections and library/category content. One integrated `OfflineSourceStatus` includes unavailable library sources and saved Live TV sources. |
| No saved content | Show an explanation and Retry/connection action. A failed request is not presented as an empty library; navigation/settings remain reachable. |
| Libraries, categories and people/collection filters | Preserve successful display data across failures. Filtered views have scoped persistent metadata snapshots; section snapshots and focus include source/account identity. A complete successful empty response can remove obsolete entries. |
| Submitted search | Configured media/Live TV sources stay selectable. Failed sources retain the same query's previous results while successful sources update. Incomplete Live TV results preserve that source's saved entries. Arbitrary searches are not guaranteed after a cold start. |
| Locked detail | Show cached poster, title, basic metadata and synopsis. Playback, resume, episodes, people, recommendations and editing are absent from the composed action/focus tree until current metadata and service access are confirmed. |
| People on verified details | Missing/failed portraits use stable name/role cards of equal size; existing person navigation remains available. |
| TVHeadend/shared Live TV views | Preserve available channels/logos, guide, recordings and timers. Home/source snapshots use the exact configured source identity. Saved or channels-only responses do not prove optional sections empty; stale programme data does not claim to be currently playing. |

Plex, Jellyfin and Emby follow the same library/detail rules. Captured Live TV
profiles use their selected repository/account independently of the active VOD
profile. TVHeadend channel and recording starts verify their required API paths.
IPTV must obtain a fresh playlist for verification; saved playlist/guide data
remain available for display.

## Recovery, playback and ownership

Explicit **Retry** checks the service/account first. An already responding server
reloads without WOL. Otherwise recovery checks the physical machine, joins any
existing recovery, and considers WOL only when the machine remains unreachable
and has usable wake configuration. A failed manual wake cannot fall through into
a second automatic wake. Authentication failures lead to service settings/sign-in,
not WOL. Shared host/MAC wake coordination does not grant sibling-service access
or wake unrelated machines. Rendering a saved-content status starts no wake;
existing optional wake-on-open behavior retains its setting.

`PlaybackAvailability` maintains bounded, process-local protected-access proofs.
Their scope includes endpoint/login identity, network generation and source/host
outage generations. Display snapshots, public identity responses and host recovery
cannot restore a revoked proof. Source-unreachable callbacks invalidate permission.
`PlaybackAccessProbe` prevents Files/DLNA repository helpers from implicitly waking
while access is only being checked.

| Playback path | Enforcement |
| --- | --- |
| Home/search/categories/details, resume, skipped details and media/version selection | Central navigation preflight plus final playback-owner checks; the selected physical source is verified. Skipping details does not skip the gate. |
| Launcher and incoming external/Companion requests | Local dispatch reaches the same gate; the receiving device verifies its own selected source. |
| Existing/cached player reuse | Requires matching route, active session, recorded source/account identity and current proof. The same numeric media ID on another account is a different session. |
| NextUp/queues/gapless | Preparation/adoption checks access. Background `GaplessQueue.Entry.canStart` also rejects revoked entries without an attached player screen. |
| Live TV channels, recordings and zapping | Verify the selected registry repository and required operation; owner reuse/change paths retain the checks. |
| Android Auto/service-owned playback | Catalog resolution and final service start check captured identity/proof without relying on a detail page. |

Pending starts carry a ticket, origin entry and stable source identity. Repeated
Play joins the pending intent. Retry resumes only that intended start after
verification. Cancel, navigation, source/account replacement, disposal and lifecycle
stop discard it; late connection/WOL success cannot launch it. Network/outage
changes revoke access while stable identity permits retrying the same intent.
Retry on a normal locked detail refreshes its metadata without autoplay.

## Failure while a view or playback is open

A confirmed protected-service read failure now marks open Home, category, filtered
browse and TVHeadend views as saved/unconfirmed even if another service keeps their
shared host ONLINE. Existing data and selection remain visible. A successful fresh
protected read clears that state; a public identity response or HTTP cache hit does
not. Open details switch to their locked preview and stop secondary work/theme
music when their access proof is lost.

TVHeadend write failures retain the dashboard, selection and editor draft. Mutation
guards check current availability synchronously; open timer/delete dialogs offer
Retry and Back after an accepted failure. Retry reloads server state and does not
repeat the write. Detail actions likewise report a failed write even when that
failure itself advanced the access generation; a different account still discards
the obsolete result.

Playback recovery captures the route, source identity, generation and exact
renderer handle. Cancellation, screen disposal, a newer item or an account change
cannot let a delayed check pause, stop or replace another session. Recovery retains
the last local absolute position, including transcoded timelines and a decoder
clock that drops to zero; live elapsed time is never a recording resume point.
Terminal failures release only their own renderer before reopening, so identical
stream URLs cannot reuse a failed handle. A retry-owned tuner handoff explicitly
transfers its captured owner, and cancelled resolved live sessions are closed.

Automatic foreground recovery checks protected access first and honours the
selected source's existing Auto-WOL preference. Explicit Retry may use configured
WOL after the failed check. Ambiguous native MPV/VLC errors first probe without WOL;
an online source retains its codec-fallback path. MediaSession Play can freshly
resolve service-owned audio through the authenticated catalog. VM-owned video
offers **Open player** instead of restarting a saved URL, and automatic audio-focus
return never becomes an implicit retry/WOL command. Old-account queues cannot offer
or execute Next/Previous against the newly selected account.

There is no new outage poller. Home/section data refresh defaults to 60 seconds and
can be disabled. A silent failure of an idle, previously healthy server becomes
known at the next request, network change or player failure signal; it is not
promised to appear immediately.

## Scope and artwork boundaries

Display snapshots contain metadata projections, not playable streams or access
grants, and use the existing bounded store. Namespace/current-scope checks reject
obsolete responses and deferred writes. Source replacement clears retained content,
selection and focus before accepting the new identity. The detail VM additionally
requires metadata confirmed for its own scope; another page's successful request
cannot unlock a cached detail tree. Lost verification cancels secondary work/theme
music; late backdrop, edit-rights and action results check ownership.

Coil retains existing bounded memory/disk caches. Never-stored or evicted artwork
uses title/name fallback. Known outages or rejected authentication block remote
image fetching while cached bytes remain usable; locked details explicitly request
cache-only images. Loopback HTTP covers work without an Android network. Isolated
LAN operation does not require Internet validation.

Successful visible posters/logos retain their requests through connectivity changes;
failed/in-flight requests restart on recovery. A temporary equality marker is
necessary because Coil Compose ignores `networkCachePolicy` alone. The interceptor
strips it before real cache-key calculation, preserving account/header identity
without duplicate recovery entries. Artwork uses raw scoped service reports so
an ONLINE NAS cannot erase an OFFLINE service. Explicit foreign-account credentials
retain their separate handling. No device performance improvement is claimed.

## Confirmed integration findings and corrections

Paths below are relative to `app/src/main/java/com/ambient/tv/`.

| Confirmed finding | Correction and evidence location |
| --- | --- |
| Offline row/system guards removed useful rows while Recently added survived. | Saved browse trees remain; one Home status and explicit no-cache state. `ui/home/HomeRowOrder.kt`, `HomeRefreshRows.kt`, `HomeTvVisibility.kt`, `HomeViewModel.kt`, `OfflineSystemPage.kt`. |
| Failed/incomplete refreshes could erase previous results or imply empty optional sections. | Preserve per-source content on failure; successful complete empties remain authoritative. `ui/search/SearchViewModel.kt`, `ui/section/SectionViewModel.kt`, `ui/browse/FilteredBrowseCache.kt`, `ui/tvheadend/LiveTvInitialLoadPolicy.kt`. |
| Cached metadata exposed too much of the full detail action/focus tree; missing portraits left weak placeholders. | Separate locked composition and metadata confirmation; fixed-size name/role fallback. `ui/detail/DetailScreen.kt`, `DetailViewModel.kt`, `DetailConnectionPolicy.kt`. |
| Reachable hosts/sibling services or saved data could be mistaken for playback access. | Protected source-specific proofs, network/outage generations, invalidation callbacks and exact captured-profile identity. `data/browse/PlaybackAvailability.kt`, `VerifiedPlaybackAccess.kt`, `SourceAvailability.kt`, `livetv/LiveTvRegistry.kt`. |
| The same route could reopen another account's cached playback session. | Store the authorizing identity in session metadata; require identity and current proof for reuse. `ui/navigation/PlaybackStartRequests.kt`, `ui/MainActivity.kt`, `ui/player/BackgroundPlaybackSession.kt`, `PlayerViewModel.kt`. |
| Delayed starts or background gapless transitions could outlive navigation/account ownership. | Cancel captured requests on ownership changes; background queue rechecks `canStart` and pauses revoked adoption. `ui/MainActivity.kt`, `ui/player/GaplessQueue.kt`, `ui/tvheadend/TvheadendPlayerViewModel.kt`. |
| IPTV's saved playlist could appear to prove connectivity. | Force fresh playlist verification without fallback, check the selected channel, and avoid requiring a guide fetch. `livetv/IptvLiveTvRepository.kt`. |
| Home could lose saved Live TV provenance when host status did not say OFFLINE. | Track saved source IDs, skip fresh-now work after cached-channel fallback, include sources in the existing status. `ui/home/HomeViewModel.kt`. |
| Coil policy-only changes did not restart failed requests. | UI equality marker stripped before caching; freeze successful requests. `network/MediaArtworkNetworkAccess.kt`, `ui/common/TvPolish.kt`, `TitleLogo.kt`. |
| Missing-network policy blocked local audio covers; shared-host recovery hid failed artwork services. | Exempt literal loopback addresses, preserve explicit cache-only restrictions, and read raw service health with `reportedState`. `network/MediaArtworkNetworkAccess.kt`, `data/browse/SourceAvailability.kt`. |
| An accepted service failure on an open page could remain visually healthy because a sibling service kept the host ONLINE. | `browseUnconfirmed` retains the protected-service failure independently of host reachability; open views consume it. TVHeadend writes retain drafts and require read-only Retry before further mutation. `data/browse/SourceAvailability.kt`, `ui/tvheadend/TvheadendViewModel.kt`, `LiveTvTimerDialogs.kt`. |
| Playback recovery could outlive its owner or lose the local clock after a terminal error. | Exact owner/request policies, scoped local checkpoints and explicit tuner-owner transfer; foreground automatic wake respects source preferences. `ui/player/PlaybackRecovery.kt`, `BackgroundPlaybackSession.kt`, `PlayerViewModel.kt`, `ui/tvheadend/TvheadendPlayerViewModel.kt`. |
| Cancelling a service retry could leave its checking message displayed indefinitely. | Cancel removes only that recovery's busy status; newer owners retain their own state. `ui/player/PlaybackService.kt`. |
| Service-owned audio had no requested-start fallback when an initial failed renderer reported zero. | Carry the absolute requested start into recovery metadata, preserving the intended resume point before the first useful clock sample. `ui/player/PlaybackService.kt`, `BackgroundPlaybackSession.kt`. |
| Fresh resolution could return the same URL/start key and reuse the failed renderer, especially for live radio. | The owned failed renderer is released before fresh acquisition. The service uses an explicit source replacement with play intent, retaining its active notification and listening timers; foreground recovery stops only its captured failed owner. Source resolution/access validation remain guarded. `ui/player/PlaybackService.kt`, `PlayerViewModel.kt`, `ui/tvheadend/TvheadendPlayerViewModel.kt`. |
| An old Auto music queue could resolve a reused numeric ID under a newly selected account. | Require the session's source identity both when advertising and executing Next/Previous. `ui/player/PlaybackService.kt`, `PlaybackServiceRecovery.kt`. |

Retry cancellation, endpoint changes, shared recovery and avoidance of a second
wake were also reviewed in `connectivity/OfflineServiceRetry.kt`,
`connectivity/UnreachableServerGate.kt` and `wol/MachineWake.kt`. The independent
detail/artwork review found no remaining confirmed blocker at its checkpoint.
The runtime rows record the subsequently identified integration corrections;
their final source review and full-application test result remain separate from
physical hardware acceptance.

## Settings shortcut boundary

An authentication failure names the selected source and opens its service category
in Settings. For an inactive captured Live TV profile, this does not switch the
active VOD server or open a profile-specific login dialog. The user selects that
profile in the existing service settings. Opening settings discards the pending
start, so login cannot launch an obsolete request on return. Existing composite
shortcuts such as `wake:plex` retain the shared settings navigation.

## Recorded validation checkpoints

**Do not sum these counts:** focused runs overlap. Their manifests record source
hashes, and runs before `9d80207c` do not prove a full build of that final head.

| Check | Result and limit |
| --- | --- |
| Gate/WOL integration harness | 88 JUnit tests passed; whole `UnreachableServerGate`/`MachineWake` with actual Kotlin/coroutines/Android APIs and explicit compile-only declarations for unrelated collaborators. Not an application/DI build. |
| Core compile/policy harness | 43 JUnit tests passed; whole `PlaybackAvailability`, `SourceAvailability`, `DetailViewModel`, `LiveTvHttp`/repository and related policies/models. Real dependency APIs, structural seams for unrelated preference/registry/lifecycle/UI collaborators. Includes proof invalidation and same-route account reuse. |
| Artwork helper harness | 20 JUnit tests passed with actual Coil/OkHttp APIs, including loopback, identity/header/cache regressions. The later raw-service-state integration belongs to the final application gate. |
| Focused Compose compile | Passed: whole `ArtworkNetworkAccess`/`TitleLogo` and verbatim locked-detail/cast/helper fragments; Compose 1.12.1, Coil 3.6.3, TV Material and Kotlin/Compose compiler 2.4.21. No UI execution or complete activity/detail-screen build is inferred. |
| Source audit | `scripts/audit_source.py`: 383 findings on the changed tree, including the runtime corrections, and 383 on fresh detached baseline `afc50d05`; zero introduced findings in that comparison. The baseline is not clean. |
| Shell syntax | `bash -n build.sh` passed. |
| Python script suite | 74 tests run: 72 passed, 2 skipped (missing `glslangValidator`; explicitly opt-in SVP network/Cargo test). |
| Asset checks | Both import scripts with `--check` passed: 30 game vectors plus one original fallback; 30 controller vectors with catalogue/attribution/license checks. |
| Native input contracts | Exact `--sanitize` run compiled but LeakSanitizer failed because this environment uses ptrace. Subprocess-only `ASAN_OPTIONS=detect_leaks=0` passed six contract groups, including 10,000 concurrent snapshots, with ASan/UBSan retained. Leak detection is unverified. |
| Standalone shader checks | Not run: `glslangValidator` unavailable. |

Evidence is in `offline-retry-validation`, `offline-core-validation`,
`offline-artwork-validation`, `offline-ui-validation` and `offline-pr-gates` under
`/workspace/scratch/74eca959a858`; focused runners record logs/source manifests.
`offline-pr-gates/results.json` records fast-gate commands and limitations. Source
comparison logs are `offline-ambient-tv-source-audit.log` and
`offline-offline-audit-baseline-source-audit.log` in the same workspace.

Added full-suite regressions cover locked-detail action absence/focus and cast
fallback, real Coil cache behavior, fresh IPTV verification, proof scoping,
cancelled/replaced starts, account reuse, background gapless revocation, runtime
recovery ownership/positions, service audio retry and retained Live TV state after
write failure. Their presence alone is not counted as execution; the final XML
record follows.

## Completed full Android Gradle result

The real run uses full JDK 21, SDK 37, NDK 30.0.16248370, build-tools 37,
CMake 3.22.1 and the pinned/patched LibretroDroid checkout, with PR workflow targets:

```sh
CI=1 SKIP_MPV_NATIVE=1 ./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :libretrodroid:testDebugUnitTest :libretrodroid:assembleDebug -PAPK_ABI=arm64 --continue --max-workers=1 -Dorg.gradle.parallel=false -Pkotlin.compiler.execution.strategy=in-process --no-daemon --console=plain
```

The isolated runner uses the distribution matching the wrapper's Gradle 9.8.1
checksum. An initial four-worker run completed production Kotlin/Java/Hilt and
LibretroDroid compilation/tests, then lost its Gradle daemon while compiling the
unit/device tests concurrently. It emitted no Kotlin compiler error or JVM crash
file; the exact cause was not established. Its log is preserved as
`offline-gradle-build-daemon-exit.log`. The final command serializes the same
targets and runs the Kotlin compiler in-process to reduce simultaneous JVM memory
use; no project build settings or test selections were changed.

The first complete app test execution ran 5,895 tests and exposed five failures
in the new tests. The recording interceptor chain accidentally returned its outer
request instead of its own updated request; bytecode inspection confirmed that
capture, and the helper now uses `this.request`. The two TV UI tests used Android
touch injection instead of D-pad activation. They now exercise real OK/directional
events and retain their focus/action-absence/single-callback assertions; the Back
selector distinguishes the action row from the existing header Back button. An
invalid import of a member assertion was also removed. These corrections change
the test setup, not production behavior. The final full run below includes them.

A subsequent complete 5,895-test run passed those five cases and failed only the
unchanged API-35 `UiRenderConfigurationTest` resource-update assertion; its API-31
case and both cases in the preceding full run passed. The retained host uses its
own window recomposer, which the test rule's idle check does not await. The test
now bounds its wait to five seconds for the actual `SideEffect` resource update,
reading the observation on the UI thread. All retained-tree identity,
configuration and density assertions remain. This is test-only synchronization,
without a fixed sleep or production rendering change. Both API cases passed in
the final run together with the runtime additions.

| Field | Result |
| --- | --- |
| Tested commit / additional changes | Local `56bd06696653148b1ebd1d2dd043ac8806c64cc2`; subsequent changes only finalize this audit record. Publication through the GitHub integration uses its own commit identity and is verified against the full local tree. |
| Final Gradle exit status | 0; `BUILD SUCCESSFUL in 4m 24s`, 89 actionable tasks (26 executed, 63 up-to-date). |
| App Kotlin/Hilt/application compilation | Passed. |
| Android-test Kotlin compilation | Passed; instrumentation tests were compiled, not executed on a physical device. |
| LibretroDroid JVM tests/native debug assembly | Passed for the pinned/patched sources and ARM64 target. |
| Log/report location | Workspace `offline-gradle-build.log` and `offline-gradle-final-results.json`; repository `app/build/test-results/testDebugUnitTest/`, `app/build/reports/tests/testDebugUnitTest/` and corresponding `libretrodroid_wrapper/build/` paths. |

Use the completed run's XML counts without adding focused-harness totals:

| XML result set | Tests | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- |
| App `testDebugUnitTest` (980 suites) | 5,926 | 0 | 0 | 0 |
| LibretroDroid `testDebugUnitTest` | 6 | 0 | 0 | 0 |

The completed full run includes all 16 recovery-policy tests, eight service-recovery
tests, both real timer-dialog remote-input tests, both locked-detail tests, three
artwork-interceptor tests and both retained-configuration cases. The new runtime
source-failure/Live TV state regressions also pass in this same result set.

The independent service re-review confirmed the four service corrections above
without another confirmed blocker. `git diff --check` passes. The final source
audit still matches all 383 pre-existing baseline findings; it is not presented
as a globally clean source audit.

## Remaining acceptance limits

No physical server outage, actual WOL packet capture, TV/handset focus session or
renderer playback session was exercised by the focused host runs. Completed
Gradle/Robolectric gates do not establish those hardware outcomes.

| Remaining scenario | Required observation |
| --- | --- |
| NAS off/on, no snapshot, invalid login, isolated LAN, one stopped service on an otherwise reachable NAS | Saved/no-cache states are accurate; Retry checks first; WOL occurs only when needed; other services remain independent. |
| Account/profile change, Cancel/Back/navigation/background during pending connection or WOL | Late completion cannot launch playback or republish the old account's view. |
| Details skipped, launcher/Companion, resume, NextUp, detached gapless and Live TV zapping | Every new local start uses the correct source proof; invalidated continuations stop. |
| TV and handheld focus/artwork/guide | Retry/Back/settings remain reachable; return restores the intended selection; cached posters stay stable; portrait fallbacks are readable; saved/expired EPG never claims current playback. |

No hardware WOL success rate, end-to-end recovery time, device frame-time improvement
or physical focus-runtime result is claimed.
