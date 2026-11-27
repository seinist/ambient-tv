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
the pinned key, process ABI, host API, and module contracts. The first production paths now consult the loader; release discovery and
process-lease garbage collection are implemented for the migrated modules.

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
semantics need engine-specific design and Android device validation. The first production engine paths now use the generic runtime.

## First production migration (development preview)

AmbientTvApplication registers SystemModuleHost without loading executable code.
The process holds a shared filesystem lease during module operations. Before
acquiring the first shared lease, an exclusive nonblocking lease permits cleanup
of old extracted runtime trees and unreferenced package versions, retaining
current and rollback packages. Other live processes prevent reclamation.
The existing core release public key is reused for this initial channel;
modules-* tags and the separate catalog schema distinguish module manifests.

ProjectM and MPV consult the module host before their original first-load
paths. Only an absent override falls back to the bundled runtime; an activation
failure must not load a second copy of JNI libraries into the same process.
ProjectM uses APK-owned C++ runtime, MPV uses approved APK-owned shared libraries
including FFmpeg. The signed catalog records their SHA-256 fingerprints, and
the host verifies the exact installed bytes before loading. Native package
generation traverses the dependency closure from verified Full APKs.
FFmpeg remains in the APK because local audio also links against it; independent
shared-runtime delivery, other native engines and DEX adapters remain open.
The Shader module currently contains only the signed Vulkan Slang preset assets,
not librashader or all filters. A selected external preset pack is used as a
whole, not mixed with APK files.

Settings > About / App updates exposes the initial migrated components,
version, download/update, rollback and removal. Selection is fixed on the first
use in a process. Restart after changing selection; native code is never unloaded.

Build commands:
- ./build.sh release or ./build.sh release-full: existing full distribution.
- ./build.sh release-modular-preview: separate development APKs under
  dist/modular-preview; excludes the ProjectM native pair and Slang assets.
  It is NOT the completed Modular APK. No stable app update manifest is generated
  and the regular APK updater is disabled for this preview.

The System module packages workflow validates and builds reproducible shader
packages on PRs. Manual publication is permitted only on main and uses signed
manifests, a fresh-main check, and a non-latest GitHub release. Native package
candidates can be built with scripts/package_system_modules.py from the verified
matching Full APKs and corresponding license notices; their public release
workflow is not yet enabled. No signed release or Android test result is implied
by this source change. Monogram, SmartTube, Spotify, VLC, Moonlight, LibretroDroid,
SVP and the executable shader runtime still require their individual production
migration. Variant-aware stable self-updates and complete Full/Modular packaging
remain required before the new distribution is ready.

### UI availability contract

SystemModuleAvailability publishes a StateFlow separating the verified current
process runtime (bundled or external, uninitialized, missing, failed) from
the selected package version and pending restart. A downloaded package alone
does not enable its dependent UI. ProjectM and MPV publish actual engine probe
results; Slang asset preparation reports whether the selected preset was
available. Settings probes MPV and ProjectM on a worker when their relevant
pages are opened. Renderer and visualizer choices and dependent profile/preset
controls are filtered. Existing stored preferences are not rewritten when
these controls disappear. The module management page itself remains reachable,
including recovery actions for corrupt packages.

Monogram and Spotify eligibility has been centralized across the provider
catalog, settings, search, sidebar, handheld navigation and other affected
paths; their predicates still reflect bundled build flags until their external
runtime contracts are migrated. Spotify's requested enabled flag is saved
and backed up independently of runtime eligibility. Other legacy modules still
need their actual runtime migration and UI audit (including Android Auto,
background tasks, direct routes, and all player OSD panels). Native/device
and CI validation remain required.

### Spotify receiver and expanded preview

Spotify now has a native-only module contract, an external-first JNI load path,
and package generation from matching Spotify-enabled Full APKs. At process
start, the bundled opt-in or downloaded native receiver is probed on IO
without constructing a repository or starting a session. Its UI eligibility
requires a successful native load. TV/phone settings observe availability;
sidebar and Home observers receive the provider availability signal. Android
Auto requires the actual receiver as well as an authorized account. Stored
preferences and credentials are not deleted. Spotify remains experimental:
the upstream service's audio-key rejection is not fixed by moving the library,
and normal Full builds retain the existing SPOTIFY_NATIVE opt-in policy.

The modular preview also omits MPV and Spotify native roots. Shared FFmpeg
and C++ libraries remain packaged. The build audits all four ABI APKs for
excluded payloads and required shared libraries. No complete stable Modular
release is implied. Native package candidates undergo host contract, ELF/DEX,
and shared fingerprint preflight before installation or rollback commits a
selection. A damaged selected archive can be reinstalled at the same signed
version while retaining the prior rollback pointer. Tests cover these cases.

Native package release publication, remaining engine migration (including
Monogram and SmartTube's Java/native boundaries), and real-device validation
remain outstanding.

### Monogram native-first migration

The native Rust payload is now optional in the modular preview. A
Monogram-enabled APK still includes the existing Kotlin adapter, generated
UniFFI bindings, JNA and TLS verifier Java code; builds with
MONOGRAM_NATIVE=0 do not offer this module yet. The signed
hostBindingSha256 field pins the exact classes.jar from the matching patched
Monogram AAR. The runtime verifies it before installation, rollback and
activation. The native package is built directly from that AAR for all four
ABIs, with required license notices and no unapproved native dependencies.

The repository chooses the external absolute library before constructing the
backend. The pinned upstream UniFFI generator already honors the namespace
libraryOverride property; its JNI TLS verifier is patched to load that same
absolute file. The build checks that regenerated bindings still honor it.
Neither loader may mix an external and bundled Rust copy. A successful backend
probe on IO unlocks Monogram settings/navigation; account restoration remains
conditional on the existing user preference. Session/cache paths and
credentials are unchanged.

This is a native-first intermediate step, not a fully independent Monogram
DEX adapter. Native release signing/publication and corresponding GPL source
distribution, ABI/device validation, matching-artifact release selection,
and removal of the Java/JNA payload from the base APK remain outstanding.
