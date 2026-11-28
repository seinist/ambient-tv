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

### Independent Monogram DEX candidate

The native-only Monogram format remains supported by a matching Full APK,
but the new independent format contains the pre-R8 Monogram adapter,
upstream UniFFI/Kotlin bindings, JNA and rustls verifier code, plus the
ABI-specific Rust and JNA dispatch libraries. The modular preview no
longer includes those bindings or native libraries. Neutral host artwork
and the account/media UI remain part of the APK.

The factory implements the host-owned MonogramModuleFactory interface.
Resource IDs and diagnostic flags are resolved by MonogramHostBindings
from the actual installed host. Host-owned DTOs/ports, Kotlin/coroutines
and credential storage retain their identity through R8. The first
version deliberately keeps a broad host ABI surface; optimize these keep
rules after device validation. The signed binding fingerprint covers
the Telegram host sources, TokenStore, runtime loader and dependency
version catalog. The child loader resolves only explicitly owned class
names from the module and never falls back to the Full APK for those names.
DEX preflight checks actual defined classes, mandatory implementations
and duplicate definitions before installation. JNA dispatch and UniFFI
resolve the extracted absolute native paths. The selected engine is
created by the factory even in builds without bundled Monogram.

Candidate export (after building the pinned Monogram AAR and preparing
the usual Android dependencies):

    MONOGRAM_NATIVE=1 ./gradlew :app:exportMonogramAdapterDex -PAPK_ABI=arm64

Output: app/build/outputs/system-modules/monogram-dex. The Gradle task
takes the release pre-R8 class graph, separates program classes from
host-only classpath classes, and runs D8 with min API 29. The package
script accepts --module monogram --monogram-dex-dir <output> and the
matching four Full ABI APKs via repeated --apk, with license notices
via --license. Native-only --monogram-aar remains a separate format.
The preview APK audit rejects actual definitions of Monogram/JNA/rustls
implementation classes as well as native payloads.

The malformed duplicated section in the existing Monogram workflow
was corrected and the workflow now exports the DEX candidate on its
generic ARM64 job. Package/exporter regression tests are included in
module CI. Actual Android linkage, TLS verification, login, session
restore, playback and APK size must still be tested; independent native
release publication and corresponding GPL source distribution are not
yet enabled. Candidate generation is not a signed public release.

### SmartTube external adapter candidate

SmartTubeEngine and YouTubeLoungeBridge now resolve their upstream types
through a single process-selected loader. The external adapter owns
com.liskovsoft and com.eclipsesource.v8 implementation classes, including
the child-owned Android J2V8 native load. Generated Android R classes,
the common dependency graph and the existing app reflection bridge remain
host-owned. The signed binding fingerprint covers the SmartTube host
sources, pinned upstream versions, wrapper configuration and patches.

The Full APK retains the bundled engine. The modular preview now uses
an AGP scoped CLASSES transform to remove SmartTube-owned executable
classes before D8/R8 and excludes libj2v8.so at native packaging time.
Generated R classes, Android resources/assets, manifest and common
dependencies remain in the host. Resource shrinking is disabled for
preview builds so references from the external DEX remain valid.
The signed adapter is offered only when the pinned resource graph is
present. No account data is migrated or removed.
The startup probe and state flow gate system catalog, settings, Home,
sidebar and first-run sign-in without changing stored enabled preferences.

Candidate export:

    ./gradlew :app:exportSmartTubeAdapterDex -PAPK_ABI=arm64

Output: app/build/outputs/system-modules/smarttube-dex.
Use package_system_modules.py --module smarttube --smarttube-dex-dir
with matching Full APKs for all four ABIs and corresponding license notices.
The SmartTube integration workflow now exports the DEX candidate.
Format tests cover ownership, resource classes and J2V8 packaging.
Publication, native 16 KiB validation of the pinned J2V8 binaries,
Android playback/TV-link tests and actual size measurements are still
outstanding. The integration workflow exercises the resource-only class
transform, and the preview APK auditor checks for actual definitions of
upstream SmartTube and J2V8 classes and the native payload. These checks
do not by themselves establish runtime linkage or playback compatibility.
The broad R8 keep rules preserve common host APIs pending device validation
and future size optimization.

### VLC native-first migration

The custom pinned VLC 4 AAR now patches its Java JNI load sites to use the
same verified absolute native library paths selected by SystemModuleHost.
A marker in classes.jar gates native modularization; older/unpatched AARs
and the Maven fallback remain bundled. The signed VLC package binds to
the exact patched classes.jar digest and hashes of any approved shared
APK-owned native dependencies. The normal Full build keeps VLC available
without downloading anything. The modular preview omits libvlc.so and
libvlcjni.so when the patched AAR is present, and its release APK audit
requires those roots to be absent in the normal custom VLC build.

VlcBackendAvailability probes an actual LibVLC instance through the
selected native runtime before playback. The Vulkan capability probe and
controller constructor use the same gate. A missing or failed VLC
renderer is filtered from settings and player controls; saved preferences
are retained while Media3 is used instead. Live TV and local audio
recovery also honor VLC availability.

Native candidate generation uses package_system_modules.py --module vlc
--vlc-aar <matching-patched-aar> and the four matching Full ABI APKs
(--apk), with license notices (--license). The Java LibVLC wrapper and
Android resources still remain in the host, because current controllers
use its types directly. Full Java/DEX separation would require a wider
playback interface refactor. The custom JNI source patch, native package
binding and preview exclusion have regression tests. Actual pinned
upstream rebuild, Vulkan/HDR/SVP/PCM compatibility, Android linker
validation and signed publication remain to be verified.

### SVP, Vulkan Slang and LibretroDroid native candidates

SVP uses a native-only module pinned to the host C header. The Kotlin gate
selects and checks the actual JNI ABI before enabling interpolation.
Both the MPV and VLC filters dynamically resolve the already selected
SONAME; the Rust build requires the exact SONAME, and the module loader
rejects mismatches. Missing SVP hides the controls without deleting
stored presets or changing the user's global preference.

The combined Shader package contains the ABI-specific Vulkan-only
librashader C API plus the Slang preset assets. The host checks the
header fingerprint and native SONAME, then uses the immutable verified
preset tree directly. Existing GLES filters remain available in the
base APK. Legacy asset-only Shader packages are compatible only with
hosts that still bundle the Vulkan runtime. The shader-only publishing
job is not a complete native Shader publication pipeline.

LibretroDroid uses the same native-first approach as VLC. The exact
pinned upstream static initializer is patched after applying the
existing source patches. The native package is bound to the prepared
source stamp and the patched JNI Java binding. The worker probes the
read-only Vulkan frame status before showing the Games sidebar or
preparing a ROM. The Java/Kotlin frontend, Android resources, and
any driver hooks/shared dependencies still required by the host
remain in the APK. Cores and save files remain independent.

The preview APK excludes libambientsvp.so, liblibrashader_capi.so and
liblibretrodroid.so; the audit rejects leakage. Candidate generation
uses package_system_modules.py --module svp|shaders|libretrodroid with
matching Full APKs for all four ABIs and corresponding notices.
For shaders, passing no APKs generates the legacy asset-only candidate.
For LibretroDroid, prepare the matching pinned sources first.

The remaining Moonlight integration is not present in the inspected
PR source graph; reconcile the branch with the Moonlight implementation
before attempting to omit its runtime. New native candidates are not
published or validated on Android by these source changes. The shared
release pipeline, full CI, device linkage and actual APK size measurements
remain outstanding.

### Moonlight migration staged independently of PR #494

Moonlight's ongoing native performance work stays in
feature/moonlight-native-integration (PR #494); this PR does not merge,
rebase, or modify that branch. The verified pinned upstream entrypoint is
MoonBridge.java, with a single System.loadLibrary("moonlight-core") call.
The standalone patch_moonlight_module_loader.py script changes only that
call in the staged protocol Java sources and preserves the subsequent
JNI initialization. It is idempotent and rejects unexpected upstream drift.

Once the final Moonlight implementation is integrated into the module
branch, run the patch after its normal source staging, before compilation:

    python3 scripts/patch_moonlight_module_loader.py third_party/moonlight-android
    python3 scripts/patch_moonlight_module_loader.py third_party/moonlight-android --fingerprint

Use the identical fingerprint in the host's native-only Moonlight contract
and signed module manifest. It includes the source/patch provenance marker
and every staged Java source, so ongoing native optimization revisions
cannot accidentally be mixed with a previous host build. This conservative
policy requires regenerating the matching module when those inputs change.
The native package command supports --module moonlight with the four
matching Full ABI APKs and required license notices; libmoonlight-core.so
is the only root, with platform dependencies only until additional native
dependencies have been explicitly reviewed.

Final wiring must set ambient.moonlight.native.directory before any
MoonBridge class initialization, preserve host-side Java/JNI bindings,
probe on IO, gate MoonlightFeature and direct playback routes, and only then
exclude the native root from Modular APKs. Full must preserve its normal
opt-in behavior and native fallback when no selected package exists.
No packet, decoder, audio, input, network or optimization source is copied
from PR #494 here. Tests cover patch drift, idempotency, provenance changes
and the native package layout. This is preparation, not activated Moonlight
modularization, and it must be validated against the final Moonlight branch.

### Compatible release discovery

Release discovery walks the bounded GitHub release history and authenticates
all discovered module catalogs before downloading the highest compatible signed
version. GitHub presentation order is not version order. Conflicting signed
entries at the same ABI/profile/version fail closed. A signed incompatible
catalog is skipped without downloading its ZIP or changing the installed
selection. Invalid metadata, network errors and archive/preflight failures stop
the search; they never trigger an automatic downgrade. Only the winning ZIP
is downloaded. The persisted version floor is rechecked under the install lock.

### Cancellable module downloads

Settings shows release discovery, signed-byte transfer percentage, verification
and atomic publication separately. Cancellation closes the active HTTP call and
is checked again before the selection pointer is committed. A cancelled staging
operation cannot advance the version floor or replace the previous selection.
Once atomic publication starts, cancellation is disabled; the transaction must
finish. Verification may need to finish its current bounded I/O before aborting.
Closing the settings composition also requests cancellation. Device UI and CI
validation remain required.

### Native candidate and publication pipeline

The Build release workflow accepts a single optional module_candidate on main.
It builds signed Full APKs, verifies their APK manifest and common identity,
exports the matching adapter DEX where necessary, and generates a four-ABI
unsigned module candidate with APK hashes and exact commit/run provenance.
The candidate checks archive hashes, payload roots and 16 KiB ELF load alignment.
It does not publish an application release draft. Moonlight is intentionally
excluded until its final host integration is present.

The source collector packages the exact tracked Ambient TV source, build recipes,
patched upstream source trees and license notices, including resolved SVP and
Moonlight notices where present. Reviewed additional notices can be placed under
third_party_licenses/system_modules/<module>. The source candidate is a separate
release asset; its SHA-256 descriptor is embedded inside every signed native ZIP.
Resolved Cargo dependencies are vendored with the exact lockfile for SVP,
Monogram, Spotify and librashader. Moonlight corresponding source additionally
fetches the reviewed OpenSSL and mobile build-script commits from its pinned
source notice. ProjectM includes the prepared preset and texture sources.
Missing sources/notices and inconsistent provenance fail closed. The review must
still establish completeness for transitive dependencies, generated inputs,
prebuilt archives and required corresponding-source terms before publication.
After verifying the exact artifact on devices and arranging required license and
source distribution, dispatch System module packages on the same main commit
with publish=true, reviewed=true, native_module and candidate_run_id. The signing
job checks the successful same-repository build run, exact main SHA, four ABI
assets and provenance before accessing the pinned signing key. It publishes
only a non-latest release, never replacing an application update release.
Native catalog versions use the originating numeric run ID. The legacy shader
asset-only publishing mode is retained separately. No native release has been
published or device-tested by this change; remaining notice/source preparation
and incremental builds are separate tasks.

### Incremental release and native build reuse

Publication compares the candidate against the most recent authenticated
native catalog of the same module. Only the version number and release tag
are ignored in the payload comparison: ABI, ZIP digest/size, profile, host
API, SDK, dependency and host fingerprint metadata remain significant.
Unchanged payloads retain the existing release; force_publish can request a
newer version but cannot bypass signatures or downgrade/conflict checks.
An invalid historical catalog is an error, not permission to publish.
The legacy shader asset-only catalog is not treated as a native candidate.

Main-only Full builds restore independent MPV/FFmpeg, VLC, ProjectM, SVP,
librashader, Monogram, Spotify, LibretroDroid and SmartTube caches. Keys use
tracked input blobs, engine-specific upstream pins, shared build recipes,
and observed compiler/toolchain identity, computed before restoring anything.
Shared SVP changes invalidate MPV and VLC too. MPV retains the FFmpeg
headers needed by the local audio compiler. Spotify is only cached when
enabled; its C++ runtime remains checked by the existing build preflight.
LibretroDroid caches prepared sources and native incremental build state;
SmartTube caches its pinned patched checkouts and uses Gradle's build cache.
No fallback keys or PR cache writes are used. Existing engine validation
remains authoritative. The candidate packaging still uses signed Full APKs.
Cache reuse is subject to GitHub eviction and actual CI/device validation.

### Variant-aware APK updates

The updater persists an explicitly selected Full/Modular channel. The common
application release may contain update.json (legacy schema 1 Full) and
update-modular.json (schema 2 with packaging=modular). Cached offers are
invalidated when the channel changes. Same-version replacement is allowed
only across explicitly selected variants; lower version codes are forbidden.
The incoming APK must have the same application ID/signing lineage and match
the selected variant's signed Android manifest marker (legacy unmarked Full
APKs remain supported). A profile-install retry is tied to the original APK
hash. Preview builds cannot use or generate stable update manifests.

Stable Modular APK publication is an explicit reviewed main-only workflow
option (paired_release=true), not the default. Do not select it before
remaining engine integration, module availability and device validation are complete. The Modular channel can only resolve a published modular
manifest; absence produces the existing no-manifest error. The paired release verifies version code, signing identity, app ID, SDK,
all four ABIs and exact shared native bytes across both variants. Both update
manifests and all eight APKs are uploaded to one reviewable release draft. No account data is intentionally reset by package replacement.

### Additional Java ownership and runtime audit

SmartTube's private JSONPath/json-smart, nanojson, regex and disk cache
implementations are exported with the adapter rather than left in the
Modular base. The ownership table, required classes, export, strip and
APK audit share the same explicit allowlist. The build refuses a retained
host class that references the private packages. RxJava, Retrofit, DNSJava
and shared Android libraries remain host-owned pending an API/SPI boundary
review. VLC and LibretroDroid Java bindings still need a separate typed
host-interface refactor; their native-only isolation is unchanged.

The runtime rejects undeclared cross-module dependencies and extra native roots.
An authenticated selection whose host fingerprint no longer matches may fall back
to the bundled Full engine before executing any module code; Modular requires a
matching update. The settings dialog preserves and explains the incompatible
selection. Invalid signatures, corrupted archives and failed activation still
fail closed. The runtime memoizes verified host-library
fingerprints for the installed APK process and explicitly loads each
shared SONAME at most once. The persisted version floor also records the
signed identity per ABI/profile, preventing a conflicting same-version
replacement even after removal. FFmpeg dependencies needed for local audio
remain in the base until a separate local decoder migration is validated.

### Moonlight final integration seam

The host contract, exact binding fingerprint, native exclusion, settings
registration, first-load probe and native release candidate are prepared in
this branch. The current Moonlight sources still live in PR #494. Once that
PR is integrated into the module branch, run
python3 scripts/reconcile_moonlight_module_host.py and review its changes.
It updates only the known source staging, startup/preloader, availability,
settings, pairing and direct playback gates; it never copies or changes
native optimization code. All anchors must match before any file is edited.
The check mode is enforced for the Moonlight candidate and stable paired
release; those builds fail closed until the final integration is applied.
An actual Android load, pairing, decoder/audio and playback test remains
necessary. The native source in #494 has not been merged or modified here.

### Remaining integration and validation gates

The final combined audit cannot be completed before the pending Moonlight,
renderer and SVP branches land. The new source collection is executable
in CI, but J2V8/V8, Maven artifacts and VLC contrib source completeness
must be independently checked against the exact built binary inputs;
the review flag is not an automated legal certification. Full Java/DEX
separation of VLC and LibretroDroid still requires replacement of the
direct Java playback and surface/frontend types in their host consumers.
The common local-audio FFmpeg graph must remain in the base until a
compatible alternative is tested. No release should be marked reviewed
solely because the source-collection or APK-format tests pass.

### VLC host ABI extraction

The production controller, startup and Vulkan capability probe now depend on
host-owned VlcEnginePort, VlcLibraryPort, VlcPlayerPort, VlcMediaPort,
VlcOutputPort and VlcTracksPort. Events, chapters, equalizer settings, tracks
and media statistics use neutral DTOs. The concrete bundled implementation
retains the same LibVLC constructors, callbacks, track-ID handling and release
ordering. JNI namespace and dependency closure still need verification before
the concrete implementation and vendor classes may be removed from Modular.

### Libretro core-control boundary

The primitive JNI control/status calls in GamePlayerScreen, the Vulkan
surface owner, netplay, achievements and startup probe now use the
host-owned RetroCorePort. Its bundled implementation retains the same
static native methods, original thread ownership and reusable input arrays.
View-specific netplay stepping, achievement definitions, GLRetroView,
shader configurations and virtual files still need their own host contracts
before the entire LibretroDroid Java implementation can be externalized.
Tests inspect the host port signatures for leaked vendor types.

### Host-owned game shader model

Shader choices, authenticated shader packs, GLES upscaling passes,
HDR conversion and picture effects now use a neutral RetroShaderConfig.
The bundled frontend maps the six used built-in presets to their existing
defaults and copies all custom pass fields at the view boundary. Shader
source text, pass order, scaling, sampling, framebuffer precision and
intensity are unchanged. Existing shader policy tests use the host model;
a separate conversion test verifies the vendor mapping.

### Experimental VLC Java/DEX candidate

Set VLC_DEX_MODULE=1 when building release-modular-preview to strip the
VLC implementation and vendor Java classes (retaining Android resource
classes). The Full candidate uses exportVlcAdapterDex and packages the
exact signed APK native roots plus a private C++ runtime. A DEX-specific
host fingerprint, entrypoint allowlist and retained-class boundary scan
prevent mixing the native-only and DEX formats. The Vulkan module-bank
probe runs inside VLC JNI, not the host linker namespace.

The Build release workflow accepts vlc_dex_candidate with module_candidate=vlc.
This is opt-in; the stable Modular release deliberately rejects the flag
until native cross-namespace SVP and ProjectM PCM integration is validated.
The isolated adapter now registers a versioned borrowed SVP function table
and ProjectM PCM callback from the already loaded host providers. The
host retains their linker handles for process lifetime. The VLC filter
never creates a separate SVP engine, and the audio callback uses the
same host PCM ring. The experimental AAR build checks core and JNI dynamic
exports; a missing registration fails the isolated engine closed. Actual
Android linker/renderer validation is still required before stable promotion.
The default native-only VLC path stays unchanged. Device validation must
cover JNI load, seek, audio, subtitles, Vulkan and release before promotion.

ROM content preparation now uses host-owned GameVirtualFile descriptors;
only the bundled frontend adapter constructs vendor VirtualFile instances.
The original PFD, read-only proxy and native detach ownership are retained.
Achievement definitions similarly cross the neutral RetroCorePort rather
than leaking vendor data classes. The remaining GLRetroView/lifecycle and
netplay view-specific operations are still directly coupled.

### Experimental complete Libretro frontend DEX

LIBRETRO_DEX_MODULE=1 selects the isolated frontend contract. The host
owns the actual view/lifecycle API, input and render events, rewind,
netplay, state operations, controller descriptions, ROM descriptors and
shader policy. The signed adapter owns GLRetroView, native JNI and its
implementation. It returns the original Android SurfaceView and observer,
so no extra rendering view, GL queue or lifecycle is introduced.
The exporter and class-stripping step reject remaining vendor linkages.
The package includes the frontend's private ELF dependency closure and
retains only the needed host resources. The Vulkan Slang runtime is
borrowed from the already loaded host through a process-lifetime symbol
resolver rather than loaded a second time. Individual signed game cores
remain separate downloads.

The Build release workflow accepts libretro_dex_candidate with
module_candidate=libretrodroid. Stable packaging remains gated until
Android builds and device tests establish correct JNI/renderer and core
loading behaviour, including netplay and Vulkan shaders.

### Publication ordering and archive parity

Publication compares the highest authenticated catalog version across the
bounded release history, not GitHub's presentation order. Conflicting
signed entries at the same version stop publication. Legacy shader assets
use the same run-ID version sequence as native candidates and cannot
replace an already published native shader catalog. Both publication
paths require explicit review and check main again before exposing a draft.
Package generation and candidate verification also reject invalid roots,
file/directory collisions, and unsupported ZIP entry types.

### APK variant discovery and transport

If the latest stable APK release lacks the requested variant, the updater
searches at most ten pages of ten published releases for the first
matching variant. A malformed matching manifest or asset stops discovery;
it cannot silently select another release. Both manifest and APK URLs are
bound to the exact release tag and filename, redirects are limited to
known HTTPS GitHub asset hosts, and the installer still checks the signed
APK identity and rejects downgrades. No automatic switch to Full occurs.

After an APK variant or host API change, the selection reader authenticates
the stored manifest and archive even if the previous ABI, SDK or host API
no longer matches. Full may use its bundled engine for this verified
incompatibility; Modular reports the unavailable engine and retains the
selection for recovery. Corruption still fails closed, and runtime loading
and rollback retain strict compatibility checks.

Runtime activation pins the exact authenticated root package selected by the
host compatibility check. Another process can publish or remove a selection,
but cannot redirect that activation to a different archive; the process lease
prevents reclamation. Dependencies still require exact signed versions and
host contract validation. Observing a changed installed version while an engine
is usable marks a restart as required without changing the running engine.
ProjectM does not attempt a bundled native load when the Modular APK has no
selected package.

The paired APK release verifier now traverses the actual Full ELF dependency
graphs for native-only modules and checks the byte identity of their shared
host dependencies. It also examines every retained Modular native library
for DT_NEEDED references to libraries removed from Full. A retained local
decoder or other base library cannot rely on a separately downloaded engine
to satisfy a direct linker dependency. Explicit optional JNI/dlsym bridges
still require device validation.

Runtime preflight additionally inspects the actual APK-owned ELF dependencies
behind every signed host fingerprint. The reachable host graph must match the
signed dependency set exactly; missing transitive libraries, unused entries,
SONAME mismatches and host-to-module back edges fail before activation.
Parsed ELF metadata is cached per installed APK process. Compressed APK entry
inspection uses a bounded temporary file, removed after inspection; it is
never loaded as native code. Shared libraries are explicitly loaded once in
dependency-first order (Android resolves host-only linker cycles).

Corresponding-source candidates are independently streamed and validated
before release signing: the compressed digest, regular-member paths, unique
members, declared root inventory, notice counts and bounded expansion must
agree. This validates package integrity and declared coverage, not legal
completeness or reproducibility of every third-party build.

SmartTube native candidates export source JARs for the actual Gradle-resolved
private Java dependencies (json-path, json-smart and resolved net.minidev
components, nanojson, regex, DiskLruCache). Source files and embedded binary
license notices are collected with exact Maven coordinates and SHA-256
fingerprints for both artifacts. Missing source artifacts block candidate
generation. This does not yet establish native J2V8/V8 source correspondence
or complete licensing of host-owned Java dependencies.

SmartTube records native J2V8 binary provenance for all four ABIs: exact
SharedModules commit and Git blob, input and stripped Full APK SHA-256,
and matching executable .text section SHA-256. Module ZIP payloads are
checked against that evidence. The declared J2V8 version is not proof of
native J2V8/V8 source correspondence; that remains explicitly unverified.
Resolved ASM artifacts are included in the Java source inventory without
changing host class ownership.

VLC source candidates now inventory the downloaded contrib tarballs from
the actual build tree, recording hashes and available upstream SHA-512 or
Git archive marker evidence. FFmpeg must match the pinned commit marker;
libplacebo must match its recipe checksum. The final source archive is
stream-checked against the tarball inventory. Unmatched recipe evidence is
explicitly marked for review, not claimed to be verified source correspondence.

Moonlight's final integration seam now also guards application catalogs,
host verification/proofs, artwork and remote quit operations against an
unavailable module. Local trust cleanup remains usable without the native
engine. Native builds require the entire reconciliation script to pass in
check-only mode, not merely a matching feature flag. The reviewed source
anchors are compatible with PR #494 head d1e345c3423c8086adb967b868c01208b3888d51;
the upstream branch is not merged or modified by this preparation.

Signed catalog validation now requires a nonempty catalog and one version
per module across ABI/profile alternatives. The incremental publisher checks
the same bounds, fields, fingerprints and dependency identities before
comparing historical releases. Explicit schema defaults and omitted optional
fields are treated identically; duplicate catalog assets are rejected.

All native, shader and APK release drafts are checked after upload against
the exact local asset names, lengths and SHA-256 digests returned by GitHub.
The draft state and explicit commit target must also agree. Missing remote
digest evidence fails closed; native/shader drafts remain unpublished on
failure, and APK drafts remain subject to manual review. Paired APK releases
also revalidate the signed APK pair and current main immediately before
draft creation. This does not substitute for device or license review.

Merge order: PR #532 is intentionally the final integration PR, after the
other engine and optimization work has landed and its combined CI and
runtime compatibility have been checked. Do not merge it early.
