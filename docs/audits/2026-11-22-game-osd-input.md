# Game OSD input and window integration audit

## Implemented behavior

- `GameInputRouting` gives the active game an owner-scoped input lifetime independent of
  background media playback. Running, menu, dialog and blocked states are explicit. An old
  session cannot update or detach its replacement. A stable navigation context also invalidates
  input when entering another page within the same menu state.
- Physical controllers use game-local menu navigation: the configured confirm side, Back,
  shoulders for paging, and Guide/Start for the game menu. App-wide Search/Start/Settings
  shortcuts cannot unexpectedly navigate away. Settings and Info key actions open the game's
  own panels. Unsupported media actions are consumed. System Home, power and volume remain
  system-owned.
- Running games retain the existing RetroPad raw-input and Start+Select hotkey implementation.
  A late-disposed raw sink is ignored once the game context changes to a menu or blocked state.
- Keyboard/remote player bindings work in games without adding `GAME_PLAYER` to the
  background-media route set. Pending long presses are cancelled at context changes; native
  text editing keeps its ordinary keys.
- Held buttons, arrows and sticks must release or return to neutral across context changes.
  Physical hold tracking is shared by the activity and game dialog windows. A new down-time
  recovers from Android omitting a release while the app was backgrounded.
- Synthetic controller UI keys bypass the raw emulator sink. Button and stick repeat callbacks
  validate their context even if no new hardware event arrives.

## Integration findings corrected during this feature

1. **Dialog windows bypass activity dispatch.** A game-scoped dialog input bridge now covers
   ordinary native dialogs and independently rendered dialog surfaces. Translated Confirm/Back
   reach the actual dialog root, rather than the player underneath. It preserves the dialog's
   Back dispatcher, text keys, key capture, focus loss and window-callback restoration. No bridge
   is installed for dialogs opened outside games.
2. **Separate windows can leak held input on resume.** Their physical hold tracker is shared,
   while each input root owns its repeat timers. Closing a dialog cannot reclassify its held
   navigation button or stick as a fresh emulator input.
3. **Menu pages can change without changing the mode enum.** The optional context key increments
   the input generation on a real page change; routine recomposition keeps the generation.
4. **Deferred events outlive their context.** Context observers immediately cancel UI repeat
   timers and bound long presses. Scheduled callbacks independently check their original mode
   and generation. Key releases cannot execute the previous context's delayed action.
5. **A callback may run before the next Compose side effect.** Screen callbacks recheck live
   busy/lifecycle/session state, and menu-open/background transitions publish their input mode
   synchronously. The screen integration remains the final authority for game actions.

## Performance review

- Context generation changes only for a new session, actual mode change or different page key.
  No per-frame listener, background poller, coroutine or timer was added to the routing layer.
- Existing button/stick repeat timers are reused and cancelled promptly. Motion axes and trigger
  values are read once and reused for hold tracking and UI translation. No navigation-axes data
  object is allocated on the running-game raw-input path.
- Physical input state is bounded by the currently held keys/devices and is removed on release
  or device disconnection. Window bridges and observers are removed with their owners.

## Validation

- Production routing, controller, binding and dialog-bridge Kotlin compiled against the Android
  and Compose API libraries available in the workspace. The isolated check supplies resource
  identifiers and display-root type signatures; it is not a substitute for the full app build.
- Eight plain JVM policy tests cover ownership, global navigation isolation, blocked callbacks,
  context/page generation, confirm-side policy, system keys and neutral-before-reuse behavior.
- Added Android-backed regressions for Guide/Start in dialogs, Confirm/Back target roots, text
  and system-key pass-through, cross-window hold quarantine, omitted key-up recovery, settings
  key capture, stale raw sinks, held sticks, pending long presses and repeat cancellation.
- Reviewed preparation/error UI, game-session replacement, actual menu/dialog pages, background,
  state work and native teardown with the player integration. Background-media classification
  remains unchanged.
- Full Android/Robolectric execution and app compilation are part of the combined PR CI gate.
  Physical device-specific Guide delivery and Android TV driver behavior still require device
  testing; this audit does not claim a new hardware test.
