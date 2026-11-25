# Game player OSD and phone controls

Games use Ambient TV's shared player chrome, action bar and right-side panels. Their actions
and input lifetime belong to the active game, separately from background video/audio playback.
The game starts with this chrome hidden. It is shown only while the game menu is open and is
removed immediately on Continue. The fullscreen ROM route also suppresses the handheld media
mini-player and Companion playback bar during preparation and play.

## Phone picture and control areas

Landscape uses side areas for Menu, rewind, Start, Select, shoulder buttons and stick-click
controls, with no toolbar taking height from the picture. The layout fits the largest picture
between those areas, keeps utility targets at least 48 dp and adapts the number of columns to
the available height. Very short windows use a compact local pad arrangement. In portrait the
pad stays in its separate deck below the picture, with Menu and rewind above it. The picture
keeps the same bounds when the menu opens, rewind becomes available or a connected gamepad
temporarily hides touch controls, so these changes do not resize the native surface.

On phones, the native surface follows the core's current display aspect, including aspect
overrides and rotation, so it no longer covers the side areas with opaque black bars. The
existing video-style ambient backdrop remains visible there and behind the controls. Integer
scaling retains the full central area so the core can fit its source pixels without a smaller
surface changing the whole-number scale. Its internal integer borders can therefore remain.
The separate Companion controller keeps its existing layout.

Touch customization also offers **Button vibration**, disabled by default. It requests a short
20 ms pulse from the phone's vibration motor on a newly pressed game button, including face,
Start/Select and shoulder buttons. Android receives the touch-feedback usage, so device/system
haptic policies still apply. Devices without a vibrator silently ignore the request.
The D-pad, analog movement, held-button motion and releases do not produce a pulse. Local play
and Companion touch controls use the same saved preference. Physical gamepads, keyboards and
remotes never trigger this handset pulse. Separately, the game's Rumble option (on by default)
routes core vibration events only to assigned physical gamepads connected to the playback device.

## Available actions

The action bar uses the full available width and the video player's transport order:
Rewind, Continue, Restart, Stop, Fast-forward. Quick save, quick load and saved states follow
in that left group; picture, core, control, information and settings actions form the right
group. Every game system uses the same positions and simply omits unavailable or hidden
actions. Phones use the same fullscreen grouping without requiring the video player's
orientation switch, and overflowing groups remain horizontally scrollable.

The game OSD only composes actions which can currently be used. Unsupported core features,
unavailable saves, an empty rewind history and actions blocked by hardcore/netplay or an active
state operation do not appear as disabled icons. The OSD button settings use the same available
action list. Explicit hidden-button preferences continue to use the existing backup/TV-sync path.

Quickload selects the newest compatible manual state. State cards still allow managing old
files; their Load action is absent when the current session cannot load the selected file.
Compatibility uses the session's in-memory core identity, including the supported legacy stamp.
Execution checks remain in place in case availability changes between rendering and activation.

**End game** saves the existing checkpoint and closes the native game session. It does not
advertise continued background gaming. The action remains in the game settings panel even when
its direct OSD button has been hidden. Settings always remains reachable.

## Navigation and controllers

- While playing, the existing raw game mappings and Start+Select shortcut apply.
- In the OSD, D-pad/stick, Confirm and Back operate the menu. Guide/Mode and Start have game-menu
  semantics. Confirm respects the configured A/B preference.
- General app shortcuts cannot navigate to the library, search or unrelated settings while a
  game owns input. Player settings/info shortcuts open the corresponding game panel.
- Opening a panel records its initiating button/row. Back restores that origin, including core
  categories, value selectors and the save-state strip. A disappearing action falls back
  to Continue or the always-present Settings button.
- Native and embedded dialogs share the same input boundary. Held keys, repeats and stick axes
  must be released/neutralized after a context or page change, so the old input cannot activate
  a new page or enter the resumed emulator.

Android system Home/Power/volume behavior is preserved. A controller's Guide button is handled
when Android delivers the corresponding controller/menu event to the app.

Core-option values, picture filters (including installed shader packs) and the selected disc
use the shared select control directly in their settings panel. Opening or choosing a value
does not add a navigation page. Back first dismisses the selector and returns focus to its
trigger; configuration panels and core categories retain their normal page navigation.
The game drawer owns its right-edge alignment, so its shared entrance/exit animation stays on
the right side of the TV picture. Subpages and Back keep that same position. The narrow panel
in phone landscape and full-width panel in phone portrait retain their shared sizing.

## Companion phone

The phone stays on the gamepad page when the TV opens its game OSD. That page switches to
D-pad, OK, Back and Continue. A controller attached to the phone uses the same menu mode.
Loading, busy, background, playing and menu/dialog states are distinct; a generic `ready=false`
does not imply a navigable menu. Older peers retain their compatible legacy behavior.

During netplay state exchange, Continue is absent on both devices while menu navigation and
Cancel remain available. Waiting for a guest does not pause a game until synchronization starts.
Background synchronization never resumes emulation before the app is active again.

New peers attach the active game/context revision to gameplay and menu commands. The TV checks
the current claim and page, rejects stale commands, releases held input on context changes and
retains its deadman fallback. This also prevents an old phone repeat from activating a newer
page after a delayed acknowledgement.

## Rewind

Rewind appears when enabled in Games settings, supported by the current core/session, and actual
native history is present. Hardcore/netplay restrictions still apply. Hold to rewind and release
to stop:

- In the OSD, holding previews rewind while game input stays blocked; releasing pauses again.
- On a phone playing locally, a separate touch control rewinds without opening the OSD.
- A Companion phone assigned to player one can hold its rewind control while the TV is playing.

The displayed duration is approximate and comes from the currently stored history and core
frame rate, not the configured target. Fast-forward can capture multiple emulated frames between
stored states. The existing target of 20 seconds and 64 MiB buffer budget remain upper limits;
large serialized states can leave less history or make rewind unavailable.

Holds release on cancellation, leaving the control, lost window/lifecycle focus, disposal and
context changes. Accessible activation is bounded. Remote holds have a short renewable lease,
so a disconnected phone cannot leave rewind or menu confirmation pressed.

## Efficiency and validation

Input descriptors keep their last native revision for the whole session. Core-option snapshots
survive category/option navigation, are refreshed on real changes/opening the OSD, and
are parsed off the UI thread. Native option access is serialized on its owning GL thread.

Rewind UI observers read atomic count snapshots at a bounded rate. They neither dereference the
native ring from the UI thread nor wait for GL. The buffer is configured on capability/settings
changes, not on panel navigation; leaving netplay begins fresh history.

The phone picture layout reads a lock-free native aspect snapshot while the session is active.
Geometry changes update that snapshot on the core's owning thread; UI reads neither access the
core nor wait for GL. Ambient rendering reuses the existing small, lifecycle-bound frame sample
and adds no separate frame capture.

Cheat edits are queued as a single ordered GL update. Unchanged lists do no native work;
superseded updates and session teardown invalidate pending batches without blocking the UI.

Feature audits: [input](audits/2026-11-22-game-osd-input.md),
[actions](audits/2026-11-22-game-osd-actions.md),
[focus](audits/2026-11-22-game-osd-focus.md),
[Companion](audits/2026-11-22-game-osd-companion.md),
[rewind/performance and integration](audits/2026-11-22-game-osd-integration.md).
