# External libraries and update workflow

Ambient TV keeps external media engines pinned so normal builds are reproducible. The versions displayed under **Settings → About** are generated from the same build inputs.

## Check for updates

The UI uses [AndroidSVG](https://bigbadaboom.github.io/androidsvg/) 1.4 to render
local branding SVGs as vector drawing commands. Its Maven version is pinned in
`gradle/libs.versions.toml`; it is independent of the native media-engine updater.
Only SVG `currentColor` accents are replaced at runtime; branding PNGs are exports.

```bash
./build.sh libs-check
```

This performs a network check for the managed external components without modifying the project:

- libVLC Android — newest stable Maven release (pre-release/EAP builds are ignored)
- SmartTube — newest stable GitHub release plus the exact MediaServiceCore and SharedModules submodule commits from that release
- mpv-android — newest stable GitHub release/commit
- projectM — newest stable GitHub release
- Spotify native core (`SerafiniJose/rusty`, developer opt-in only via `SPOTIFY_NATIVE=1`; disabled in regular builds because Spotify's audio keys no longer work with librespot) — newest stable GitHub release/tag
- Open-SVPFlow — newest published versioned release, or an explicitly named nightly when no stable version exists; always resolved to an exact commit
- Monogram (GPL-3.0-only; included in regular release builds, optional in developer builds) — newest published GitHub release, including pre-releases; the tag commit, the `vendor/mono-rs` submodule commit, and the `rustls-platform-verifier-android` version from that release. Drafts are ignored. Moving the pin does not build the native AAR.

## Apply updates

```bash
./build.sh libs-update
```

This updates `gradle/external-versions.properties` and the libVLC entry in `gradle/libs.versions.toml`. It does **not** silently update anything during an ordinary build.

The next normal build compares the pinned native revision with the marker next to the packaged native libraries and rebuilds mpv, projectM or Spotify when the pin changed. SmartTube is re-fetched/re-patched when its compatible release/submodule pair changed.

`libs-update` does not move the pins of what those engines pull in themselves; review them by hand when mpv-android or projectM moves:

- mpv-android's `download-deps.sh` clones mpv, libplacebo, dav1d and libass from their default branch. `scripts/pin_mpv_deps.sh` moves them (and the freetype2 tag) to `MPV_DEP_*_COMMIT` and fetches the source tarballs of its `depinfo.sh` only with a matching `MPV_DEP_SHA256_<file>`. A new mpv-android release with other tarball versions stops the build until their sums are added.
- The projectM preset and texture packs are checked out at `PROJECTM_PRESETS_*_COMMIT` and `PROJECTM_TEXTURES_COMMIT`.

SVP participates in both commands. To update only SVP (without changing other libraries):

```bash
./build.sh libs-check svp
./build.sh libs-update svp
./build.sh svp                 # build/test the pinned native engine, no APK
```

Monogram participates in both commands as well. To update only that pin:

```bash
./build.sh libs-check monogram
./build.sh libs-update monogram
./build.sh monogram            # build the pinned native AAR, no APK
```

`monogram-check` and `monogram-update` are aliases for the Monogram-only commands. After the pin moves, confirm `scripts/patch_monogram_leave.py` still applies and review the UniFFI ABI, auth, streaming and GPL license before distributing. An ordinary APK build does not fetch a newer Monogram release.

`lib-check` / `lib-update` are also accepted aliases. SVP updates require Python 3.11+ and stable Rust via rustup. Updating resolves Cargo dependencies but does not compile a native runtime. It stages all three Rust dependency pins, `Cargo.lock`, the matching Vulkan shader, upstream provenance, and notices together. Download/resolution failures leave the current sources and pins intact; upstream license changes require manual review. Ordinary builds never fetch a newer release automatically.

The next build checks pin/shader/lockfile consistency, runs portable SVP regression tests and rebuilds changed native inputs. Per-ABI `.svp-engine` fingerprints include the engine sources, build scripts, Rust/NDK toolchain and flags; unchanged runtimes are reused. `REBUILD_SVP=1 ./build.sh svp` reruns preparation; `SVP_ABIS=arm64-v8a ./build.sh svp` restricts a standalone build. `SKIP_SVP_NATIVE=1` deliberately bypasses preparation and requires existing packaged libraries. Updating does not alter the Maximum-performance-only runtime gate.

Always review the diff and run playback/login regression tests after an update. Upstream API changes can require a compatibility patch even when the upstream release itself is stable.

## Manual overrides

The existing environment variables still take precedence for one-off testing, including `MPV_ANDROID_REF`, `PROJECTM_REF`, `RUSTY_REF`, `SMARTTUBE_MEDIA_SERVICE_REF` and `SMARTTUBE_SHARED_MODULES_REF`. Those overrides do not rewrite the project pins unless `libs-update` is used.

## Android ABIs

App-owned native engines (mpv, projectM, Spotify/Rusty, Open-SVPFlow) are built for:

- `arm64-v8a`, `armeabi-v7a` — Android TV / physical devices
- `x86`, `x86_64` — emulators (and rare x86 Android TV images)

`./build.sh release` packages ABI split APKs plus a universal APK. Use `./build.sh x86_64` (or `x86`) for a single emulator APK. Spotify x86 binaries require a cargo-ndk source build; Rusty upstream prebuilts are ARM-only.

## Games (libretro)

- **LibretroDroid** — the Argosy launcher's fork (GPL-3.0), pinned in `gradle/external-versions.properties` (`LIBRETRODROID_*`), fetched and patched by `scripts/fetch_libretrodroid.sh` (`scripts/patches/libretrodroid`: core options v2, netplay sticks). Moving the pin: change the commit, run the script, check that the patches still apply.
- **librashader Vulkan Slang runtime** — [SnowflakePowered/librashader](https://github.com/SnowflakePowered/librashader) 0.12.1, pinned to `LIBRASHADER_COMMIT` in `gradle/external-versions.properties`. The C header is vendored as build-only source under `scripts/native_libretro_vulkan/`, while `scripts/build_librashader_android.sh` builds a **Vulkan-only** `liblibrashader_capi.so` for each Android ABI and checks 16-KiB ELF alignment. Upstream licenses: MPL-2.0 OR GPL-3.0-only (check the exact distribution and static dependency license obligations before release); the host C++ glue is GPL-3.0-or-later. Rust static dependencies and notices require a third-party attribution audit in production packaging. The runtime is not downloaded as executable code by the app. Regular native shader APKs include the ABI-specific `.so` (ARM64 stripped CI size ~6.7 MB before APK compression); `SKIP_LIBRASHADER_NATIVE=1` makes a GLES-only APK and explicitly excludes leftover `.so` files from packaging. This is **experimental SDR output only**, not tested HDR passthrough.
- **libretro cores** — never bundled. Pinned in `cores/cores.json`, built by `.github/workflows/cores.yml` into a signed `cores-*` release (or loaded from the libretro buildbot, see the core source setting). Update a core by changing its commit.
- **Apache Commons Compress** (Apache-2.0) with **XZ for Java** (0BSD) for ZIP/7z, **junrar** (UnRAR licence, extraction only) for RAR4 — versions in `gradle/libs.versions.toml`.
