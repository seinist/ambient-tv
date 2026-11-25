# Moonlight / Sunshine — integration work

## Scope and status (2026-11-25)

The goal is a native Moonlight GameStream client in Ambient TV, not launching an
external APK and not passing the stream to Media3, MPV or VLC.

**Experimental groundwork only** in this branch:
- normalized host profiles and explicit user-controlled enabling;
- defensive, bounded, unauthenticated GameStream serverinfo probing;
- on-demand mDNS discovery via Android's NsdManager;
- feature-gated system identity and Settings host management;
- address/XML unit tests.

This does **not** implement pairing, mutually authenticated TLS, an application catalog,
transport, game streaming, controller passthrough or recovery. Normal builds have
MOONLIGHT_EXPERIMENTAL=false. Set -PMOONLIGHT_EXPERIMENTAL=true for the host setup UI.
The app must never advertise these experiments as a working Moonlight player.

## Upstream and licenses

- Moonlight Android: https://github.com/moonlight-stream/moonlight-android
- Version reviewed: v12.2 (2026-09-12).
- Native transport: https://github.com/moonlight-stream/moonlight-common-c
- The common C library uses a **modified ENet submodule**. Never replace it
  with a generic system libenet.
- Official Moonlight Android/common-c are GPL-3.0. Reuse must preserve upstream
  notices and corresponding source, reproducible pins and ABI/NDK build rules.
  Ambient TV's release APKs already use GPLv3 distribution terms.

## Security model

Moonlight host profiles contain **only** user-entered address, port, name
and optional MAC. Pairing keys and pinned server certificates must be stored
separately in device-secure storage; never export them through backup, TV sync,
logs or discovery data. An unauthenticated serverinfo result is not proof of
host trust, pairing, or streaming support. HTTPS APIs for application listing,
assets, launch, resume and termination MUST use reviewed upstream Moonlight
certificate pinning and mutual TLS. Never use a trust-all certificate verifier.

The standalone Moonlight streaming lifecycle must not be confused with
Libretro cores or background video. Disconnecting a stream must not terminate
the remote PC application. Input ownership must switch atomically and deliver
all held-key releases to the remote.

## Development order (2026-11-25)

Implement all technical features and complete automated CI/code audits first.
Sunshine/device-based acceptance tests are deliberately postponed until the
technical integration is complete. No claims of verified live gameplay before then.

## Work still required before release

1. Pin and integrate upstream Moonlight Android Java/NDK, common-c and bundled
   ENet into all four target ABIs with exact licenses, source, reproducible builds.
2. Pair/repair/unpair, client certificates, pinned host identities, secure
   per-device key storage, real authenticated app catalog and box art.
3. Native streaming pipeline: MediaCodec, AudioTrack, Surface lifetimes,
   audio focus, codec/frame-rate/HDR capability validation, low-latency metrics.
4. Controller, keyboard, mouse, touch and Companion controller input sink
   with context generation, button releases and focus-safe OSD.
5. Full TV/phone pages, host/app detail, Home, search, favorites, recents,
   optional backup of non-secret settings, WOL and WireGuard.
6. Error/reconnect/pause/resume and lifecycle policies, host trust audit.
7. CI native builds and tests, plus device acceptance on Shield, Philips
   TV and handset with Windows/Linux Sunshine, including HDR and latency.

NO MERGE until the full native implementation is ready and validated.
