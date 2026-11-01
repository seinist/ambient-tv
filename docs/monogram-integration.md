# Minimal Monogram MTProto integration (optional GPL build)

Ambient TV uses the name and icon **Monogram** for its Telegram-backed media
system. The app's stable internal `telegram` key is retained for preference and
Home-row migrations.

## What is built

Only upstream `monogram-android/monogram:native/mtproto` is compiled.
The build invokes `./gradlew :native:mtproto:assembleRelease` in an independent,
SHA-pinned upstream checkout. It never builds or packages Monogram's
`:app`, `core:ui`, `core:database`, `feature:auth`, `feature:chats`,
`feature:dialog`, `feature:folders`, `feature:profile`, `feature:settings`,
`network:bridge`, `network:http` or `native:markup` modules.

The upstream native *Rust crate* still depends on mono-rs and tlottie and
exports broader Telegram APIs; those crates cannot be safely elided without
upstream features or a maintained fork. Upstream uses `opt-level="z"`,
full LTO, stripping and `panic="abort"` for release. R8 also shrinks
Kotlin/Java code when building Ambient TV release.

The adapter lives under `app/src/monogram/java` and is included only when
`MONOGRAM_NATIVE=1`. Normal builds contain neither the AAR, its JNA/Rustls
dependencies nor the Kotlin adapter. Each ABI APK ships only its matching native
library. For x86, the build explicitly compiles `i686-linux-android`;
if the native Rust crate cannot target it, the build must **fail** rather
than silently ship an unusable x86 APK.

## Reproducible build

Install Android SDK 37, NDK, JDK 17, Rust >= 1.98 and `cargo-ndk`.
**Builds require no Telegram application credentials**, and there are no
`MONOGRAM_API_ID`/`MONOGRAM_API_HASH` BuildConfig constants.

After installing a Monogram-enabled APK, open **Settings → Systems → Monogram**,
enter your own **API ID** and **API hash** (obtained at
https://my.telegram.org/apps), then select **Save API credentials**. Both
values are written atomically to the encrypted, private TokenStore
(`@EncryptedPrefs`). They are never included in source code or APK bytecode.
The hash field is masked; it is an application key, not a session token.
QR/phone/2FA login remains disabled until both fields are valid.

Application credentials can be edited only while signed out and with no
authentication in progress. Saving an update destroys the old unsigned native
client, discards stale local session files and starts a new application context.
User session credentials are kept separately under `noBackupFilesDir`, with
Monogram's Android Keystore-backed wrapping. Phone codes, QR tokens and
2FA passwords are not persisted by Ambient TV.

**Migration:** Builds that previously relied on `telegram.properties` or
`-PMONOGRAM_API_ID` now require entering the credentials in Monogram Settings.

```sh
./build.sh monogram                     # build the pinned native AAR, all Android ABIs
MONOGRAM_ABIS="arm64-v8a" ./build.sh monogram  # narrow standalone native build
MONOGRAM_NATIVE=1 ./build.sh arm64       # APK with Monogram, only arm64 runtime
MONOGRAM_NATIVE=1 ./build.sh release     # APKs for all four ABIs
./build.sh monogram-check               # compare pinned upstream SHA
./build.sh monogram-update              # explicitly move pin; review before shipping
```

External pins are in `gradle/external-versions.properties`. Monogram is NOT
silently upgraded through a normal `libs-update` of unrelated dependencies:
the UniFFI ABI, MTProto models and licensing must be rechecked on every pin
update. Generated AARs, upstream source and credentials are git-ignored.
The assembly script merges per-ABI AARs only if their non-native Java resources
are identical.

## Distribution / legal considerations

Monogram's native module and its original logo artwork are GPL-3.0-only.
An optional compile-time flag, separate source directory or independent
dependency build **does not** waive the GPL. If distributing a Monogram-enabled
Ambient TV binary, review GPL-3.0-compatible distribution of the combined
program, retain copyright notices and licenses, and provide corresponding
source including build instructions, patches and source/pins for mono-rs.
Also review Telegram's API Terms of Service. The plain build stays separate.

This source integration does not in itself establish successful compilation
of the optional Rust/UniFFI path, full Android device playback, ABI size or
licensing approval. Before release: build arm64/armv7/x86/x86_64; instrument
QR, code, 2FA, logout, session restore and media seeking; inspect ABI APK
size with `apkanalyzer`; test background playback and session/cache cleanup.
