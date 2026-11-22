# Game OSD action availability audit — 2026-11-22

## Scope and decision

The game toolbar, its settings and state-card actions must expose only usable actions.
The user's follow-up explicitly asks for hidden unavailable buttons, without disabled
icons or explanatory labels. Existing state files remain manageable even when this core
or session cannot restore them.

## Implementation

- `GameOsdAvailability` is an immutable, in-memory session snapshot shared by the game
  toolbar, the offered-button list and execution guards. It does not inherit a video
  player's last offered buttons.
- Loading requires persistent-state support, a ready/non-busy session, matching core
  identity/build and neither hardcore nor netplay. Both current SHA stamps and an
  explicitly resolved legacy stamp are accepted using `GameStateStore.compatible`.
  An unresolved legacy stamp cannot authorize a legacy state.
- Quickload selects the newest compatible manual state, with the existing state-number
  tie breaker. A newer incompatible state or auto/undo checkpoint cannot displace it.
  Selection is calculated once per snapshot; no state files or core binary are read.
- Saving retains the existing hardcore/netplay behavior because it does not restore
  the timeline. Reset, fast-forward, core options, cheats, disc switching and rewind
  respect their session restrictions. Netplay's panel remains available for an already
  active session so disconnect is still reachable if capabilities change.
- The state dialog omits Load entirely when it is unavailable. Manual rename and
  confirmed deletion remain reachable. Compatibility is provided separately from
  loadability so hardcore does not incorrectly label compatible files as another core.
- Dialog visibility remains active through nested rename/delete pages and clears on
  dismissal, removal, disposal or a busy operation. Completing that operation does not
  reopen the old selected dialog. Existing card focus retry and nearest-survivor logic
  remain in place.

## Feature and integration audit

Reviewed the source paths for current/legacy compatibility, newest-manual ordering,
hardcore, netplay, busy/loading states, unsupported cores, hidden-button persistence,
dialog disposal and callbacks. Execution must continue to validate the current session
and read result; hiding an action is a presentation policy, not the sole native guard.

The player integration now:

1. Builds `loaded` from live native/lifecycle readiness and passes current busy/session flags.
2. Resolves the legacy core stamp once off the UI thread and passes that snapshot.
3. Applies the policy before composing each optional game action, and uses the
   same `buttons()` in OSD settings.
4. Supplies `canLoad`, `compatible` and `onDialogVisibilityChanged` to `GameStateStrip`.
5. Loads `latestLoadableManual` directly rather than re-querying the filesystem on click.

The existing save list is bounded by 50 manual states plus auto/undo. The policy keeps
all filtering in memory and adds no queries, polling or native calls.

## Regression coverage and validation

- Nine `GameOsdOptionsTest` scenarios cover ordering/persistence, unsupported cores,
  hardcore/netplay, busy/loading, current/legacy/foreign core stamps, quickload selection,
  rewind restrictions and retaining active-netplay disconnect controls.
- Four new Compose tests cover absent Load with working deletion, restriction changes
  while a dialog is open, nested-dialog/disposal reporting, and busy completion without
  reopening. Existing touch rename/delete confirmation tests remain unchanged in scope.
- `git diff --check` passes. A local Kotlin/Compose harness compiles the complete action
  policy and game navigation helper against real Compose APIs, with extracted unchanged
  shared/model declarations and compile-only surrounding UI boundaries. Its 50 policy
  and shared-navigation regressions pass, including all nine action scenarios above.
  Full Android compilation and Robolectric execution remain the PR CI gate.
- No physical phone/TV/controller run is claimed by this audit.
