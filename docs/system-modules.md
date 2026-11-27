# Downloadable system libraries

This is a staged migration. The existing APK build and runtime paths remain
unchanged until each external module is tested. Do not label an APK modular
until it actually omits the corresponding engines.

## Scope

Optional: Monogram, SmartTube, Spotify native receiver, MPV, VLC, Moonlight,
LibretroDroid, ProjectM, SVP and shader runtimes/assets.
Keep Media3, Radio Browser, weather, Plex, Jellyfin and Emby in the base.
Retro cores remain separate downloads in both distributions.

Both APK variants must use the existing applicationId and signing identity,
with a coordinated versionCode and explicit update-channel preference.
The Full APK ships compatible embedded engines; Modular omits them.
Do not reset accounts, library databases or preferences when changing variants.
Full prefers a compatible, verified downloaded update at a cold start and
falls back to embedded code when no such update is active. User removal of an
override does not delete the embedded engine. No hot replacement of JNI/JNA.

## Signed catalog v1 (initial implementation)

Release tags: modules-*. Each release includes manifest.json and manifest.json.sig
(ECDSA P-256/SHA-256 DER over the exact manifest bytes). Only the pinned host
trust key may authenticate a release. Asset names are
module--abi--profile.zip. Each signed entry binds the module, ABI, profile,
monotonic module version, exact host API, minimum Android SDK, asset byte count
and lowercase SHA-256. Supported profiles: baseline, optimized, optimized-a57.
Cortex-A57 applies only to ARM64 and is explicitly selected; architecture-neutral
packages use baseline only. Resolve the actual process ABI, not hardware ABI.
An authenticated missing candidate permits fallback; invalid signatures do not.
A persistent high-water version prevents unsolicited downgrade; rollback requires
explicit user selection of a previously verified compatible version.

SystemModuleChannel implements the signed catalog and selection contract;
the trust key is passed by the caller, which must pin it. SystemModuleTransport fetches a named release from fixed GitHub HTTPS origins;
SystemModuleStore stages the verified ZIP and its signed catalog, checks bounded
archive entries, persists a monotonic version floor and atomically replaces the
selection pointer. Rollback re-authenticates the previous archive. Removal clears
the selection but preserves the version floor. No executable files are extracted
or loaded yet. The caller must supply the pinned key and private storage root;
release discovery, runtime integration and safe garbage collection remain open.

## Next implementation stages

1. Extend the implemented bounded HTTPS transport and private ZIP store with
   release discovery, cancellable streaming/progress, a pinned-key host factory,
   safe materialization (regular files only, no symlinks), per-file hashes, ELF
   ABI and 16 KiB checks, directory durability and safe garbage collection.
   Reject unknown dependencies and dependency cycles. Never load a partially
   installed package.
2. Versioned module host API and process-start loader with isolated classloader,
   read-only executable DEX files and native dependency resolution. No mixing
   incompatible JNI/UniFFI/JNA bindings. Keep non-optional UI in the APK; replace
   compile-time availability gates with reactive installed/usable state.
3. Monogram first: keep TelegramRepository and the UI in the host, relocate
   adapter and matched MTProto/UniFFI/JNA/Rustls package; preserve session data.
   Test credentials, login, restoration, logout, thumbnails and video seeking.
4. SmartTube and remaining native engines. Retain the Media3 fallback; audit
   shared FFmpeg dependencies between MPV, VLC and local audio/SVP before
   removing packaged binaries. Measure APK entry sizes rather than guessing.
5. Separate Full/Modular packaging and variant-aware self-updates; incremental
   signed module release pipeline keyed on source pins, build recipes, ABI,
   compiler profile and host API. Publish only tested artifacts. Preserve
   source and notices for GPL and other licenses; download does not waive them.
6. TV/phone settings for install/update/remove/rollback and restart activation;
   hide unavailable systems from navigation, Home and Search. End-to-end tests
   for fresh install, upgrade, switching variants, offline fallback, interrupted
   download, corrupt update, storage exhaustion and real-device playback.

Direct executable downloads are intended for the GitHub distribution, not
Google Play. A future Play distribution requires a separate compliant delivery
strategy (e.g. Play Feature Delivery).
