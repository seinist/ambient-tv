# Game OSD rewind, performance and integration audit — 2026-11-22

## Scope and user decision

Implement the Kodi comparison findings in Ambient's existing video-derived chrome. Unavailable
game OSD actions are hidden entirely; no dimmed buttons or explanatory availability messages.
Keep an explicit End game action and the always-reachable settings recovery path.

Related feature audits cover input contexts, action visibility/state dialogs, common panel focus
and the Companion protocol/UI. This audit covers the assembled screen and rewind/performance.

## Rewind feature audit

- The available control depends on real history, serialization capability, settings, hardcore,
  netplay and current session readiness. A current-frame-only buffer is not rewind history.
- Separate physical, local-touch, Companion and OSD hold owners prevent one source's release
  from cancelling another. OSD rewind advances native rendering only for the preview; ordinary
  game inputs remain neutral and the input context remains MENU.
- Rewind is set before lifting the native frame gate. Releasing an OSD hold pauses before taking
  the replacement save-state preview. No per-rewind-frame screenshot or JPEG work is added.
- Hold pointer streams are consumed and release on drag-out, cancellation, window/lifecycle
  loss and disposal. Keyboard holds and bounded accessibility activation use the same primitive.
- Native JNI statistics previously dereferenced a GL-owned buffer on a foreign thread. Patch
  0029 publishes bounded atomic counts/capacity at existing ring mutations, resets them on clear,
  failed reinitialization and destruction, and never adds a UI-to-GL wait for telemetry.
- The existing 20-second target/64-MiB ring budget remains. Displayed seconds are explicitly
  approximate: captured-state cadence differs when fast-forward runs several emulated frames.
- Reconfiguration responds to capability/settings changes rather than panel keys. Netplay or
  disabled rewind releases the ring; leaving netplay starts fresh history. Unrecognized/failed
  achievement sessions do not permanently inherit the initially requested hardcore flag.

## Performance feature audit

- Descriptor revision is session state, independent of panel/options effects. Unchanged core
  descriptors are not re-copied and re-parsed on every panel transition.
- Core-option snapshots live outside GamePanelOverlay's structural key. Initial declarations
  and parsed choices are obtained together; category/option navigation does not rebuild them.
- Option snapshots are loaded/parsed on a worker. Patch 0030 serializes the native read behind
  core option updates on GL, avoiding a race against core-owned metadata changes.
- Rewind observers poll one atomic count at 4 Hz while relevant. Equal snapshots do not dirty
  Compose state. No polling runs for absent/disabled history capability or a background app.
- Existing bounded/lazy state images, asynchronous file IO, checkpoint writer and background
  frame gate remain in use. Reset work now also runs off main.
- Cheat changes use one ordered GL batch with immutable desired codes. Equal requests avoid
  native work, newer generations supersede queued batches, and teardown invalidates pending
  work before releasing the session. Hardcore initialization and netplay negotiation share
  the same policy, including restoration after an unsupported achievements session.

## Integration corrections found and fixed

1. A successful state load initially tried to resume before clearing its busy flag. The busy
   state is now cleared before successful resume; failures remain paused, and background resume
   stays gated by the activity lifecycle.
2. A remembered availability snapshot alone left a same-frame duplicate-save window. Execution
   additionally checks the live busy/destroyed/exiting/lifecycle fields. Save/load/reset/exit and
   background transitions synchronously publish BLOCKED input where applicable.
3. Settings/info callbacks now recheck live readiness before opening a game panel. Old app-wide
   shortcuts cannot win a race against a busy or exiting session.
4. Input generations carry a stable page/dialog identity, not only MENU versus PLAYING. This
   closes the held-confirm/repeat path from one menu page to its successor, locally and remotely.
5. Save dialogs use the same in-memory compatibility rule as Quickload. Compatible legacy core
   stamps remain loadable; incompatible newer saves do not mask an older usable quickload.
6. Shared OSD option labels/icons distinguish End game from media fullscreen/minimize. New rewind
   choices use the existing hidden-button preference/backup contract.
7. Shared focus origins have stable native category/option IDs and a recovery target when an
   action disappears. Returning from a child no longer unconditionally requests Continue.

8. A separate review found auto-resume could retain pre-restore rewind history. Patch 0031 clears
   history in the same native operation as every successful persisted restore. It also freezes
   failed rewind restoration rather than advancing a normal core frame.
9. A core advertising no options initially could never reveal later options if both entry points
   stayed hidden. The bounded snapshot now refreshes on OSD opening, independent of panel keys.
10. Hold activation rejects repeats/canceled downs and uses independent device/key owners, so
    moving focus onto Rewind while OK is already held cannot activate it.
11. Netplay's Connected flag did not cover host approval or joining. Timeline-changing actions,
    rewind and cheats now respect the entire busy session. Actual state exchange separately
    gates emulation/resume; waiting for a peer remains playable. Cancel stays navigable, while
    Continue disappears locally and on the Companion. Its capability change invalidates old
    input tickets, and a successful background handshake cannot bypass lifecycle suppression.
12. Android gesture/system Back now closes the save-state strip before resuming or navigating
    away. The strip's focused-row key handler alone did not cover the system Back dispatcher.
13. Child panels now use the same hidden-action/focus wrapper. Unavailable device sections and
    draft Save disappear. Cheat deletion remains a usable storage action even when activating
    cheats is prohibited; Cancel/Done and netplay cancellation remain reachable.
14. Full CI exposed a missing semantic boundary for held buttons inside a clickable player
    surface. Hold controls now merge their own icon/label descendants as independent buttons,
    preserving their accessibility action and preventing the parent OSD action from absorbing
    them. The strict pointer/drag/disposal regressions remain unchanged.

## Validation

- All three new native patches applied cleanly in a fresh pinned LibretroDroid fetch.
- Python script suite: 74 tests completed, 2 skipped.
- Local HoldAction/Rewind compilation against real Android/Compose APIs and 4 plain JVM tests
  passed. Android-backed hold/pointer/disposal regressions are included for CI.
- Input and action/focus feature audits record their separate local compilation/policy runs;
  those are focused checks, not substitutes for full app/Robolectric/native CI.
- Cheat batching passed 6 focused JVM tests; the netplay implementation compiled against real
  Android/Compose APIs with a compile-only native boundary and passed 5 existing protocol and
  serialization tests. Companion adds explicit resume-capability and stale-ticket regressions.
- Native input contracts passed under ASan/UBSan (6 groups, including 10,000 concurrent
  snapshots). Local LeakSanitizer is unavailable under the container ptrace environment; CI
  retains the unmodified sanitizer command.
- Full CI result is recorded in the PR before merge.

Device validation remains: physical Android TV/phone Guide delivery, TV standby/resume, several
native cores and sustained rewind while controlling the TV over an actual network. No device
benchmark or measured FPS improvement is claimed by this source and automated test audit.
