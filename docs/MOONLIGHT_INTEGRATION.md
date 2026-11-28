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

## Native latency and adaptive buffering

- The opt-in MediaCodec renderer copies picture fragments synchronously from
  the active native decode unit directly into the codec's writable input
  buffer. JNI validates the direct address, exact byte count and capacity.
  The unit is thread-local and cleared before native packet ownership ends.
  Codec-configuration NALs retain the separate byte-array path, and the base
  renderer keeps its legacy callback. This is one CPU copy, not end-to-end
  hardware zero-copy.
- If a surround stream falls back to stereo, Opus PCM is downmixed natively
  before the JNI short-array copy. ARM64 uses NEON for four frames per iteration,
  including saturation; other ABIs and remaining frames use the scalar
  implementation. Native allocation failure retains the Kotlin fallback.
  The Opus source pin is unchanged; the archive is now built locally.
- The session-local network policy samples ENet RTT variance, observed frame
  gaps and network status once per second. After two pressure samples it may
  increase the audio reordering reserve (4–30 ms, in addition to the FEC
  block's negotiated duration) and the video UDP receive-capacity tier.
  After ten consecutive stable samples the reserve falls by 3 ms, down to
  the latency/balanced/smooth floor (4/6/10 ms), and video capacity falls one
  tier. Missing RTT telemetry cannot trigger shrinkage. Video receive capacity
  preserves the upstream 2048-packet floor and caps at 4096 packet-equivalents;
  the OS may clamp or reject changes. This is a kernel capacity, not an
  intentional video playout delay. Only the receive thread calls setsockopt.
- Android CI runs source-level native regression harnesses for JNI copy bounds,
  thread ownership, scalar/ARM64 NEON downmix parity (via QEMU) and socket
  capacity limits. The Kotlin policy has deterministic hysteresis tests.
  These do not replace device measurements of actual decoding, presentation,
  network jitter, underruns or input-to-photon latency.

- Native PCM uses a session-owned direct ByteBuffer for stereo or compatible
  surround output. The sink writes synchronously using AudioTrack's nonblocking
  ByteBuffer API; only a partial-write tail is copied into its own pending
  storage. Non-opt-in renderers or direct buffer allocation failure keep the
  short-array path. The borrowed view cannot outlive the callback.
- The pinned NanoRS submodule uses a specialized single-erasure reconstruction
  with the negotiated parity coefficients (including custom audio parity),
  explicit ARM64 XOR vectorization and the existing finite-field SIMD kernels.
  Multi-erasure reconstruction remains on the general solver. A regression
  harness checks unaligned buffers, odd lengths, every single erasure/parity
  row, multiple erasures, insufficient parity and concurrent initialization.

- Opus 1.6.1 is fetched from the reviewed full commit and built from source
  with the consuming NDK 30 toolchain for every requested ABI. Static PIC,
  -O3, ThinLTO, upstream runtime SIMD selection and ARM64 Cortex-A57 tuning
  are enabled. Fast-math is disabled; the original verified archive hashes
  remain provenance records, not the linked library. CMake and Ninja are
  required on the build host.
- ENet selects an optional ARM64 IEEE CRC32 implementation through HWCAP;
  generic builds keep the byte-table implementation. CRC32C is not used.
  The tests compare the actual implementation to a polynomial reference.
- NanoRS uses direct one- and two-erasure reconstruction and restricts general
  elimination to the active matrix columns. The video receive owner adapts
  recvmmsg batch width between 1 and 8 without waiting to fill a batch.
  Queue-depth telemetry uses relaxed atomic loads; ownership, shutdown and
  condition-variable behavior remain protected by the queue mutex.

## Release and device acceptance gates

- All four native ABIs must compile, pass alignment checks, and appear in the
  correct verification APK. Run the native-enabled unit tests and R8 check.
  The CI verification APK intentionally omits unrelated native media builds;
  it is **not** a distribution artifact and does not replace the full build.sh.
- Native Android and common-c license texts, available ENet/NanoRS notices,
  exact source pins and patch list are packaged under assets/moonlight-licenses.
  The repository's CC0 dedication does not change the GPL-3.0 distribution
  obligations for the combined APK. The upstream dependency update
  [31b7003](https://github.com/moonlight-stream/moonlight-android/commit/31b700304a9eb2827516f52c33d9da0845fc1ece)
  identifies the [exact Android dependency build](https://github.com/cgutman/moonlight-mobile-deps/tree/419349aa689e8749a9fb91a22dc45531ddd3df45),
  including NDK 29.0.14206865 and submodules
  [Opus 1.6.1](https://github.com/xiph/opus/tree/22244de5a79bd1d6d623c32e72bf1954b56235be)
  and [OpenSSL 4.0.2](https://github.com/openssl/openssl/tree/f089acdf4bc7ba94a79f4bf6eb7362c3e7d14aa9).
  Exact license texts, Git blob hashes of all eight static archives, and
  source/build references are packaged and checked. New binary hashes require
  review before an upstream upgrade is accepted. The release distributor must
  still provide complete corresponding source and notices for the combined
  APK; passing the license-asset check alone is not legal certification.
- Test against Windows/Linux Sunshine on Shield, Philips TV and phones:
  pairing/revocation, WOL, cancellation at every handshake stage, hotplug and
  Companion handoff, stereo/surround, SDR/HDR10, codec negotiation, display
  mode restoration, frame pacing, latency and long reconnect/stop cycles.
- Test standard builds with Moonlight disabled and verify no unrelated
  playback, gaming, settings, backup or Android Auto regression.

**Do not merge or enable Moonlight in normal releases until these gates pass.**

### Follow-up audit gates

- Native decode-unit bounds and JNI callback failures are checked before allocation; vendor non-direct buffers use a bounded synchronous array fallback. PCM writes must remain channel-aligned, including partial tails. The queue estimator extrapolates only after observed playback progress.
- Socket capacity is read back from SO_RCVBUF; Linux bookkeeping doubles the reported capacity, which is normalized for tier changes. Artwork lookups and proof reuse are bounded and identity-scoped. Launch and quit still require a fresh authenticated probe.
- The host Opus comparison builds a separate O2 baseline and O3/ThinLTO candidate, runs upstream API/decode tests and compares identical multichannel bitstreams and loss concealment, reporting median CPU time. These are host measurements, not Shield/Android device benchmarks or a substitute for official external conformance vectors.

- Follow-up hardening: irreversible audio-sink failure is quarantined; native audio scratch is freed even if JNI attachment fails, and global references are collected after the stop worker joins. The depacketizer caps native frame assembly before allocating additional fragments. UDP receive-buffer resizing retries at most three times with monotonic backoff. Pinned artwork connections use a dedicated bounded HTTPS pool (not shared with launch, pairing or quit); cache reads no longer hold the global disk-writer lock. Input queue items are invoked directly without drain-time closure allocation. Opus comparisons time only in-memory decoding and cover the exact legacy 5.1 mapping plus representative negotiated 7.1 and independent-channel modes at 5/10/20 ms; device measurements and external normative vectors remain separate acceptance gates.
