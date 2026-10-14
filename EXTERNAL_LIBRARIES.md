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
- Spotify native core (`SerafiniJose/rusty`) — newest stable GitHub release/tag
- Open-SVPFlow — newest published versioned release, or an explicitly named nightly when no stable version exists; always resolved to an exact commit

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
