# Embedded MPV backend

The app packages native playback runtimes for these Android ABIs:

- `arm64-v8a` / `armeabi-v7a` — Android TV and most physical devices
- `x86` / `x86_64` — Android emulators and rare x86 Android TV images

ABI selection is handled by Gradle `splits.abi` (see `app/build.gradle.kts` / `APK_ABI`). Do not also set `ndk.abiFilters`: AGP 8.11+ rejects using both mechanisms together.

## Automatic ABI selection

Do not implement a manual ABI switch in the player UI.
Bundle the same native library names in each ABI folder:

```
app/src/main/jniLibs/arm64-v8a/libmpv.so
app/src/main/jniLibs/arm64-v8a/libambientmpv.so
...
app/src/main/jniLibs/armeabi-v7a/...
app/src/main/jniLibs/x86/...
app/src/main/jniLibs/x86_64/...
```

At installation/runtime Android selects the native directory matching the process ABI.
`System.loadLibrary("mpv")` and `System.loadLibrary("ambientmpv")` therefore load the correct
build automatically. `NativeArmRuntime.selectedAbi` is available for diagnostics and
prevents MPV from being exposed when no packaged ABI matches the device.

## Native payload still required

The backend/UI selection layer already knows `auto`, `media3`, `vlc`, and `mpv`, but MPV
remains disabled until real native binaries are supplied for the applicable ABI.
The official mpv-android project is an application rather than an importable AAR, so the
native libmpv runtime, its dependencies, and the app-owned JNI shim must be built/bundled.

The Compose/UI layer stays independent from libmpv and talks only to the playback backend
controller so Plex and TVHeadend keep the same OSD/navigation across engines.

## Build script

Run from the project root on Linux or macOS:

```sh
./scripts/build_mpv_android.sh
```

Use `--clean` for clean native prefixes. The script clones the official `mpv-android`
source/build scripts into `.native/mpv-android`, builds upstream architectures
(`arm64`, `armv7l`, `x86`, `x86_64`), copies libmpv plus its generated shared dependencies
into the app's `jniLibs` directories, and compiles the app-owned `libambientmpv.so`
JNI shim against each matching libmpv build.

Or via the main entrypoint:

```sh
./build.sh mpv
./build.sh x86_64    # package an x86_64-only release APK (after natives are ready)
```

The default source ref is pinned in `gradle/external-versions.properties`. Override it when
deliberately updating native playback:

```sh
MPV_ANDROID_REF=<git-tag-or-commit> ./scripts/build_mpv_android.sh --clean
```

The app's `minSdk` is 29, so the JNI shim is compiled for API 29 by default. Override with
`ANDROID_API` only if the application minimum changes.

Important: successful native compilation makes `MpvBackendAvailability.nativeRuntimeAvailable`
true, but MPV remains hidden as a selectable renderer until the Kotlin/JNI player controller
and Android render surface are fully implemented. This prevents a misleading "MPV" setting
that would silently play through Media3.

## Spotify note

Rusty upstream prebuilts are ARM-only. `scripts/build_spotify_engine.sh` builds `x86` and
`x86_64` from source via cargo-ndk; the prebuilt fetch path cannot supply emulator ABIs.
