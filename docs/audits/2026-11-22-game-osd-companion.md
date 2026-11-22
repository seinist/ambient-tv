# Companion game OSD and held controls audit — 2026-11-22

## Scope and resulting behavior

The phone's gamepad page now stays in place while the TV game OSD is open. The same
page switches between gameplay and D-pad/OK/Back/Resume controls. Loading, native
state work and TV backgrounding have explicit inactive states instead of being
mistaken for a usable menu. Unavailable actions are omitted, as requested.

The menu uses the existing remote D-pad treatment, with 48 dp minimum controls.
Short landscape windows place the D-pad beside the actions; narrow/short windows
remain scrollable. Touch options are offered while playing, not over the TV menu.
The compact rewind chip occupies the existing header in short windows so it does
not take another row away from the gameplay surface.

## Protocol and input ownership

- `CompanionGamepadInfo` adds a nullable interaction key and monotonic context
  revision. Older TVs keep the existing ready/menu behavior; an unknown mode never
  grants navigation or gameplay. Existing per-port action labels remain compatible.
- A new `gamepad_command` carries the game-session ID and the revision actually
  displayed or held by the phone. A queued command cannot acquire a new revision
  from a later ACK. The TV checks the authenticated controller claim, current port
  configuration, game identity and context before executing it.
- A separate `canResume` capability leaves D-pad/OK/Back usable during a netplay
  handshake while hiding Continue and rejecting both new Resume commands and old
  menu toggles. Changing this capability invalidates the previous context ticket.
- New gameplay snapshots carry the same ticket. Late frames from before a menu,
  resume or game replacement cannot re-enter the emulator. A new-context neutral
  frame is an ordered edge; it is not coalesced away before the next press.
- Menu keys use the registered in-process TV input endpoint, not an app-wide remote
  lease or arbitrary keybindings. Production dispatch additionally requires the
  actual `GameInputRouting` mode to be MENU/DIALOG, closing the interval before
  Compose publishes a native menu callback.
- One long-lived controller translator observes physical phone-controller input in
  gameplay, menus and blocked states. It retains held-button/stick quarantine across
  menu pages and game IDs, honors the user's confirm side, and prevents app-level
  shortcuts from firing. Local touch-options navigation stays on the phone.

## Held OK and rewind

- Menu OK has real DOWN/UP/cancel edges, so the TV's held rewind control works with
  the phone D-pad and a controller connected to that phone. A single bounded lease
  pins the release to the original TV window. Page changes, loss of claim/focus,
  backgrounding, disconnect and a missing heartbeat cancel it without activating
  a newly focused item.
- Touch and physical owners share a client hold. Releasing one owner does not
  release another; renewals retain the original ticket. A stale release cannot
  release a newer page's confirm lease.
- Direct gameplay rewind is advertised only to player port zero, only when the TV
  reports usable buffered history. Touch and physical rewind hotkeys share its hold.
  The displayed duration is approximate, following the player's native snapshot.
- Both leases use a 1.5-second watchdog and 500 ms renewals while held. No renewal
  starts another key press, and no rewind is toggled permanently on. Explicit
  releases remain safe through context changes and receiver consent/background
  gates. Root capability/lifecycle guards remain authoritative.

## Integration audit and corrected findings

1. The first implementation re-stamped navigation with the latest ACK. Corrected:
   commands and held renewals retain the originating game/context ticket.
2. A paused gameplay-neutral packet could cancel a newly started menu OK hold.
   Corrected: gameplay neutralization and menu-lease teardown are separate.
3. Opening the menu initially introduced two revisions for the same root page.
   Corrected: the immediate menu transition preserves the current local page key;
   the player's equal page snapshot does not invalidate the first phone interaction.
4. A phone in a paused/busy state could wait the previous one-second idle interval
   to see Resume finish. Cached `gamepad_status` ACKs now poll at 250 ms while an
   active new-protocol controller is paused. Normal full status remains once per
   second, preserving action-label updates and settings-return traffic. The extra
   polls do not enumerate media, decode artwork or call native core APIs.
5. Menu/rewind commands use the existing bounded FIFO and compact pipelined ACK
   path. Directions can repeat; Back/Resume cannot repeat through multiple dialogs.
   Confirm renewals preserve one held key instead of issuing repeated activations.

The player integration publishes the same page identity to `GameInputRouting` and
`CompanionGamepadSession.setInteraction`, including state-dialog focus ownership.
It supplies `setRewind` from the existing capability policy and actual native
buffer snapshot, and releases companion rewind through normal player teardown.

## Validation and limits

- Added 23 focused cases: 14 session/context/claim/protocol/watchdog cases, six
  physical-controller/menu transition cases, and three shared held-owner cases.
- The actual session, input translator, native-window hold bridge and Compose menu
  sources compile with the pinned Kotlin/serialization/Compose compilers and real
  Android/Compose APIs. Only generated resource IDs and unrelated display/pointer/
  remote-surface APIs were supplied as compile-only boundaries in the host harness.
- All three plain JVM held-owner tests pass. The 20 Android/Robolectric cases and
  full `CompanionManager`/gamepad-screen compilation are part of the PR CI gate.
- `git diff --check` passes. No phone/TV/controller device test or measured input
  latency improvement is claimed by this source and host-test audit.
