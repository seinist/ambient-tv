# Next Up and seek lifecycle audit

Date: 2026-11-22

## Scope and prior fixes

This follow-up starts from `cbec3eb1` (PR #449) and checks SmartTube/YouTube,
MPV, VLC, Media3, shared fullscreen controls, gapless item changes, delayed
resume information, and the shared Next Up timers. The regression under review
is a seekbar that moves while the displayed or actual playback time stays on
the previous position, especially after automatic advancement.

The earlier fixes addressed real but narrower problems:

- PR #403 (`47db90d2`) releases a held OSD target when the decoder keeps moving
  outside its acknowledgement window for six seconds. This recovers the display
  from a lost seek; it does not make an early command reach an unready decoder.
- PR #409 unified progressive input, and PR #419 corrected D-pad handoff and
  acceleration. Neither scoped persistent held input to the session generation.
- PR #449 aligned the visible elapsed-time readout with the active seekbar and
  reset temporary bar state per item. Decoder readiness, deferred commands,
  callback ownership, and manifest preparation have separate lifetimes.

The defects below are independent of the menu's connectivity classification.
No online/offline gate is involved in the corrected seek or timer decisions.
Slow loading can expose these timing windows without being their logical cause.

## Findings and resulting behavior

### MPV: readiness, command acceptance, and a real zero position

The native `loadfile` command returns before the file is ready for seeking.
Previously, an immediate user seek could be sent during that interval and fail
or disappear. `MpvDeferredSeek` now retains the latest target. Before the first
load it becomes the `loadfile` start option; after a load request it is replayed
once the engine processes `FILE_LOADED`. An explicit seek to zero also overrides
an older saved resume position.

Output recovery carries a pending seek into the reopened file. A newer explicit
seek that reopens an ended file takes precedence over an older pending target.
The published clock is reset to that chosen position and does not read the
outgoing file's native clock before the replacement is ready.

A deferred end request retains its logical end intent separately from the safe
loadfile start position. Replay still selects an exact tail seek rather than
reclassifying the shortened target as an ordinary keyframe seek. If the decoder
has already reached the tail, the new request still supersedes an older deferred
backward seek without replaying that tail or subtracting the safety margin twice.

The controller previously cleared its relative-seek target when a command
revision completed. Successful command submission only means libmpv accepted
the request. `MpvPendingSeek` now retains that target until the sampled clock
acknowledges a landing, using the existing shared keyframe and lost-seek rules.
Rapid repeated steps therefore build on the newest unconfirmed target rather
than the decoder's old position. Samples older than the request revision cannot
acknowledge it. Command revision tracking for temporary EOF masking remains
separate, so retaining an OSD target cannot indefinitely conceal a real EOF.

The engine also treated `time-pos == 0` as unavailable, preserving the last
positive timestamp after a valid seek to the start. A failed native property
read now uses NaN as its sentinel; a finite, nonnegative zero is a valid clock.
No JNI signature or native dependency changed.

This reasoning was checked against the exact pinned mpv commit
`69474527816bb7c00735806213f7a158ff5b2be0`:

- [`player/command.c`](https://github.com/mpv-player/mpv/blob/69474527816bb7c00735806213f7a158ff5b2be0/player/command.c):
  `cmd_seek` rejects an uninitialized playback and otherwise queues the seek.
- [`player/loadfile.c`](https://github.com/mpv-player/mpv/blob/69474527816bb7c00735806213f7a158ff5b2be0/player/loadfile.c):
  playback is initialized before `FILE_LOADED`; the initial start seek follows.
- [`include/mpv/client.h`](https://github.com/mpv-player/mpv/blob/69474527816bb7c00735806213f7a158ff5b2be0/include/mpv/client.h):
  the seek event and playback restart/completion event have different meanings.
- [`options/options.c`](https://github.com/mpv-player/mpv/blob/69474527816bb7c00735806213f7a158ff5b2be0/options/options.c):
  `demuxer-cache-wait` defaults to false and Ambient does not enable it. In this
  configuration no cache-wait command-dispatch loop intervenes between
  `FILE_LOADED` and the initial start seek. Replaying at `FILE_LOADED` is checked
  against this configuration, not claimed for arbitrary custom mpv options.

### Shared controls: one seek state and callback lifetime per item

Generic Media3 gapless playback deliberately reuses one player. The shared
`rememberPlaybackSeekState` previously used only that decoder as its key, so the
next item could inherit the outgoing target. It now keys the state and its
updated callback cells by decoder key, active playback handle, and session
generation.

Keeping the callback cells within the same lifetime is essential: disposing
an old state must not flush its target through a callback already updated to
the incoming decoder. The shared owner predicate rejects requests, commits,
external synchronization, and disposal flushes from retired states before they
change either the target or the user-seek counter. A pending target from a
retired state is also unavailable as a resume source. Leaving fullscreen while
the same item remains active still flushes its current pending command.

The debounced effect already waits for the last input. Its final commit no
longer checks a second elapsed-time boundary that could differ by a millisecond
and drop the only final commit of a burst.

### Hidden controls: use the current clock on the first input

The reduced-playback profile suspends ordinary OSD polling while controls are
hidden. A subsequent relative seek could therefore start from an old displayed
position. Shared input now samples the engine through the coordinator before
calculating a target, preserving an actual pending target where appropriate.
The generic Media3, VLC, and MPV key handlers refresh before constructing their
progressive seek steps as well.

Hidden controls continue reconciliation only while a target awaits
acknowledgement. Polling stops again after acknowledgement or the existing
lost-seek recovery clears the target. A paused or buffering decoder retains the
existing acknowledgement semantics; this is not an unconditional timeout.
SmartTube already samples its renderer clocks every 250 ms with hidden OSD and
does not have the generic suspended-polling defect.

### Persistent held input: retire the old key hold at a handoff

The fullscreen chrome can outlive both a decoder and a title. Clearing its
progressive seek only when `changingItem` was true missed same-handle gapless
transitions. The chrome now reconciles against the session generation, including
synchronously before dispatching a key event. An outgoing target cannot be
committed by a KeyUp that arrives before Compose observes the new generation.

Repeated horizontal KeyDown events from that outgoing hold are consumed until
release, so they cannot start a new scrub on the incoming title either. A fresh
physical press remains usable if the old release was lost. Ordinary clock ticks
within the same session do not cancel a held seek.

An accepted direct scrub now publishes user intent at its first KeyDown, matching
the already existing focused-seekbar preview behavior. A delayed resume or an
old terminal timer cannot treat a held manual preview as no user interaction.
Repeats still update only the preview, and the decoder receives the seek on
release. Focus navigation does not publish seek intent. The shared callback also
serves Spotify, Telegram and TVHeadend recording controls without registering a
seek owner or sending an early decoder command; live input retains its existing
non-progressive path.

### SmartTube: source, decoder, and resume have different owners

All three SmartTube renderers now validate decoder identity, session generation,
and replacement state before accepting a user seek, committing a deferred
target, or applying late resume metadata. This rejects outgoing OSD callbacks
during source changes without incrementing the new session's user-seek counter.

`YouTubePlaybackResume` belongs to the video ID. Its user-seek veto and consumed
resume decision survive internal MPV/VLC output or controller restarts of that
same video. Previously those flags reset with the controller, allowing delayed
history to overwrite an already chosen position. The decision checks the
external seek version synchronously, including remote preview, and treats a
resolved zero as a completed decision. A new video receives a fresh decision.
The effect is also keyed by its current decoder, so a previously blocked owner
can retry without allowing a duplicate successful resume.

Media3's seek coordinator and debounce version instead belong to the actual
player. A new decoder cannot inherit an acknowledgement target already consumed
by its predecessor. Media3 position and duration initialization also use the
video identity. Its error and default-audio-selection state are scoped to the
player: a successful replacement cannot keep displaying the retired player's
error or skip audio selection because the previous player applied its overrides.

### SmartTube Media3: waiting for the next manifest must not retain the old source

`produceState(initialValue, key)` restarts its producer when the key changes, but
does not recreate the remembered state value. The old implementation could
therefore publish the previous video while the next video's deferred DASH
manifest was still loading. The new preparation helper scopes that state to
video ID, URI, and deferred manifest and publishes null while the new source is
pending.

The old `runCatching { deferred.await() }` also caught coroutine cancellation.
Metadata enrichment could cancel the producer and incorrectly publish a fallback
while its manifest was still pending. Cancellation now propagates; only a real
manifest failure chooses the current source's fallback. Prepared metadata can
still update without reverting to an unrelated source.

### Next Up: a new user seek invalidates an old terminal timer

A decoder can briefly keep reporting EOF or its old end position after a seek.
The EOF-grace and still-watching timeout effects now include the external
user-seek version in their identity and validate that version at their deadline.
The EOF fallback also re-reads current play intent, error, and position before
advancing. A tick received before recomposition can invalidate the old decision.

This restarts the appropriate grace/confirmation window after a seek. It does
not wait indefinitely for native acknowledgement: if the decoder remains at
the old end throughout a complete new grace window, the existing fallback can
still act. Explicit EOF and SponsorBlock behavior retain their existing rules.

## Source and renderer coverage

| Path | Checked behavior and correction |
| --- | --- |
| SmartTube with MPV | Native readiness/clock/relative target, owner guards, late resume, persistent controls and shared Next Up |
| SmartTube with VLC | Owner guards, late resume across output restart, persistent controls and shared Next Up |
| SmartTube with Media3 | Deferred DASH lifetime/cancellation, player-owned coordinator/error/audio state, owner guards, persistent controls and shared Next Up |
| Generic Media3/VLC/MPV playback | Shared seek ownership, hidden-clock input, pending reconciliation, persistent controls and Next Up where supported |
| Plex/Jellyfin/Emby, Files/DLNA and other generic sources | Benefit through the common player paths; no claim that each server/codec combination was played on hardware |
| TVHeadend recordings | Existing shared seek registration and pending-command behavior preserved with the ownership fixes |
| TVHeadend live/timeshift | Existing direct-controller seek ownership remains; `registerWithSession = false` neither registers nor flushes a recording-style state on disposal |

The ViewModel already cancels obsolete loads and checks requested video identity
and transition tokens. No additional load-ownership change was justified there.
Renderer-local completion callbacks currently have a no-op parent; the common
session owns advancement. They were not treated as a second autoplay owner.

## Verification and limits

Focused JVM validation uses actual production helpers and existing regression
sets, with narrow Android/platform support where necessary:

- 53 MPV/owner-dispatch/command-queue/surface-gate and seek tests pass, including
  16 new MPV seek regressions.
- 91 shared seek/coordinator/progressive-input/chapter/end/bar/VLC tests pass,
  including seven new shared-state regressions.
- Seven new SmartTube resume-policy tests pass.

These sets overlap and are not presented as an additive unique-test total.
They do not compile the entire app or execute native decoders.

Separate before/after probes used the real Compose compiler and runtime. They
reproduced the baseline's same-decoder item leak, disposal through an updated
callback, and unreconciled hidden target, and passed all three after the fix.
The control case of minimizing the current item continued to flush correctly.
The SmartTube probe likewise reproduced an outgoing source retained during
Next Up and a fallback published on producer cancellation, then passed with the
new preparation helper. Resume state retained a user's veto across a controller
restart and reset for the next video.

Checked-in Compose/Robolectric integration coverage uses the actual session for
shared seek lifetime, same-turn key repeat/release at a gapless handoff, ordinary
held seeking, direct-scrub intent before decoder submission, delayed
acknowledgement around Next Up/still-watching deadlines, and the SmartTube
preparation/resume lifetimes. Full project verification is
the PR workflow's `:app:testDebugUnitTest`, `:app:compileDebugAndroidTestKotlin`,
`:libretrodroid:testDebugUnitTest`, and `:libretrodroid:assembleDebug` with
`-PAPK_ABI=arm64`. Full project compilation and these Robolectric integration
tests are pending at this documentation checkpoint; the PR records the completed
workflow result and exact tested head when available.

The audit does not claim reproduction on a physical TV or native decoder. Device
follow-up should exercise rapid seeks just after Next Up, a held key spanning
the handoff, a seek to zero, a first seek after hidden OSD, and seeking away from
the end during automatic advancement. No additional connectivity probes,
background network polling, native library changes, or decoder workarounds were
introduced. The chrome observes generation changes only, and extra clock
reconciliation is limited to input or an outstanding target.
