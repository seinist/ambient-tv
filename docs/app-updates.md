# GitHub app updates

Settings → About → App updates provides manual checking, download, cancellation and installation.
“Check for updates on app start” is opt-in and checks at most once per 24 hours. A compatible
release produces one in-app notification per version on Home; playback is not interrupted.
Home also retains an actionable update entry. Skipping a version hides its entry and automatic
notice, while manual checking still permits installation. Automatic failures remain silent.

The source is `https://api.github.com/repos/seinist/ambient-tv/releases/latest`. Only regular
releases participate; GitHub prereleases and drafts do not. Public access requires no token.
A missing manifest, incompatible device, network error and rate limit have distinct messages.
Discovery survives process restarts. Downloads continue while navigating within the process;
a killed process may require downloading again. Completed APKs are checked again before install.
Installation uses a `PackageInstaller` session that also carries the APK's baseline profile as
`base.dm`, so ART compiles the interface at install time instead of after the device's next idle
dexopt (until then navigation can stutter). The system still asks for confirmation. If a device
rejects the profile, the same verified APK is installed once more without it.

## Building release metadata

`bash build.sh release` generates `dist/update.json` automatically **after signing and renaming**
all four ABI APKs. Individual release commands generate a manifest for their one ABI. Only files
from the current invocation are included; older files in `dist/` must not be uploaded with it.
Unsigned builds remove any previous manifest and defer generation until signing is configured.
No signing credentials or GitHub credentials are written to the manifest.

Direct Gradle `packageRelease` / `assembleRelease` runs `generateUpdateManifest` automatically.
When APKs are signed, it writes `app/build/outputs/update/update.json` using AGP output metadata.
When Gradle emits unsigned APKs, generation is deferred to `build.sh`. Debug builds do not run it.
The task may also be invoked explicitly as `:app:generateUpdateManifest`.

The generator verifies APK signatures with `apksigner`, reads package/version/minimum SDK and ABI
with `aapt2`, and hashes the final bytes. All APKs must have identical package/version/minimum SDK,
unique filenames/ABIs and exactly the expected ABI set. Failure prevents a manifest from being
left behind. A post-build APK rename or modification requires regenerating the manifest.

Publish `update.json` and **the APK filenames listed inside it** together in the same regular
GitHub release. Prefer uploading to a draft and publishing only when complete. Publishing is
not performed by the local build. The first release containing this updater must be installed
through the existing method; subsequent releases can then be installed from within the app.

Release `versionCode` must increase globally and the signing identity must remain compatible.
The existing local version increment is retained. CI or multiple build machines must allocate
one authoritative higher code (for example via `ORG_GRADLE_PROJECT_versionCode`) before building
all variants. The updater compares integer version codes, never tag strings, and never downgrades.

## Installation and validation

The app selects the first matching device ABI, with universal as fallback, and validates the
asset size, SHA-256, package name, version/minimum SDK and signing identity. A single-signer
rotation can match the APK signing history; Android performs final signature/rotation validation.
A narrowly scoped FileProvider grants read access to the verified APK to the Android installer.
Installation always requires the user flow, including unknown-app-source permission where needed.
The app never uninstalls an incompatible installation or treats closing the installer as success.

Automated checks:

```sh
python3 scripts/test_update_manifest.py
./gradlew :app:testDebugUnitTest --tests 'com.ambient.tv.update.*'
./gradlew :app:lintDebug
```

Device acceptance should cover an older release signed with the same key, permission denial and
return, download interruption, install cancellation, settings retention, incompatible signing,
and TV D-pad access to Home and About. Actual installation is separate evidence from compilation.
