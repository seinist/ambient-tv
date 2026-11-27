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
and lowercase SHA-256, with optional exact module dependency versions. Supported profiles: baseline, optimized, optimized-a57.
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
the selection but preserves the version floor. SystemModuleMaterializer extracts
verified packages into new private trees and validates each binary before the
SystemModuleRuntime activates a host-approved entrypoint. The caller must supply
the pinned key, process ABI, host API, and module contracts. No production engine
uses this loader yet; release discovery and safe garbage collection remain open.

## Next implementation stages

1. Extend the implemented bounded HTTPS transport and private ZIP store with
   release discovery, cancellable streaming/progress, a pinned-key host factory,
   directory durability validation and safe garbage collection. Keep the existing
   verified archive, materialization and dependency checks.
2. Integrate the host-approved runtime loader and stable host API into the app's
   production initialization; audit JNI/UniFFI/JNA boundaries and native namespace
   behavior. Keep non-optional UI in the APK; replace compile-time availability
   gates with reactive installed/usable state.
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

### Package store hardening

The v1 ZIP format intentionally excludes ZIP64, archive comments, special-file
modes, overlapping or gapped local entries and inconsistent local/central
headers. The central directory is bounded before opening ZipFile. Package
payloads and selection metadata are synced before publication; an uncertain
post-rename sync failure retains the package rather than deleting a possibly
referenced version. The stored archive and metadata must be regular files,
not symlinks. HTTP redirects stay on HTTPS GitHub release origins and a
redirect to github.com must preserve the original release path. Content-Length
must match the signed package size when provided; streamed byte count and hash
are checked even for chunked responses. Tests cover signed malformed ZIPs,
symlink attributes, local header mismatches and invalid rollback targets.
Real-device filesystem durability and CI execution still need verification.

### Executable materialization and activation

The signed ZIP is rechecked before and after extraction. Every entry is
created as a regular file in a fresh private directory; writable descriptors
are opened before setting read-only permissions, as required for dynamic DEX.
The extracted bytes are hashed and reread. Before activation the runtime
rejects unexpected files, symlinks, missing files and changed hashes.

ELF validation requires the exact Android ABI, little endian ET_DYN, bounded
program headers, at least one executable segment, 16 KiB (or larger device)
alignment, no interpreter, executable stack, writable executable segment,
RPATH/RUNPATH or text relocations. Dynamic string tables, SONAME and DT_NEEDED
are checked. Only an explicit Android NDK library allowlist or libraries in
the same package satisfy native dependencies. DEX supports standalone
035/037/038/039/040 files with bounded header tables, map entries, SHA-1 and
Adler-32. DEX 041 containers are deliberately not supported yet; ART performs
final bytecode verification. Module dependency versions are exact, with
unknown, missing, conflicting or cyclic dependencies rejected before activation.

A host contract fixes the entrypoint class (never supplied by the release).
DEX adapters use DexClassLoader with the host loader as parent and the package's
own native search directory; the entrypoint must originate from that loader and
implement the preserved host interface. Native-only modules can be explicitly
allowed by a host contract, load dependencies first via absolute System.load,
and reject bundled library name collisions. Asset-only modules reject code.
The runtime prepares the whole graph before executing any new code and never
hot-swaps loaded modules. Loaded or partially activated trees are retained
until safe process-lifetime reclamation is implemented. Signed code is trusted
with the app's privileges; a classloader is not a security sandbox.

Initial restrictions: cross-module native linking, shared SONAME coexistence
(including libc++_shared), alternate DEX formats, and full APK native override
semantics need engine-specific design and Android device validation. The
generic runtime is not wired into the production engine paths yet.
