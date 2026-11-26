# Moonlight / Sunshine — native Ambient TV integration (experimental)

## Status as of 2026-11-26

PR #494 builds a native GameStream client **inside** Ambient TV; it does not
launch an external Moonlight APK or route game video through Media3/MPV/VLC.
The PR is a **DRAFT** and normal APKs still ship with the optional native core
disabled. No real Sunshine/Shield/Philips/phone session has been accepted yet.

### Implemented technical components
- Host profiles, on-demand Android NSD/mDNS discovery and bounded public serverinfo.
- Moonlight Android v12.2 with pinned common-c and patched ENet, 4 ABI build
  matrix, 16 KiB ELF alignment; original upstream protocol Java/JNI in a
  protocol-only module rather than upstream's launcher UI.
- Upstream PIN challenge-response pairing, per-install AndroidKeystore client
  identity, encrypted host certificate pins and HTTPS-only authenticated catalog.
  A host certificate change must not auto-repair or silently use HTTP.
- Cached multi-host application lists and artwork, favorites/recents, explicit
  PC wake button, browse/detail screens, sidebar/handheld navigation, global
  search and configurable Home shelves; no automatic NAS or PC wake on Home.
- Experimental StreamEngine using NvConnection + MediaCodec Surface + AudioTrack
  with separate media player lifecycle. 8-bit H.264/HEVC/AV1, tentative HDR10
  negotiation with Main10/BT.2020/PQ metadata and SDR fallback.
- Physical gamepads, keyboard, mouse, touch, input release on focus changes,
  native controller hotplug and rumble, and reuse of the existing Companion
  TLS phone-gamepad session, not a new unauthenticated receiver.
- Portable stream quality preferences: resolution, FPS, bitrate, H.264/HEVC/AV1,
  HDR10 auto/on/off, stereo/5.1/7.1. Host profiles and preferences are portable;
  certificates and keystore keys are never part of backup or Companion sync.

### In-player playback UI

The `MoonlightStreamActivity` now uses the same right-anchored
`RightPanelOverlay` / `RightSidePanel` used in Ambient TV's other systems.
The full-screen OSD deliberately has **no seekbar, timeline pause, chapter skip,
next episode, renderer selector or other file-video controls**, because the PC
desktop stream is interactive rather than a seekable video file.

The OSD's wired actions are:
- **Resume**: hides the overlay; the remote game continues.
- **Text**: sends at most 512 characters of UTF-8 to the connected PC.
- **Info**: displays stream status and sampled decoder, audio, frame and
  network metrics where supported.
- **Options**: opens the right-side playback settings page, with streaming
  quality, text entry, info and an explicit remote quit subpage.
- **Disconnect**: stops the client connection and leaves the PC game running.
- **Quit game on PC**: nested in Options, *only with explicit confirmation*;
  invokes authenticated Sunshine cancel; errors are reported without claiming
  success. It is never tied to Android Back or Activity destruction.

Quality changes made during streaming persist for the **next stream connection**.
They do not mutate live MediaCodec/RTSP parameters behind the user's back.
Back moves through panel subpages within the same right drawer and returns
the D-pad focus to the OSD button that opened the panel.

### Vector logo
`app/src/main/res/drawable/system_moonlight.xml` is the existing Android
VectorDrawable (a stylized display and moon). It is the Android-native
SVG-like vector asset, **not a standalone `.svg` file**. It is referenced by
`MediaSystem.MOONLIGHT` and used by the system menu, settings and player panel.

## Upstream and license

- Moonlight Android: https://github.com/moonlight-stream/moonlight-android
- Pinned reviewed release: v12.2.
- Native transport: https://github.com/moonlight-stream/moonlight-common-c
- common-c includes a **modified ENet**. Do not substitute an unrelated
  system libenet. Retain GPL-3.0 notices and corresponding sources.

## Security and lifecycle invariants

Pairing data must remain separate from portable profile preferences. Discovery
never confers trust. Application list/artwork and remote quit must use mutually
authenticated, pinned HTTPS. Disconnect must not terminate the remote process.
OSD/settings/menu transitions release held buttons, cursor inputs and vibration.
The libretro engine, video players and Android Auto do not own these resources.

## Audit follow-up

The audit introduced bounded XML/HTTP parsing and cancellable requests, an
authenticated post-pairing identity check, owner-scoped streaming and input,
serialized native start/teardown, explicit remote-app checks before quit,
codec capability negotiation, decoder and audio recovery safeguards, input
mapping/capabilities, pointer routing, aspect-ratio layout, bounded reconnect,
and sampled playback statistics. Catalogs use a 60-second process-local
authenticated freshness window, remain browseable offline, and are refreshed
only on visible entry/poll, explicit refresh or completed WOL. Artwork uses
the shared priority scheduler and certificate-scoped cache. The native feature
is still opt-in. Cancellation cannot undo a host launch already accepted by
Sunshine, and the current-game check and remote quit are separate host requests.

## Release and device acceptance gates

- All four native ABIs must compile, pass alignment checks, and appear in the
  correct verification APK. Run the native-enabled unit tests and R8 check.
  The CI verification APK intentionally omits unrelated native media builds;
  it is **not** a distribution artifact and does not replace the full build.sh.
- Native Android and common-c license texts, available ENet/NanoRS notices,
  exact source pins and patch list are packaged under assets/moonlight-licenses.
  The repository's CC0 dedication does not change the GPL-3.0 distribution
  obligations for the combined APK. Verify complete corresponding source,
  modification notices, and licenses/source provenance of the prebuilt Opus
  and OpenSSL libraries before distributing a Moonlight-enabled release.
- Test against Windows/Linux Sunshine on Shield, Philips TV and phones:
  pairing/revocation, WOL, cancellation at every handshake stage, hotplug and
  Companion handoff, stereo/surround, SDR/HDR10, codec negotiation, display
  mode restoration, frame pacing, latency and long reconnect/stop cycles.
- Test standard builds with Moonlight disabled and verify no unrelated
  playback, gaming, settings, backup or Android Auto regression.

**Do not merge or enable Moonlight in normal releases until these gates pass.**
