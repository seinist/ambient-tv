# Controller and touch UI feature audit — 2026-11-22

## Completed feature

- One reactive device source serves the settings editor, player assignment settings and the
  in-game controls panel. It listens for Android device add/remove/change events and includes
  trusted paired Companion handsets. Stable descriptor keys keep existing rows attached to
  the same device when another controller appears or disappears.
- Console artwork's presentation data is now separated from its Compose renderer. The editor,
  local touch overlay and Companion controller use the same target/label mapping. Game Boy/NES
  use two face buttons; Mega Drive offers three/six buttons and Master System/Game Gear
  variants; six-button systems have an actual two-row face area, with Libretro L/R positioned
  as X/Z where appropriate. PlayStation uses its familiar symbols. Every system also has an
  explicit full RetroPad option for unusual core mappings.
- Shared preferences control size, opacity, independent portrait/landscape position, radial
  stick deadzone, D-pad diagonal width and optional hide-after-gamepad-input behavior.
  Increasing size is bounded by available space. Geometry retains minimum 48 dp input targets,
  separate utility/shoulder targets and the permanent menu target.
- The same bounded options UI is reachable in general controls settings, the game controls
  OSD and the phone controller. The visual variant remains advisory; it does not change the
  core's selected controller type.
- A physical controller attached to the Companion phone can drive the TV through the existing
  authenticated gamepad session. Touch and hardware button masks are unioned; analog axes use
  the stronger input. The TV's existing profile/port authority and wire format are unchanged.

## Integration audit and fixes found

1. **Double deadzone:** `RetroPadInput.touchStick` and `remoteState` previously applied another
   fixed 10% axis deadzone after touch's radial normalization. Virtual input now only validates,
   clamps and converts to Libretro units. Hardware stick filtering remains unchanged.
2. **Unsafe automatic hiding:** presence of a controller is not evidence of active gameplay.
   Hide decisions now require an enabled physical gamepad's actual mapped button/stick state.
   Axis noise, disabled ports, a held touch elsewhere, paired TV controllers and virtual Android
   navigation events do not trigger hiding. A visible restore button recovers touch controls.
   Removing the active device also restores them. The local game viewport stays the same size
   when the overlay hides or returns, so this does not recreate its native surface.
3. **Phone navigation interception:** the Companion raw input sink is restricted to actual
   gamepad/joystick devices. System Back and virtual navigation sources keep their existing
   behavior. The sink and gameplay touch receivers are removed while customization is open,
   on background/pause and on exit.
4. **Mixed input release:** hiding/removing touch releases only touch's contribution; it must
   not clear a physical button still held. Removing hardware likewise preserves touch. The
   mixer has explicit independent clear operations and tests for both directions.
5. **Settings recomposition:** opacity-only changes retain the input router and held contacts;
   layout, target-map, deadzone and diagonal changes replace the router and release old
   contacts. The lifecycle gate continues to require a fresh DOWN after foregrounding.
6. **Settings portability:** `touch_style` and validated `touch_variant.<system>` values live
   inside `RetroInputConfig.encode()`. Existing `retroInputSettings` export/import, live reload
   and Companion backup classification therefore transport them together with controller
   mappings. No new database, credential storage or raw device-session identity is introduced.

## Performance audit

- Layout/presentation construction is remembered by size, mode and settings. It is not done
  for each finger movement. Connected/paired device list merging is also remembered.
- The Companion frame holder and mixer are plain holders, not Compose state; high-rate axes
  do not invalidate the whole phone page. Continuous hardware/touch motion still uses the
  existing approximately 60 Hz sampler. Button edges and transitions to a neutral axis bypass
  that sampler. The idle 500 ms heartbeat and TV deadman remain unchanged.
- Automatic-hiding checks use the input engine's already-normalized device state rather than
  reclassifying vendor trigger/stick axes on every sample.
- No network discovery, ROM access, thumbnail decode or SQLite work is added to input paths.
- This is a workload/code audit, not a measured device frame-time or input-latency result.

## Validation evidence

- Isolated JVM suite: **23 tests passed**, using the real production layout/router/settings
  sources and pinned Compose geometry/Kotlin dependencies. Includes **5,904** combinations of
  console template, mode, viewport, scale and position, checking bounds, minimum target size,
  inter-control overlap and menu clearance. Also covers two/three/six-button rolling chords,
  shared settings round-trip, malformed settings, stick deadzone/diagonals and mixed input.
- Updated touch layout/controls and RetroPad input sources compile against the actual pinned
  Compose/Android APIs in the isolated compiler harness. This is not a full APK build.
- Added real Compose regression tests for opacity changes while holding a button and overlay
  hide/restore with unchanged game viewport; added RetroPad tests for fine virtual travel and
  active-controller detection. Full Robolectric/application/Companion test execution belongs
  to the integration CI gate.
- `git diff --check` passes for this feature.

## Remaining device acceptance

Before a release, exercise a narrow phone and a TV plus phone: two-thumb gameplay, six-button
rolling, portrait/landscape changes, controller unplug/replug, phone lock/foreground, OSD,
customization while a physical button is held, backup restore and phone/TV settings sync.
No physical phone/TV frame-time, accessibility-font or Bluetooth latency measurement was
available in this workspace. The core remains authoritative for mappings; full RetroPad is
available when the console illustration does not match a particular core's configuration.

## Independent review

Root review checked shared settings serialization, portrait/landscape geometry, real-device input gating, mixed-input release, the unchanged viewport when controls hide, and the Companion sampling/deadman path. Layout/input tests and the feature audit were reviewed before commit. Native descriptors and analog trigger transport are a separate following input-contract package. No remaining blocking finding was identified; complete Android CI and physical-device acceptance remain distinct gates.
