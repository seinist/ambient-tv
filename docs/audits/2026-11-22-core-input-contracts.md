# Core input contracts feature audit — 2026-11-22

## Completed feature

- `RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS` now copies the core's descriptions into owned,
  bounded storage. At most 256 descriptions of 256 UTF-8 bytes are retained; invalid ports and
  device fields are discarded. An atomic revision changes only when the actual rows change.
  Initialization and deinitialization clear the descriptions. No core-owned pointer escapes
  the environment callback.
- JNI converts these bytes using Java's UTF-8 decoder, including replacement of a truncated
  final character. The game player checks one atomic revision per second, then copies and
  parses descriptions only when needed. Opening the controls panel or changing core options
  refreshes this check promptly. No per-frame JSON construction or descriptor list allocation
  occurs on the core thread.
- Up to 64 bounded per-player RetroPad action names appear in the in-game controls panel.
  Touch buttons retain their console symbols and add a short action caption and complete
  accessibility description. The phone controller shows the same action captions and a full
  readable list in its options panel. Handset labels follow the TV's actual per-device remap.
- Physical L2/R2 pressure survives Android mapping, mixed physical/touch input, the local
  native bridge, the authenticated Companion connection and netplay. Libretro exposes it as
  `RETRO_DEVICE_ANALOG / RETRO_DEVICE_INDEX_ANALOG_BUTTON`, in the range 0…32767. Other
  RetroPad buttons and digital-only triggers return their normal 0/full-pressure fallback.
  L2/R2 swaps retain pressure; remapping a trigger to a non-trigger button retains the digital
  threshold. This is not arbitrary analog pressure support for every remapped button.

## Integration audit and corrections

1. **Coherent native delivery:** changed local input is sent as one complete per-player
   snapshot under one native mutex. Netplay uses a separate authoritative snapshot operation,
   so remote frames cannot overwrite the locally captured next input. A reusable seven-int
   JNI buffer captures local masks, four axes and two pressures atomically for lockstep.
   The app retains the prior destroyed-session delivery guard, so a late callback cannot
   inject an old player's input into a subsequent native session.
2. **Short taps:** digital releases and pressure-to-zero transitions remain visible through
   one core sampling interval. Netplay consumes these pending releases only after capturing
   the frame. Previously its Kotlin-only instantaneous mask could lose a down/up between
   lockstep ticks. Existing Android diagonal key codes remain supported.
3. **Native disconnect and lifecycle:** leaving netplay restores the actual captured local
   inputs and clears pending releases, instead of retaining the last remote frame. Native
   reset clears mask, pressure, axes and pointer state. The existing pause/resume path retains
   the stable mutex-bearing native input object. Companion pause now preserves held-input
   suppression until the handset physically releases its button or partial trigger.
4. **Duplicate Android trigger events:** after a device reports an analog trigger axis, a
   duplicate L2/R2 key event no longer replaces fractional pressure with full pressure or keeps
   its digital bit pressed after the axis releases. A controller that only reports digital
   trigger keys continues to work. Pressure mappings use the same cached profile as keys.
5. **Companion edge gate:** a short remote digital tap is deliberately held for the existing
   minimum polling interval. Its trigger pressure is retained through that interval, avoiding
   a zero-pressure/full-digital fallback spike. Queued re-press gaps remain neutral. Pressure
   begin/end edges are ordered by the network queue; continuous travel may coalesce.
6. **Companion short-hold pulse:** pulse bits are aggregated separately from held remote bits.
   Their scheduled release reaches the native bridge immediately, without waiting for another
   phone heartbeat. The coalescing check accounts for a newly created pulse.
7. **Optional protocol fields:** absent Companion trigger values retain legacy digital
   behavior. Older peers ignore the added fields. Action descriptions are omitted from the
   frequent realtime ACKs and sent on ordinary state polls. The phone retains omitted metadata
   only for the same game session; an explicit empty map clears it and a new session cannot
   inherit old labels. Claimed player identity also remains part of the presentation lookup.
8. **Netplay wire contract:** two additional pressure shorts require protocol/configuration
   stamp version 5. Version 4 peers fail the existing handshake instead of parsing misaligned
   frames. A frame now has an exact 16-byte input payload. Invalid masks and pressures are
   rejected; persisted states and Companion's JSON protocol version do not change.

## Performance audit

- Native pressed and pending-release sets are now fixed 64-bit masks. All 16 RetroPad IDs and
  legacy diagonal IDs 50…53 fit; out-of-range query IDs are guarded before shifting. This
  removes key-node allocation from local changes and every authoritative netplay frame.
- Local delivery uses one JNI call per changed player, instead of one per changed button and
  stick. Authoritative netplay application also uses one call per player. Its additional
  capture call reuses an existing array and performs no JNI array allocation per tick.
- Kotlin input aggregation walks devices once, with scalar axis/pressure accumulators, instead
  of constructing filtered device lists and a list of candidates for each axis. Immutable
  profiles and resolved player assignments are cached until settings or port ownership change.
  Identical aggregate input no longer dispatches `onChanged`.
- Companion pressure shares the existing approximately 60 Hz sampler, idle heartbeat,
  authenticated transport and deadman. It does not introduce another timer or render loop.
  Metadata parsing, label maps and native string copies occur outside these high-rate paths.
  Descriptor polling stops while the activity is in the background and after session teardown.
- This is an allocation/workload audit. No physical-device frame-time, Bluetooth latency,
  thermal or battery benchmark was available in this workspace.

## Validation evidence

- **17 focused JVM tests passed**, executing the real input/configuration/protocol sources
  and the exact netplay codec with pinned Kotlin 2.4.21 and serialization 1.11.0. They cover
  partial/digital pressure, remapping, mixed-input release, pause suppression, short-hold
  pulses, unchanged heartbeat coalescing, descriptor bounds, old/new JSON compatibility,
  metadata lifecycle, gated trigger releases and valid/invalid netplay frames.
- **Six native production-input contract groups passed under AddressSanitizer and
  UndefinedBehaviorSanitizer**: fractional and digital input, fast taps and independent ports,
  all 16 buttons on all four ports, legacy diagonal keys, authoritative versus captured
  netplay state, disconnect/reset, malformed numeric values, bounded descriptor ownership and
  10,000 concurrent atomic snapshot samples. LeakSanitizer was disabled locally because the
  workspace uses ptrace; CI runs the same sanitizer command without that local override.
- The actual updated touch Compose sources and full `RetroPadInput` compile against the pinned
  Android/Compose APIs. This is an API compile check, not a device rendering test.
- Native patch `0028-core-input-descriptors-and-analog-triggers.patch` applies exactly to the
  post0027 baseline. A fresh `fetch_libretrodroid.sh` then successfully applied the entire
  pinned patch stack; its resulting ten native/Java files match the audited versions.
- PR checks now run `scripts/test_libretro_input.py --sanitize` immediately after fetching
  LibretroDroid, before the existing Android/native compilation and complete unit-test gates.
- Added Robolectric integration tests cover duplicate hardware trigger events, profile
  remapping, partial-pressure deadman/pause and descriptor reset on game switch. Complete
  Android/Companion/UI tests and all existing ABI builds remain the integration CI gate.
- Removed a stale top-level `assertDoesNotExist` import from the earlier touch UI test; the
  pinned Compose version exposes it as a member. `git diff --check` passes.

## Independent integration review

The independent review traced the complete local and Companion paths into the patched
native reader, including the separate lockstep capture/application operations. Kotlin's
aggregate update and callback remain on the main thread; each resulting native snapshot
is protected by the input mutex. JNI checks the reusable output array before filling it,
descriptor strings are decoded as UTF-8, and core query IDs are checked before bit shifts.
The review also checked unchanged-input suppression, digital fallback after trigger-axis
detection, pending short-tap releases and session teardown. No additional blocking finding
remained in this package. Full Android compilation and the complete CI suite remain the
final integration gate; physical-device acceptance is listed below.

## Contract references and device acceptance

The [Libretro input API](https://docs.libretro.com/development/input-api/) defines unsigned
button pressure and the digital fallback. The pinned upstream
[`libretro.h`](https://github.com/libretro/libretro-common/blob/master/include/libretro.h)
defines input descriptors, device indexes and button IDs. Ambient TV retains its Android
profiles and bounded native touch layout; it does not import RetroArch configuration files.

Device acceptance should exercise a pressure-sensitive controller connected directly to the
phone and to a TV, the same controller relayed by a Companion phone, remapped L2/R2, digital-only
triggers, background/foreground, session switches, a held trigger when the OSD opens, and two
netplay peers on protocol version 5. Core action names depend on the core reporting useful
descriptors; absent names retain the system's console labels and controls.
