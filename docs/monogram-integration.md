# Monogram MTProto integration (GPL-3.0-only)

Ambient TV uses the name and icon **Monogram** for its Telegram-backed media
system. The app's stable internal `telegram` key is retained for preference and
Home-row migrations.

**Unofficial client, uses the Telegram API.** The same plain notice is shown at
the top of the Monogram account card in Settings (and so in the first-run
setup, which opens that page), before API credentials or sign-in.

The Monogram build is **GPL-3.0-only**. Its corresponding source is this
repository plus the pinned upstream release (`MONOGRAM_VERSION`, with
`MONOGRAM_REF` and `MONOGRAM_MONO_RS_REF` in `gradle/external-versions.properties`) with the
changes from `scripts/patch_monogram_leave.py`.

## What is built

Only upstream `monogram-android/monogram:native/mtproto` is compiled.
The build invokes `./gradlew :native:mtproto:assembleRelease` in an independent
checkout of a pinned upstream **release tag**. It never builds or packages Monogram's
`:app`, `core:ui`, `core:database`, `feature:auth`, `feature:chats`,
`feature:dialog`, `feature:folders`, `feature:profile`, `feature:settings`,
`network:bridge`, `network:http` or `native:markup` modules.

The pinned upstream native Rust crate depends on mono-rs and also originally
includes tlottie/TGS sticker rendering, VPX video-sticker decoding, native
waveform generation and a separate Rust QR codec. Ambient TV does not call
these features: the reproducible source patch in
`scripts/patch_monogram_leave.py` removes their Rust modules, UniFFI exports,
Kotlin facades and `tlottie`/`rxing`/`flate2` dependency declarations
**before compilation**. The native builder no longer checks out tlottie.
The actual MTProto `mono-rs` transport, auth, session, chat metadata and
media fetching remain intact; broader mono-rs functionality cannot all be
safely removed without independent, reviewed upstream feature work. The
upstream Rust release uses `opt-level="z"`. Ambient TV selects a portable
speed-oriented `opt-level=2` by default (compare `MONOGRAM_OPT_LEVEL=3` or
`z`), retaining full LTO, one codegen unit, stripping and `panic="abort"`.
R8 also shrinks Kotlin/Java code in release APKs.
The saved native bytes from this trim must be confirmed by a passing four-ABI
CI rebuild and compared with the previously recorded raw ELF figures.

The adapter lives under `app/src/monogram/java` and is included when
`MONOGRAM_NATIVE=1`. `./build.sh release` sets that flag for all four ABIs.
Debug and single-ABI builds leave it unset, so they contain neither the AAR,
its JNA/Rustls dependencies nor the Kotlin adapter. Each ABI APK ships only its
matching native library. For x86, the build explicitly compiles `i686-linux-android`;
if the native Rust crate cannot target it, the build must **fail** rather
than silently ship an unusable x86 APK.

## Reproducible build

Install Android SDK platforms 37.1 (app) and 37.0 (library modules),
NDK, JDK 17, Rust >= 1.98 and `cargo-ndk`.
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
User session credentials are kept separately under `noBackupFilesDir`
(app-private, excluded from Android backups). Ambient TV adds no extra
encryption layer to this session file; its protection is the Android app
sandbox. Phone codes, QR tokens and 2FA passwords are not persisted by
Ambient TV.

## Local data and sign-out

- Thumbnails, opened photos and streamed media chunks are cached **unencrypted**
  in the app-private `cacheDir/monogram` (sandboxed, never backed up, size-bounded,
  may be cleared by Android). In-flight `part-*.bin` downloads are never trimmed.
- Resume positions for reopening a post are kept **in memory only** and dropped on
  sign-out, account change and leaving a channel.
- **Sign out** first asks Telegram to end the session. Afterwards, and also when
  Telegram cannot confirm the logout (for example offline), this device deletes the
  native session file, the account id and the complete Monogram media cache. In the
  offline case the session may still be listed under Telegram › Settings › Devices
  until it is ended there; the app says so.

**Migration:** Builds that previously relied on `telegram.properties` or
`-PMONOGRAM_API_ID` now require entering the credentials in Monogram Settings.

```sh
./build.sh monogram                     # build the pinned native AAR, all Android ABIs
MONOGRAM_ABIS="arm64-v8a" ./build.sh monogram  # narrow standalone native build
./build.sh release                       # four ABI APKs, Monogram included
MONOGRAM_NATIVE=0 ./build.sh release     # four ABI APKs without Monogram
MONOGRAM_NATIVE=1 ./build.sh arm64       # one ABI APK with Monogram
./build.sh libs-check                   # includes the Monogram release pin
./build.sh libs-update                  # moves the Monogram pin with the other libraries
./build.sh libs-check monogram          # compare only the Monogram pin
./build.sh libs-update monogram         # move only the Monogram pin
./build.sh monogram-check               # alias of libs-check monogram
./build.sh monogram-update              # alias of libs-update monogram
```

### Release pin

Monogram is pinned to an upstream GitHub **release**, not to a branch head or
an arbitrary commit. Upstream publishes every release as a *pre-release*
(there is no non-pre-release yet), so pre-releases are accepted; drafts are not.

| Key | Value | Meaning |
|---|---|---|
| `MONOGRAM_VERSION` | `0.5.0` | Upstream release tag (tag commit dated 2026-11-15, pre-release; the tag has no `v` prefix) |
| `MONOGRAM_REF` | `712b1d1718ac9ca65ce46a56911bfe7cb99ac0f0` | Commit the tag pointed to when reviewed |
| `MONOGRAM_MONO_RS_REF` | `ccb2ea4606e1d90c173a7f1dbe5a66e0fd0a9a72` | `vendor/mono-rs` submodule commit at that tag |

`scripts/build_monogram_native.sh` fetches only the tag, and fails if the tag
no longer points to `MONOGRAM_REF` or the submodule is not at
`MONOGRAM_MONO_RS_REF`. The AAR stamp records `version=`, `ref=`, `abis=`, `patch=`,
`builder=`, `build_config=` and `opt_level=`; stale builds are rejected.
The build configuration includes the Rust compiler and effective Rust flags.
Do not globally enable optional CPU instructions on portable ABI builds.
`./build.sh libs-check` asks the GitHub releases API for the newest published
release (pre-releases included) and prints its tag, commit, mono-rs commit and
rustls version together with the other managed libraries. `./build.sh libs-update`
writes that pin. `monogram-check` and `monogram-update` do the same for Monogram
only.

Release 0.5.0 (previous pin 0.4.3) changes `native/` and mono-rs substantially.
It adds an upstream media-filtered `search_global_filtered` and reformats the
Kotlin adapter, so `scripts/patch_monogram_leave.py` was re-anchored (see
[Filtered global search](#filtered-global-search)). `scripts/build_monogram_native.sh`
force-resets the mono-rs submodule, because the previous build's patched files
otherwise block switching it to a new pinned commit.

External pins are in `gradle/external-versions.properties`. `./build.sh libs-check`
and `./build.sh libs-update` include the Monogram release pin with the other
managed libraries. An ordinary APK build still does not fetch a newer release.
The UniFFI ABI, MTProto models and licensing must be rechecked on every pin
update, and the native AAR rebuilt with `./build.sh monogram`. Generated AARs,
upstream source and credentials are git-ignored.
The assembly script merges per-ABI AARs only if their non-native Java resources
are identical.

## Size budget and release APK measurement

The native CI workflow builds all four ABIs, records the **raw MTProto ELF**
and compressed AAR size per ABI, checks ELF page alignment, exercises the
patched Rust range/crypto tests and compiles the opt-in Android adapter. A raw ELF budget alone
does **not** show that the final APK fits: Android can store native libraries
uncompressed, and Monogram also needs UniFFI, JNA and Rustls dependencies.

To measure the **actual extra bytes per ABI**, use the full normal release build
with the required existing Ambient TV engines (SmartTube, VLC/MPV,
projectM and SVP) available:

```sh
./build.sh monogram-size arm64      # baseline release vs Monogram release
./build.sh monogram-size armv7
./build.sh monogram-size x86
./build.sh monogram-size x86_64
./build.sh monogram-size all        # all four comparisons, sequential
```

Each benchmark builds the **same source and versionCode**, first with
`MONOGRAM_NATIVE=0` and then `MONOGRAM_NATIVE=1`, using the same single ABI,
release minification, R8 and packaging options. The normal build infrastructure
prepares/reuses its native engines; `CI=1` prevents a versionCode bump between
the pair. The check fails if the added APK bytes exceed **15,000,000 bytes**
(15 MB, decimal), if the architecture mismatches or if the baseline already
contains Monogram. Report files are written to
`dist/monogram-size-<abi>.md` and `dist/monogram-size-<abi>.json`.

The report shows total APK byte delta (the **actual budget gate**), native
library ZIP contribution, DEX, resources and largest increased entries.
Compressed AAR/ELF figures must **never** be described as an APK-size result.
An opt-in APK requires the same GPL source/distribution review regardless of
the size budget.

## Account setup and sign-in (Android TV / phone)

The in-app Monogram account section follows the **Settings → Media sources
& accounts** workflow: application credentials, the current account
status, then the available sign-in actions in a single settings card.

1. Create an application at https://my.telegram.org/apps, then open
   **Settings → Media sources & accounts → Monogram** in a Monogram-enabled APK.
   Use the **Open Telegram API applications** action: on Android TV it shows a
   QR to the dashboard; on the phone it opens the browser.
2. Enter **API ID** and **API hash**, select **Save API credentials**, then
   enable Monogram. The status becomes **Not signed in to Telegram** until a
   user account is authorized.
3. **TV QR method:** Select **Sign in with QR code**. Telegram on a separate,
   already signed-in phone scans the QR through **Settings → Devices**. The
   QR contains `tg://login?token=...`, **not** a web callback.
   Closing the dialog does not sign into Telegram; the account status reflects
   actual native authorization.
4. **Phone/code method (also on TV):** Enter an international phone number,
   request a code, confirm the code and enter the optional two-step verification
   password. A phone uses this method because it cannot scan its own screen.
5. The **Connected account** status confirms native authorization. From here
   the user can browse six tabs/Home rows: Recent media, Channels, Videos,
   YouTube links, Music & audio and Photos. **Sign out** revokes access to
   account-private media and cache. The API ID/hash can only be changed after
   sign-out.

The UI keeps input errors visible and never stores phone verification codes,
QR tokens or 2FA passwords in preferences or backups.

## Minimal channel leave patch

The pinned upstream Monogram MTProto interface provides joinChat but no
`channels.leaveChannel`. The opt-in build therefore applies the versioned,
idempotent `scripts/patch_monogram_leave.py` to the pinned GPL module and
its pinned mono-rs submodule. Only the standard Telegram channels.leaveChannel
RPC plus a small Rust/UniFFI/Kotlin bridge are added: no extra UI, databases,
or Monogram feature modules are compiled.

The AAR stamp includes this patch's SHA-256 checksum. A patch change cannot
silently reuse old MTProto binaries. Distributions must include these GPL
patches and corresponding source. Verify join, leave, reconnect, account cache
invalidation and all four ABIs on real devices before marking the PR ready.

### Filtered global search

The same script exports `search_global_filtered` to Kotlin: Telegram's
`messages.searchGlobal` with a media filter (videos, music, URLs) and
`broadcasts_only`. Since 0.5.0 upstream mono-rs has its own media-filtered
`search_global_filtered` (with a folder, but no channels-only scope), so the patch
adds `search_global_channels` in mono-rs on top of upstream's request builder and
routes the UniFFI export through it; the Kotlin signature is unchanged. The Videos, YouTube and Music categories load
one global page per request instead of one `messages.search` per channel, which
ran into FLOOD_WAIT on a first load without cache. If Telegram rejects the empty
query with a filter, the app falls back to the per-channel search.

### Modification notices (GPL-3.0 section 5a)

The patch script writes a dated notice at the top of every upstream file it
changes, for example:

```
// Modified by Ambient TV, 2026-11-01: added leave_chat (Telegram channels.leaveChannel).
// Changed by scripts/patch_monogram_leave.py; distributed under GPL-3.0-only.
```

The notice is added once (re-running the script changes nothing) and also to
files patched by an earlier version of the script. The date is fixed in the
script, not taken from the build, so builds stay reproducible; update it with
the description when the patch changes. Two unused upstream Kotlin facades are
removed instead of modified: `native/mtproto/.../LottieNative.kt` and
`native/mtproto/.../VpxNative.kt`. Changing the script changes its SHA-256 in
the AAR stamp, so the Monogram AAR must be rebuilt (`./build.sh monogram`);
normal builds and unit tests do not use the AAR.

## Settings backups and migration

The ordinary Ambient TV settings backup includes Monogram activation, visible
submenus/TV chips, Home-row definitions, system names, sidebar order and
section view-mode preferences (including `ROWS`, `GRID`, `LIST` and `INFO_LIST`).
It does **not** include the Telegram application API ID/hash.

Select **Export with credentials** and enter a backup passphrase to include the
**application API ID and hash**. The exported file is encrypted with
`BackupEncryption`; imported values are validated and stored in the encrypted
`TokenStore` on the receiving device. Older backups and backups without
credentials leave a device's configured Monogram API identity untouched. To
avoid breaking a live native MTProto client, an incoming identity that differs
from already configured credentials is rejected before settings are modified.
Sign out and set matching API credentials in Monogram settings first.

**User account login state is never portable.** Native authorization, phone/QR
codes, 2FA material, private messages, original media and cached thumbnails are
not exported, including with credentials. A restored installation needs a new
Telegram sign-in. Companion transfers follow the same secret classification:
API credentials move only with an explicit credentials transfer and can be
adopted only on a device without an existing application identity.

## Distribution / legal considerations

Monogram's native module and its original logo artwork are GPL-3.0-only.
The logo artwork lives in `app/src/monogram/res` and is packaged in
`./build.sh release` and other `MONOGRAM_NATIVE=1` builds. Debug and single-ABI
builds use a separate CC0 icon from `app/src/plain/res` and show no
Monogram/Telegram UI at all.
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


## Native streaming performance

Ambient TV's versioned source patch adds a bounded 512 KiB in-memory origin
range sink to mono-rs, preserving existing authentication, file-reference
refresh, migration, flood handling, cancellation and session-lease publication.
Inline responses retain the bounded file fallback. Streaming ranges request
origin data instead of starting the whole-file CDN receiver; ordinary full
downloads retain upstream CDN behavior. The UniFFI
byte-array export avoids a native payload write and Kotlin payload re-read;
the app can persist a single atomic cache copy. The dedicated Android JNI path returns the final JVM byte array directly,
avoiding the intermediate UniFFI RustBuffer/JNA lifting. Media3 and the
loopback transport already consume byte arrays; introducing a DirectByteBuffer
would add a conversion copy here. UniFFI remains a missing-symbol fallback. No global CPU-feature assumptions are added.

A sequential read grows a bounded prefetch window from one to three parts;
a discontinuous seek discards it and cancels native request controls when
the final waiter leaves. Four concurrent part RPCs are allowed across players.
Identical part requests are coalesced by account generation and offset. Preview and individual streaming ranges
use single wire requests per lane, while known large complete user downloads
may pipeline up to four parts. The chunk cache tracks LRU order incrementally.
The chunk hot cache retains at most eight parts (4 MiB), cleared on account
generation changes. The encrypted metadata snapshot retains a bounded
in-memory parsed cache and skips unchanged snapshot writes.
The pinned aes 0.9.3, sha1 0.11.0 and sha2 0.11.1 depend on cpufeatures
0.3.1; preserve their safe CPU dispatch rather than forcing AES/SHA/AVX on
all devices. The patch also removes fixed-size KDF heap allocations and
reuses authenticated plaintext storage; existing authentication and
constant-time comparisons are unchanged.

Benchmark on Shield and a phone: first frame, random and forward seek latency,
sustained 1080p/4K throughput, concurrent thumbnail load, native and Java heap,
GC, disk writes, and release APK delta per ABI. Compare the three compiler
profiles before raising the portable default; keep the existing APK size gate.

For compiler profile size comparisons, run `MONOGRAM_ABIS=arm64-v8a bash scripts/measure_monogram_native_profiles.sh`. This reports actual AAR and raw ELF bytes, not APK overhead or device throughput.

An opt-in Shield-oriented build is available with `MONOGRAM_CPU_TUNE=cortex-a57 MONOGRAM_ABIS=arm64-v8a bash scripts/build_monogram_native.sh`. Use the same `MONOGRAM_CPU_TUNE=cortex-a57` when assembling the ARM64 APK. Multi-ABI and ordinary release builds use the generic default; the AAR stamp prevents accidentally packaging the tuned binary as a generic one. Android-only compiler flags do not affect host bindgen. Optional AES/SHA/CRC features are not globally enabled. CI builds the additional tuned ARM64 variant and verifies the JNI symbol in each ELF.
