#!/usr/bin/env bash
# Build Ambient TV including the optional native MPV runtime.
#
# Usage:
#   ./build.sh debug          # MPV (if needed) + debug APK
#   ./build.sh release        # MPV (if needed) + release APK
#   ./build.sh arm64          # arm64-v8a release APK only
#   ./build.sh armv7          # armeabi-v7a release APK only
#   ./build.sh x86            # x86 release APK only (emulator)
#   ./build.sh x86_64         # x86_64 release APK only (emulator)
#   ./build.sh arm64-adb [ip[:port]]      # build + optional ADB deploy
#   ./build.sh armv7-adb [ip[:port]]      # build + optional ADB deploy
#   ./build.sh x86-adb [ip[:port]]        # build + optional ADB deploy
#   ./build.sh x86_64-adb [ip[:port]]     # build + optional ADB deploy
#   ./build.sh all            # all Android artifacts
#   ./build.sh mpv            # only build/install native MPV libraries
#   ./build.sh svp            # only build/update the pinned Open-SVPFlow runtime
#   ./build.sh nnedi3-shaders # pre-bundle additional NNEDI3 win8x4/win8x6 assets
#   ./build.sh clean-mpv      # rebuild native MPV libraries from scratch
#   ./build.sh gen-keystore   # create keystore/release.jks
#   ./build.sh libs-check [svp]  # check all managed libraries, or only SVP
#   ./build.sh libs-update [svp] # update pins/assets/lockfile (no native build)
#                               # lib-check / lib-update are aliases
#
# Environment overrides:
#   SKIP_MPV_NATIVE=1         Do not build/check MPV before Android artifacts.
#   REBUILD_MPV=1             Force rebuilding MPV even when .so files exist.
#   REBUILD_SVP=1             Run SVP tests/build even when the runtime is cached.
#   SVP_ABIS="arm64-v8a"      Restrict a standalone SVP build (default: all four).
#   SKIP_SVP_NATIVE=1         Skip SVP native preparation before Android artifacts.
#   MPV_ANDROID_REF=<ref>     Forwarded to scripts/build_mpv_android.sh.
#   MPV_BUILD_ROOT=<path>     Forwarded to scripts/build_mpv_android.sh.
#   ANDROID_API=<api>         NDK API used by the MPV JNI shim (default 29).
#   SKIP_MPV_DOWNLOAD=1       Reuse an already prepared mpv-android checkout.
#   SMARTTUBE_MEDIA_SERVICE_REF=<sha>  Override pinned MediaServiceCore (set with SharedModules ref).
#   SMARTTUBE_SHARED_MODULES_REF=<sha> Override pinned SharedModules (set with MediaServiceCore ref).
#   REFRESH_YOUTUBE_ENGINE=1  Developer-only: test independent upstream heads instead of pinned pair.
#   SKIP_NNEDI3_VENDOR=1     Do not pre-bundle additional NNEDI3 shader assets.
#   ADB_DEVICE=<ip[:port]>     Wireless ADB target for *-adb modes (default port 5555).
#
# Output:
#   dist/ambient-tv-debug-<version>.apk
#   dist/ambient-tv-{arm64,armv7,x86,x86_64}-<version>.apk          (signed when configured)
#   dist/ambient-tv-{arm64,armv7,x86,x86_64}-unsigned-<version>.apk (without signing config)
#   Pre-release suffixes (beta, alpha, rc, …) also include the build number:
#   dist/ambient-tv-arm64-1.0.<versionCode>-beta.apk
#
# Signing:
#   If keystore.properties exists (local only, git-ignored) or the AMBIENT_TV_KEYSTORE_FILE,
#   AMBIENT_TV_KEYSTORE_PASSWORD, AMBIENT_TV_KEY_ALIAS and AMBIENT_TV_KEY_PASSWORD environment
#   variables are set, release APK artifacts are signed.
#   Expected keys: storeFile, storePassword, keyAlias, keyPassword.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

MODE="${1:-all}"
DIST_DIR="$ROOT_DIR/dist"
KEYSTORE_PROPS="$ROOT_DIR/keystore.properties"
APP_GRADLE="$ROOT_DIR/app/build.gradle.kts"
MPV_SCRIPT="$ROOT_DIR/scripts/build_mpv_android.sh"
PROJECTM_SCRIPT="$ROOT_DIR/scripts/build_projectm_android.sh"
YOUTUBE_ENGINE_SCRIPT="$ROOT_DIR/scripts/fetch_smarttube_engine.sh"
SPOTIFY_ENGINE_SCRIPT="$ROOT_DIR/scripts/build_spotify_engine.sh"
EXTERNAL_VERSIONS="$ROOT_DIR/gradle/external-versions.properties"
LIB_UPDATE_SCRIPT="$ROOT_DIR/scripts/update_external_libraries.py"
NNEDI3_VENDOR_SCRIPT="$ROOT_DIR/scripts/vendor_nnedi3_quality_ladder.py"

# App-owned native engines are built for these Android ABIs (TV ARM + emulator x86).
SUPPORTED_ABIS=(arm64-v8a armeabi-v7a x86 x86_64)

external_version() {
    local key="$1"
    [[ -f "$EXTERNAL_VERSIONS" ]] || fail "Missing external version pins: $EXTERNAL_VERSIONS"
    sed -n "s/^${key}=//p" "$EXTERNAL_VERSIONS" | head -n 1
}

log() { printf '\n==> %s\n' "$*"; }
fail() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }
warn() { printf '\nWARNING: %s\n' "$*" >&2; }


# Disable stale Groovy Gradle entrypoints left behind by older RCs/checkouts.
# Gradle builds are now Kotlin-DSL only; shadow files can silently select the legacy SmartTube graph.
disable_legacy_gradle_shadow_files() {
    local file backup
    local files=(
        "$ROOT_DIR/settings.gradle"
        "$ROOT_DIR/build.gradle"
        "$ROOT_DIR/app/build.gradle"
        "$ROOT_DIR/smarttube_wrappers/youtubeapi/build.gradle"
        "$ROOT_DIR/smarttube_wrappers/sharedutils/build.gradle"
        "$ROOT_DIR/smarttube_wrappers/mediaserviceinterfaces/build.gradle"
        "$ROOT_DIR/smarttube_wrappers/commons-io/build.gradle"
        "$ROOT_DIR/smarttube_wrappers/j2v8/build.gradle"
    )
    for file in "${files[@]}"; do
        [[ -e "$file" ]] || continue
        backup="${file}.legacy-disabled"
        log "Disabling stale Gradle shadow file: ${file#$ROOT_DIR/}"
        rm -f "$backup"
        mv "$file" "$backup"
    done
}

write_canonical_settings_kts() {
    local out="$1"
    mkdir -p "$(dirname "$out")"
    cat > "$out" <<'GRADLE_SETTINGS_KTS'
// AMBIENT_TV_SETTINGS_KTS_V66
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(org.gradle.api.initialization.resolve.RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "ambient-tv"
rootProject.buildFileName = "build.gradle.kts"
include(":app")
project(":app").buildFileName = "build.gradle.kts"

val smartRoot = file("third_party/smarttube")
val msc = smartRoot.resolve("MediaServiceCore")
val shared = smartRoot.resolve("SharedModules")
val wrappers = file("smarttube_wrappers")
if (msc.resolve("youtubeapi/src/main").isDirectory && shared.resolve("sharedutils/src/main").isDirectory) {
    include(":sharedutils", ":commons-io-2.8.0", ":j2v8", ":mediaserviceinterfaces", ":youtubeapi")

    project(":sharedutils").projectDir = wrappers.resolve("sharedutils")
    project(":sharedutils").buildFileName = "build.gradle.kts"

    project(":commons-io-2.8.0").projectDir = wrappers.resolve("commons-io")
    project(":commons-io-2.8.0").buildFileName = "build.gradle.kts"

    project(":j2v8").projectDir = wrappers.resolve("j2v8")
    project(":j2v8").buildFileName = "build.gradle.kts"

    project(":mediaserviceinterfaces").projectDir = wrappers.resolve("mediaserviceinterfaces")
    project(":mediaserviceinterfaces").buildFileName = "build.gradle.kts"

    project(":youtubeapi").projectDir = wrappers.resolve("youtubeapi")
    project(":youtubeapi").buildFileName = "build.gradle.kts"
}
GRADLE_SETTINGS_KTS
}

ensure_settings_kts() {
    local target="$ROOT_DIR/settings.gradle.kts"

    if [[ ! -f "$target" ]] \
        || ! grep -F 'AMBIENT_TV_SETTINGS_KTS_V66' "$target" >/dev/null 2>&1 \
        || ! grep -F 'rootProject.buildFileName = "build.gradle.kts"' "$target" >/dev/null 2>&1 \
        || ! grep -F 'project(":youtubeapi").projectDir = wrappers.resolve("youtubeapi")' "$target" >/dev/null 2>&1 \
        || ! grep -F 'project(":youtubeapi").buildFileName = "build.gradle.kts"' "$target" >/dev/null 2>&1; then
        log "Repairing canonical Kotlin Gradle settings entrypoint"
        write_canonical_settings_kts "$target"
    fi
}

verify_kotlin_dsl_entrypoints() {
    grep -F 'AMBIENT_TV_SETTINGS_KTS_V66' "$ROOT_DIR/settings.gradle.kts" >/dev/null \
        || fail "settings.gradle.kts is missing or stale."
    grep -F 'rootProject.buildFileName = "build.gradle.kts"' "$ROOT_DIR/settings.gradle.kts" >/dev/null \
        || fail "settings.gradle.kts does not pin the root Kotlin build file."
    grep -F 'project(":youtubeapi").buildFileName = "build.gradle.kts"' "$ROOT_DIR/settings.gradle.kts" >/dev/null \
        || fail "settings.gradle.kts does not pin the YouTube wrapper build file."
    grep -F 'project(":youtubeapi").projectDir = wrappers.resolve("youtubeapi")' "$ROOT_DIR/settings.gradle.kts" >/dev/null \
        || fail "youtubeapi is not mapped to the host wrapper."
}

version_name() {
    # Same resolution as Gradle: ORG_GRADLE_PROJECT_versionName (CI/tag) or version.properties.
    if [[ -n "${ORG_GRADLE_PROJECT_versionName:-}" ]]; then
        printf '%s\n' "$ORG_GRADLE_PROJECT_versionName"
        return
    fi
    sed -nE 's/^[[:space:]]*VERSION_NAME[[:space:]]*=[[:space:]]*([^[:space:]]+).*/\1/p' "$ROOT_DIR/version.properties" | head -n 1
}

version_code() {
    # Same resolution as Gradle: ORG_GRADLE_PROJECT_versionCode or version.properties.
    # Call this after Gradle has run so a local build sees the incremented code.
    if [[ -n "${ORG_GRADLE_PROJECT_versionCode:-}" ]]; then
        printf '%s\n' "$ORG_GRADLE_PROJECT_versionCode"
        return
    fi
    sed -nE 's/^[[:space:]]*VERSION_CODE[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' "$ROOT_DIR/version.properties" | head -n 1
}

# Dist APK label. Stable names stay as versionName. A pre-release suffix such as
# -beta, -alpha or -rc keeps the suffix and inserts the build number before it:
# 1.0-beta -> 1.0.270-beta.
apk_version_label() {
    local name code base suffix
    name="$(version_name)"
    if [[ "$name" =~ ^([0-9]+(\.[0-9]+)*)-([A-Za-z].*)$ ]]; then
        base="${BASH_REMATCH[1]}"
        suffix="${BASH_REMATCH[3]}"
        code="$(version_code)"
        if [[ "$code" =~ ^[0-9]+$ && "$base" != *".${code}" ]]; then
            printf '%s.%s-%s\n' "$base" "$code" "$suffix"
            return
        fi
    fi
    printf '%s\n' "$name"
}

ensure_android_sdk() {
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [[ -n "$sdk" ]]; then
        [[ -d "$sdk" ]] || fail "ANDROID_HOME/ANDROID_SDK_ROOT does not exist: $sdk"
        printf 'sdk.dir=%s\n' "$sdk" > "$ROOT_DIR/local.properties"
        return 0
    fi

    if [[ -f "$ROOT_DIR/local.properties" ]]; then
        local configured
        configured="$(sed -nE 's/^[[:space:]]*sdk.dir[[:space:]]*=[[:space:]]*(.*)$/\1/p' "$ROOT_DIR/local.properties" | tail -n 1)"
        if [[ -n "$configured" && -d "$configured" ]]; then
            return 0
        fi
    fi

    fail "Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT before building."
}

write_canonical_root_build_kts() {
    local target="$1"
    cat > "$target" <<'EOF_ROOT_BUILD'
// AMBIENT_TV_ROOT_BUILD_V036
buildscript {
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21") }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

subprojects {
    configurations.configureEach {
        resolutionStrategy.force(
            "org.chromium.net:cronet-api:143.7445.0",
            "org.chromium.net:cronet-common:143.7445.0",
            "org.chromium.net:cronet-embedded:143.7445.0",
        )
    }
}

// Build-graph preflight used by build.sh. It validates the actual project descriptors and
// concrete compile-task targets before any Android compilation starts.
tasks.register("verifySmartTubeBuildGraph") {
    group = "verification"
    description = "Verifies SmartTube host wrappers and Java/Kotlin JVM target alignment."
    doLast {
        val wrappers = mapOf(
            "youtubeapi" to "youtubeapi",
            "sharedutils" to "sharedutils",
            "mediaserviceinterfaces" to "mediaserviceinterfaces",
            "commons-io-2.8.0" to "commons-io",
            "j2v8" to "j2v8",
        )
        wrappers.forEach { (projectName, wrapperDir) ->
            val child = project(":$projectName")
            val expectedDir = rootProject.file("smarttube_wrappers/$wrapperDir").canonicalFile
            check(child.projectDir.canonicalFile == expectedDir) {
                "$projectName is using ${child.projectDir}, expected host wrapper $expectedDir"
            }
            check(child.buildFile.name == "build.gradle.kts") {
                "$projectName is using unexpected build file ${child.buildFile}"
            }

            child.tasks.withType(org.gradle.api.tasks.compile.JavaCompile::class.java).forEach { javaTask ->
                val target = javaTask.targetCompatibility
                println("Ambient TV preflight: ${javaTask.path} Java target = $target")
                check(target == "17") {
                    "${javaTask.path} Java target is $target, expected 17"
                }
            }

            child.tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile::class.java).forEach { kotlinTask ->
                val target = kotlinTask.compilerOptions.jvmTarget.get()
                println("Ambient TV preflight: ${kotlinTask.path} Kotlin target = $target")
                check(target == org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) {
                    "${kotlinTask.path} Kotlin target is $target, expected JVM_17"
                }
            }
        }
        println("Ambient TV preflight: SmartTube wrappers and JVM targets are correct.")
    }
}
EOF_ROOT_BUILD
}

ensure_root_build_kts() {
    local template="$ROOT_DIR/gradle/root.build.compat.gradle.kts"
    local target="$ROOT_DIR/build.gradle.kts"

    if [[ ! -f "$template" ]]         || ! grep -F 'AMBIENT_TV_ROOT_BUILD_V036' "$template" >/dev/null 2>&1         || ! grep -F 'expected host wrapper' "$template" >/dev/null 2>&1; then
        log "Repairing canonical root Gradle build preflight"
        write_canonical_root_build_kts "$template"
    fi

    if [[ ! -f "$target" ]]         || ! grep -F 'AMBIENT_TV_ROOT_BUILD_V036' "$target" >/dev/null 2>&1         || grep -F 'expected direct SmartTube module' "$target" >/dev/null 2>&1         || ! grep -F 'expected host wrapper' "$target" >/dev/null 2>&1; then
        log "Restoring canonical root Gradle build preflight"
        cp "$template" "$target"
    fi
}

verify_build_toolchain_matrix() {
    local catalog="$ROOT_DIR/gradle/libs.versions.toml"
    local wrapper="$ROOT_DIR/gradle/wrapper/gradle-wrapper.properties"
    [[ -f "$catalog" ]] || fail "Missing Gradle version catalog: $catalog"
    [[ -f "$wrapper" ]] || fail "Missing Gradle wrapper properties: $wrapper"
    grep -F 'agp = "9.4.1"' "$catalog" >/dev/null || fail "Unsupported AGP configuration. Expected AGP 9.4.1 for Kotlin 2.4.21."
    grep -F 'kotlin = "2.4.21"' "$catalog" >/dev/null || fail "Unexpected Kotlin plugin version. Expected Kotlin 2.4.21."
    grep -F 'ksp = "2.3.12"' "$catalog" >/dev/null || fail "Expected KSP 2.3.12."
    grep -F 'hilt = "2.60.1"' "$catalog" >/dev/null || fail "Expected Hilt 2.60.1."
    grep -F 'gradle-9.8.1-bin.zip' "$wrapper" >/dev/null || fail "Unexpected Gradle wrapper. Expected Gradle 9.8.1."
    grep -F 'alias(libs.plugins.android.library) apply false' "$ROOT_DIR/build.gradle.kts" >/dev/null || fail "Android library plugin is not aligned to AGP 9.4.1."
}

require_project() {
    ensure_settings_kts
    disable_legacy_gradle_shadow_files
    verify_kotlin_dsl_entrypoints
    ensure_root_build_kts
    rm -rf "$ROOT_DIR/.gradle"
    verify_build_toolchain_matrix
    log "Build toolchain: AGP 9.4.1 / Kotlin 2.4.21 / Gradle 9.8.1 / JVM bytecode 17"
    [[ -f "$APP_GRADLE" ]] || fail "Run this script from the Ambient TV project root. Missing app/build.gradle.kts."
    [[ -x "$ROOT_DIR/gradlew" ]] || chmod +x "$ROOT_DIR/gradlew"
    [[ -x "$ROOT_DIR/gradlew" ]] || fail "gradlew is not executable."
    command -v java >/dev/null 2>&1 || fail "Java is required (JDK 17 recommended)."
    ensure_android_sdk
}

mpv_libs_ready() {
    bash "$ROOT_DIR/scripts/prepare_local_audio.sh" --check "${SUPPORTED_ABIS[@]}" || return 1
    local abi marker expected_ref
    marker="$ROOT_DIR/app/src/main/jniLibs/.mpv-version"
    expected_ref="${MPV_ANDROID_REF:-$(external_version MPV_ANDROID_REF)}"
    [[ -f "$marker" ]] || return 1
    grep -Fx "mpv_android_ref=${expected_ref}" "$marker" >/dev/null || return 1
    if [[ "${MPV_ENABLE_VULKAN:-1}" == 1 ]]; then
        grep -Fx 'vulkan_pipeline=1' "$marker" >/dev/null || return 1
    fi
    for abi in "${SUPPORTED_ABIS[@]}"; do
        local dir="$ROOT_DIR/app/src/main/jniLibs/$abi"
        [[ -s "$dir/libmpv.so" && -s "$dir/libambientmpv.so" ]] || return 1
        [[ -f "$dir/.svp-mpv" && "$(<"$dir/.svp-mpv")" == "$(svp_adapter_hash mpv)" ]] || return 1
        # The JNI shim is compiled with the runtime; a changed shim source needs a rebuild.
        [[ -f "$dir/.projectm-pcm" ]] || return 1
        [[ -f "$dir/.ambientmpv-src" && "$(<"$dir/.ambientmpv-src")" == "$(mpv_shim_hash)" ]] || return 1
        [[ -f "$dir/.mpv-patches" && "$(<"$dir/.mpv-patches")" == "$(bash "$ROOT_DIR/scripts/mpv_patch_hash.sh")" ]] || return 1
    done
    return 0
}

mpv_shim_hash() {
    sha256sum "$ROOT_DIR/native/ambientmpv/ambientmpv.c" | cut -d ' ' -f1
}

verify_mpv_libs() {
    local abi
    for abi in "${SUPPORTED_ABIS[@]}"; do
        local dir="$ROOT_DIR/app/src/main/jniLibs/$abi"
        [[ -s "$dir/libmpv.so" ]] || fail "Missing $dir/libmpv.so"
        [[ -s "$dir/libambientmpv.so" ]] || fail "Missing $dir/libambientmpv.so"
        log "MPV runtime ready for $abi"
        ls -lh "$dir/libmpv.so" "$dir/libambientmpv.so"
    done
}

build_mpv() {
    local clean="${1:-0}"
    [[ -f "$MPV_SCRIPT" ]] || fail "Missing scripts/build_mpv_android.sh"
    chmod +x "$MPV_SCRIPT"

    if [[ "$clean" != "1" && "${REBUILD_MPV:-0}" != "1" ]] && mpv_libs_ready; then
        log "Existing MPV native libraries found; reusing them"
        verify_mpv_libs
        return 0
    fi

    log "Building native MPV runtimes for ${SUPPORTED_ABIS[*]}"
    if [[ "$clean" == "1" ]]; then
        "$MPV_SCRIPT" --clean
    else
        "$MPV_SCRIPT"
    fi
    verify_mpv_libs
}

projectm_libs_ready() {
    local abi marker expected_ref
    marker="$ROOT_DIR/app/src/main/jniLibs/.projectm-version"
    expected_ref="${PROJECTM_REF:-$(external_version PROJECTM_REF)}"
    [[ -f "$marker" ]] || return 1
    grep -Fx "projectm_ref=${expected_ref}" "$marker" >/dev/null || return 1
    grep -Fx "pcm_tap_api=1" "$marker" >/dev/null || return 1
    for abi in "${SUPPORTED_ABIS[@]}"; do
        local dir="$ROOT_DIR/app/src/main/jniLibs/$abi"
        [[ -s "$dir/libprojectM-4.so" && -s "$dir/libambientprojectm.so" ]] || return 1
    done
    [[ -s "$ROOT_DIR/app/src/main/assets/projectm/index.txt" ]] || return 1
    return 0
}

build_projectm() {
    local clean="${1:-0}"
    [[ -f "$PROJECTM_SCRIPT" ]] || fail "Missing scripts/build_projectm_android.sh"
    chmod +x "$PROJECTM_SCRIPT"
    if [[ "$clean" != "1" && "${REBUILD_PROJECTM:-0}" != "1" ]] && projectm_libs_ready; then
        log "Existing projectM runtime + presets found; reusing them"
        return 0
    fi
    log "Building projectM visualizer and preset assets"
    if [[ "$clean" == "1" ]]; then "$PROJECTM_SCRIPT" --clean; else "$PROJECTM_SCRIPT"; fi
    projectm_libs_ready || fail "projectM build finished without required runtime/assets"
}

ensure_projectm() {
    if [[ "${SKIP_PROJECTM_NATIVE:-0}" == "1" ]]; then
        warn "SKIP_PROJECTM_NATIVE=1: build continues without projectM native visualizer."
        return 0
    fi
    build_projectm 0
}



smarttube_live_patch_ready() {
    [[ -f "$ROOT_DIR/scripts/patch_smarttube_compat.py" ]] || return 1
    python3 "$ROOT_DIR/scripts/patch_smarttube_compat.py" \
        --check --root "$ROOT_DIR/third_party/smarttube" >/dev/null 2>&1
}

youtube_engine_ready() {
    local engine_version="$ROOT_DIR/third_party/smarttube/.engine-version"
    local msc="$ROOT_DIR/third_party/smarttube/MediaServiceCore"
    local shared="$ROOT_DIR/third_party/smarttube/SharedModules"
    [[ -f "$engine_version" ]] || return 1
    [[ -d "$msc/.git" && -d "$shared/.git" ]] || return 1
    grep -Fx 'COMPAT_PATCHSET=live-v106' "$engine_version" >/dev/null || return 1
    grep -Fx 'BUILD_INTEGRATION=host-wrappers-live-patch' "$engine_version" >/dev/null || return 1
    grep -Fx 'JVM_TARGET=17' "$engine_version" >/dev/null || return 1
    grep -Fx 'CRONET=143.7445.0' "$engine_version" >/dev/null || return 1
    grep -E '^SmartTubeBaseline=.+' "$engine_version" >/dev/null || return 1

    # Normal builds must use the compatible SmartTube release/submodule pair recorded in
    # gradle/external-versions.properties. Explicit paired overrides and REFRESH_YOUTUBE_ENGINE=1
    # remain developer escape hatches and validate by their recorded actual commits below.
    if [[ -z "${SMARTTUBE_MEDIA_SERVICE_REF:-}" && -z "${SMARTTUBE_SHARED_MODULES_REF:-}" && "${REFRESH_YOUTUBE_ENGINE:-0}" != "1" ]]; then
        local expected_st expected_ref expected_msc expected_shared
        expected_st="$(external_version SMARTTUBE_VERSION)"
        expected_ref="$(external_version SMARTTUBE_REF)"
        expected_msc="$(external_version SMARTTUBE_MEDIA_SERVICE_REF)"
        expected_shared="$(external_version SMARTTUBE_SHARED_MODULES_REF)"
        grep -Fx "SmartTubeBaseline=${expected_st}@${expected_ref}" "$engine_version" >/dev/null || return 1
        grep -Fx "MediaServiceCore=${expected_msc}" "$engine_version" >/dev/null || return 1
        grep -Fx "SharedModules=${expected_shared}" "$engine_version" >/dev/null || return 1
    fi

    local msc_commit shared_commit
    msc_commit="$(git -C "$msc" rev-parse HEAD 2>/dev/null)" || return 1
    shared_commit="$(git -C "$shared" rev-parse HEAD 2>/dev/null)" || return 1
    grep -Fx "MediaServiceCore=$msc_commit" "$engine_version" >/dev/null || return 1
    grep -Fx "SharedModules=$shared_commit" "$engine_version" >/dev/null || return 1
    smarttube_live_patch_ready || return 1

    local wrapper
    for wrapper in youtubeapi sharedutils mediaserviceinterfaces commons-io j2v8; do
        local gradle_file="$ROOT_DIR/smarttube_wrappers/$wrapper/build.gradle.kts"
        [[ -f "$gradle_file" ]] || return 1
        grep -F 'JavaVersion.VERSION_17' "$gradle_file" >/dev/null || return 1
    done
    grep -F 'JvmTarget.JVM_17' "$ROOT_DIR/smarttube_wrappers/youtubeapi/build.gradle.kts" >/dev/null || return 1
    grep -F 'JvmTarget.JVM_17' "$ROOT_DIR/smarttube_wrappers/sharedutils/build.gradle.kts" >/dev/null || return 1
    return 0
}

ensure_youtube_engine() {
    if [[ "${SKIP_YOUTUBE_ENGINE:-0}" == "1" ]]; then
        warn "SKIP_YOUTUBE_ENGINE=1: YouTube UI stays available but engine calls will be unavailable."
        return 0
    fi

    if youtube_engine_ready && [[ "${REFRESH_YOUTUBE_ENGINE:-0}" != "1" ]]; then
        log "SmartTube upstream checkout already live-patched (host wrappers, JVM 17)"
        return 0
    fi
    [[ -x "$YOUTUBE_ENGINE_SCRIPT" ]] || chmod +x "$YOUTUBE_ENGINE_SCRIPT"
    rm -rf "$ROOT_DIR/.gradle" "$ROOT_DIR/build" "$ROOT_DIR/app/build"
    rm -rf "$ROOT_DIR/smarttube_wrappers/youtubeapi/build" \
           "$ROOT_DIR/smarttube_wrappers/sharedutils/build" \
           "$ROOT_DIR/smarttube_wrappers/mediaserviceinterfaces/build" \
           "$ROOT_DIR/smarttube_wrappers/commons-io/build" \
           "$ROOT_DIR/smarttube_wrappers/j2v8/build"
    "$YOUTUBE_ENGINE_SCRIPT"
    youtube_engine_ready || fail "SmartTube live-patch integration did not validate after preparation."
    log "SmartTube live-patch integration ready (AGP 9.4.1/Kotlin 2.4.21/JVM 17)"
}


# JNI exports the Kotlin side (dev.rusty.app.NativeBridge) relies on. startDevice with the
# startup-volume argument and the AudioTrack/PcmOutput sink both require Rusty >= 2.7; the
# marker's rusty_ref pins the exact release.
SPOTIFY_REQUIRED_SYMBOLS=(
    Java_dev_rusty_app_NativeBridge_initAndroidContext
    Java_dev_rusty_app_NativeBridge_startDevice
    Java_dev_rusty_app_NativeBridge_setStartupVolume
    Java_dev_rusty_app_NativeBridge_setSpotifyAttenuation
    Java_dev_rusty_app_NativeBridge_requestAccessToken
)

spotify_symbol_present() {
    local so="$1"
    local symbol="$2"
    local output=""
    # Do not use `producer | grep -q` here: build.sh runs with pipefail and
    # grep -q may exit as soon as it finds the symbol, causing readelf/nm to
    # receive SIGPIPE. That makes a successful lookup look like a failure.
    if command -v readelf >/dev/null 2>&1; then
        output="$(readelf -Ws "$so" 2>/dev/null || true)"
        grep -F "$symbol" <<<"$output" >/dev/null && return 0
    fi
    if command -v nm >/dev/null 2>&1; then
        output="$(nm -D "$so" 2>/dev/null || true)"
        grep -F "$symbol" <<<"$output" >/dev/null && return 0
        output="$(nm "$so" 2>/dev/null || true)"
        grep -F "$symbol" <<<"$output" >/dev/null && return 0
    fi
    if command -v strings >/dev/null 2>&1; then
        output="$(strings "$so" 2>/dev/null || true)"
        grep -F "$symbol" <<<"$output" >/dev/null && return 0
    fi
    return 1
}

spotify_missing_symbol() {
    local so="$1" symbol
    for symbol in "${SPOTIFY_REQUIRED_SYMBOLS[@]}"; do
        spotify_symbol_present "$so" "$symbol" || { echo "$symbol"; return 0; }
    done
    return 1
}

spotify_libs_ready() {
    local abi marker expected_ref
    marker="$ROOT_DIR/app/src/main/jniLibs/.spotify-rusty"
    expected_ref="${RUSTY_REF:-$(external_version SPOTIFY_RUSTY_REF)}"
    [[ -f "$marker" ]] || return 1
    grep -Fx "rusty_ref=${expected_ref}" "$marker" >/dev/null || return 1
    for abi in "${SUPPORTED_ABIS[@]}"; do
        local dir="$ROOT_DIR/app/src/main/jniLibs/$abi"
        [[ -s "$dir/libspotify_receiver_core.so" && -s "$dir/libc++_shared.so" ]] || return 1
        spotify_missing_symbol "$dir/libspotify_receiver_core.so" >/dev/null && return 1
    done
    return 0
}

verify_spotify_libs() {
    local marker="$ROOT_DIR/app/src/main/jniLibs/.spotify-rusty"
    [[ -f "$marker" ]] || fail "Spotify core marker missing: $marker"
    local abi missing
    for abi in "${SUPPORTED_ABIS[@]}"; do
        local dir="$ROOT_DIR/app/src/main/jniLibs/$abi"
        [[ -s "$dir/libspotify_receiver_core.so" ]] || fail "Missing or empty Spotify core: $dir/libspotify_receiver_core.so"
        [[ -s "$dir/libc++_shared.so" ]] || fail "Missing or empty C++ runtime: $dir/libc++_shared.so"
        if missing="$(spotify_missing_symbol "$dir/libspotify_receiver_core.so")"; then
            fail "Spotify core for $abi does not export $missing (expected Rusty $(external_version SPOTIFY_RUSTY_REF))"
        fi
        log "Spotify librespot runtime + JNI exports verified for $abi"
        ls -lh "$dir/libspotify_receiver_core.so" "$dir/libc++_shared.so"
    done
}

build_spotify() {
    local clean="${1:-0}"
    [[ -f "$SPOTIFY_ENGINE_SCRIPT" ]] || fail "Missing scripts/build_spotify_engine.sh"
    chmod +x "$SPOTIFY_ENGINE_SCRIPT"
    if [[ "$clean" == "1" ]]; then
        rm -rf "$ROOT_DIR/third_party/spotify/rusty-src" "$ROOT_DIR/third_party/spotify/rusty-"*.tar.gz
        rm -f "$ROOT_DIR/app/src/main/jniLibs/.spotify-rusty" "$ROOT_DIR/app/src/main/jniLibs/.spotify-pcm-v39"
        for abi in "${SUPPORTED_ABIS[@]}"; do
            rm -f "$ROOT_DIR/app/src/main/jniLibs/$abi/libspotify_receiver_core.so"
        done
    fi
    if [[ "$clean" != "1" && "${REBUILD_SPOTIFY:-0}" != "1" ]] && spotify_libs_ready; then
        log "Existing Spotify runtime (Rusty $(external_version SPOTIFY_RUSTY_REF)) found; reusing it"
        verify_spotify_libs
        return 0
    fi
    log "Building REQUIRED Spotify/librespot core for ${SUPPORTED_ABIS[*]}"
    "$SPOTIFY_ENGINE_SCRIPT"
    verify_spotify_libs
}

ensure_spotify() {
    if [[ "${SKIP_SPOTIFY_ENGINE:-0}" == "1" ]]; then
        warn "SKIP_SPOTIFY_ENGINE=1: Spotify Native/PCM visualizers will be unavailable."
        return 0
    fi
    build_spotify 0
}

ensure_svp() {
    if [[ "${SKIP_SVP_NATIVE:-0}" != 1 ]]; then
        bash "$ROOT_DIR/scripts/build_svp_android.sh"
    fi
}

ensure_mpv() {
    ensure_svp
    if [[ "${SKIP_MPV_NATIVE:-0}" == "1" ]]; then
        warn "SKIP_MPV_NATIVE=1: Android build will continue without building MPV."
        return 0
    fi
    build_mpv 0
}

ensure_vlc_vulkan() {
    [[ "${SKIP_VLC_VULKAN:-0}" == 1 ]] && return 0
    local artifact="$ROOT_DIR/app/libs/libvlc-vulkan.aar"
    if [[ "${REBUILD_VLC_VULKAN:-0}" != 1 && -s "$artifact" ]]; then
        local abi complete=1
        local vlc_source_hash
        vlc_source_hash="$(bash "$ROOT_DIR/scripts/build_vlc_vulkan.sh" --hash)"
        for abi in "${SUPPORTED_ABIS[@]}"; do
            [[ -f "$ROOT_DIR/app/libs/.vlc-source-$abi" && "$(<"$ROOT_DIR/app/libs/.vlc-source-$abi")" == "$vlc_source_hash" ]] || complete=0
            unzip -l "$artifact" "jni/$abi/libvlcjni.so" | grep -q "jni/$abi/libvlcjni.so" || complete=0
            [[ -f "$ROOT_DIR/app/libs/.projectm-pcm-vlc-$abi" ]] || complete=0
            [[ -f "$ROOT_DIR/app/libs/.svp-vlc-$abi" && "$(<"$ROOT_DIR/app/libs/.svp-vlc-$abi")" == "$(svp_adapter_hash vlc)" ]] || complete=0
        done
        [[ "$complete" == 1 ]] && return 0
    fi
    log "Building Vulkan-enabled LibVLC for ${SUPPORTED_ABIS[*]}"
    bash "$ROOT_DIR/scripts/build_vlc_vulkan.sh"
}

svp_adapter_hash() {
    bash "$ROOT_DIR/scripts/prepare_svp_sources.sh" --hash "$1"
}

# Release-only native size optimization. Source jniLibs are restored after each build,
# so debug builds keep the original symbols while release artifacts get stripped copies.
NATIVE_RELEASE_BACKUP_DIR=""
NATIVE_RELEASE_STRIP_TOOL=""

find_native_strip_tool() {
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    local ndk_root="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
    local candidate=""
    local local_props="$ROOT_DIR/local.properties"
    local prop_sdk="" prop_ndk=""

    # Android Studio commonly stores SDK/NDK paths only in local.properties.
    if [[ -f "$local_props" ]]; then
        prop_sdk="$(sed -n 's/^sdk\.dir=//p' "$local_props" | tail -n 1 | sed 's/\\:/:/g; s/\\ / /g; s/\\\\/\\/g')"
        prop_ndk="$(sed -n 's/^ndk\.dir=//p' "$local_props" | tail -n 1 | sed 's/\\:/:/g; s/\\ / /g; s/\\\\/\\/g')"
        [[ -n "$sdk" ]] || sdk="$prop_sdk"
        [[ -n "$ndk_root" ]] || ndk_root="$prop_ndk"
    fi

    if [[ -n "$ndk_root" && -d "$ndk_root/toolchains/llvm/prebuilt" ]]; then
        candidate="$(find "$ndk_root/toolchains/llvm/prebuilt" -type f -name llvm-strip 2>/dev/null | head -n 1)"
        [[ -n "$candidate" ]] && { printf '%s\n' "$candidate"; return 0; }
    fi

    if [[ -n "$sdk" && -d "$sdk/ndk" ]]; then
        candidate="$(find "$sdk/ndk" -type f -path '*/toolchains/llvm/prebuilt/*/bin/llvm-strip' 2>/dev/null | sort -V | tail -n 1)"
        [[ -n "$candidate" ]] && { printf '%s\n' "$candidate"; return 0; }
    fi

    # Common Android Studio SDK locations, especially useful on Linux/Fedora/Flatpak-style homes.
    local common_sdk=""
    for common_sdk in         "$HOME/Android/Sdk"         "$HOME/Android/sdk"         "$HOME/Library/Android/sdk"         "/opt/android-sdk"         "/usr/lib/android-sdk"; do
        [[ -d "$common_sdk/ndk" ]] || continue
        candidate="$(find "$common_sdk/ndk" -type f -path '*/toolchains/llvm/prebuilt/*/bin/llvm-strip' 2>/dev/null | sort -V | tail -n 1)"
        [[ -n "$candidate" ]] && { printf '%s\n' "$candidate"; return 0; }
    done

    # Older SDK layouts may expose a single ndk-bundle directory.
    if [[ -n "$sdk" && -d "$sdk/ndk-bundle/toolchains/llvm/prebuilt" ]]; then
        candidate="$(find "$sdk/ndk-bundle/toolchains/llvm/prebuilt" -type f -name llvm-strip 2>/dev/null | head -n 1)"
        [[ -n "$candidate" ]] && { printf '%s\n' "$candidate"; return 0; }
    fi

    if command -v llvm-strip >/dev/null 2>&1; then
        command -v llvm-strip
        return 0
    fi
    return 1
}
native_release_library_selected() {
    case "$(basename "$1")" in
        libmpv.so|libambientmpv.so|libavcodec.so|libavdevice.so|libavfilter.so|libavformat.so|libavutil.so|libswresample.so|libswscale.so|libspotify_receiver_core.so|libprojectM-4.so|libprojectM-4-playlist.so|libambientprojectm.so)
            return 0 ;;
        *) return 1 ;;
    esac
}

human_bytes() {
    local bytes="$1"
    if command -v numfmt >/dev/null 2>&1; then
        numfmt --to=iec-i --suffix=B "$bytes"
    else
        printf '%s bytes\n' "$bytes"
    fi
}

prepare_native_release_libs() {
    [[ -z "$NATIVE_RELEASE_BACKUP_DIR" ]] || return 0

    NATIVE_RELEASE_STRIP_TOOL="$(find_native_strip_tool || true)"
    if [[ -z "$NATIVE_RELEASE_STRIP_TOOL" ]]; then
        warn "NDK llvm-strip not found; release build continues without manual native stripping."
        return 0
    fi

    local jni_root="$ROOT_DIR/app/src/main/jniLibs"
    [[ -d "$jni_root" ]] || return 0
    NATIVE_RELEASE_BACKUP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ambient-tv-native.XXXXXX")"

    local before=0 after=0 count=0 so rel size_before size_after backup
    while IFS= read -r -d '' so; do
        native_release_library_selected "$so" || continue
        rel="${so#$jni_root/}"
        backup="$NATIVE_RELEASE_BACKUP_DIR/$rel"
        mkdir -p "$(dirname "$backup")"
        cp -p "$so" "$backup"
        size_before="$(wc -c < "$so")"
        before=$((before + size_before))

        if "$NATIVE_RELEASE_STRIP_TOOL" --strip-unneeded "$so" >/dev/null 2>&1; then
            size_after="$(wc -c < "$so")"
            after=$((after + size_after))
            count=$((count + 1))
            printf '    %-48s %10s -> %10s\n' "$rel" "$(human_bytes "$size_before")" "$(human_bytes "$size_after")"
        else
            cp -p "$backup" "$so"
            after=$((after + size_before))
            warn "Could not strip $rel; keeping original library."
        fi
    done < <(find "$jni_root" -type f -name '*.so' -print0)

    if (( count > 0 )); then
        local saved=$((before - after))
        log "Native release strip: $count libraries, $(human_bytes "$before") -> $(human_bytes "$after") (saved $(human_bytes "$saved"))"
    else
        rm -rf "$NATIVE_RELEASE_BACKUP_DIR"
        NATIVE_RELEASE_BACKUP_DIR=""
    fi
}

restore_native_release_libs() {
    [[ -n "$NATIVE_RELEASE_BACKUP_DIR" && -d "$NATIVE_RELEASE_BACKUP_DIR" ]] || return 0
    local jni_root="$ROOT_DIR/app/src/main/jniLibs"
    local backup rel dest
    while IFS= read -r -d '' backup; do
        rel="${backup#$NATIVE_RELEASE_BACKUP_DIR/}"
        dest="$jni_root/$rel"
        mkdir -p "$(dirname "$dest")"
        cp -p "$backup" "$dest"
    done < <(find "$NATIVE_RELEASE_BACKUP_DIR" -type f -name '*.so' -print0)
    rm -rf "$NATIVE_RELEASE_BACKUP_DIR"
    NATIVE_RELEASE_BACKUP_DIR=""
    log "Restored unstripped native libraries for debug/development builds"
}

run_release_gradle() {
    prepare_native_release_libs
    local rc=0
    set +e
    local -a strip_args=()
    [[ -n "$NATIVE_RELEASE_STRIP_TOOL" ]] && strip_args+=("-PRELEASE_STRIP_TOOL=$NATIVE_RELEASE_STRIP_TOOL")
    "$ROOT_DIR/gradlew" --no-daemon --no-configuration-cache "${strip_args[@]}" "$@"
    rc=$?
    set -e
    restore_native_release_libs
    return "$rc"
}

# Safety net: never leave release-stripped binaries in the development source tree.
trap restore_native_release_libs EXIT INT TERM

find_build_tool() {
    local tool="$1"
    if command -v "$tool" >/dev/null 2>&1; then
        command -v "$tool"
        return 0
    fi

    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [[ -n "$sdk" && -d "$sdk/build-tools" ]]; then
        find "$sdk/build-tools" -type f -name "$tool" 2>/dev/null | sort -V | tail -n 1
        return 0
    fi
    return 1
}

prop_value() {
    local key="$1" env_name=""
    # Environment variables take precedence over keystore.properties so CI and
    # release machines can sign without a properties file on disk.
    case "$key" in
        storeFile) env_name="AMBIENT_TV_KEYSTORE_FILE" ;;
        storePassword) env_name="AMBIENT_TV_KEYSTORE_PASSWORD" ;;
        keyAlias) env_name="AMBIENT_TV_KEY_ALIAS" ;;
        keyPassword) env_name="AMBIENT_TV_KEY_PASSWORD" ;;
    esac
    if [[ -n "$env_name" && -n "${!env_name:-}" ]]; then
        printf '%s\n' "${!env_name}"
        return 0
    fi
    [[ -f "$KEYSTORE_PROPS" ]] || return 1
    sed -nE "s/^[[:space:]]*${key}[[:space:]]*=[[:space:]]*(.*)[[:space:]]*$/\\1/p" "$KEYSTORE_PROPS" | tail -n 1
}

signing_available() {
    local store_file store_password key_alias key_password
    store_file="$(prop_value storeFile || true)"
    store_password="$(prop_value storePassword || true)"
    key_alias="$(prop_value keyAlias || true)"
    key_password="$(prop_value keyPassword || true)"
    [[ -n "$store_file" && -n "$store_password" && -n "$key_alias" && -n "$key_password" ]] || return 1
    [[ "$store_file" = /* ]] || store_file="$ROOT_DIR/$store_file"
    [[ -f "$store_file" ]]
}

sign_release_apk() {
    local unsigned_apk="$1"
    local signed_apk="$2"
    local zipalign apksigner
    zipalign="$(find_build_tool zipalign || true)"
    apksigner="$(find_build_tool apksigner || true)"
    [[ -n "$zipalign" ]] || fail "zipalign not found; install Android SDK Build Tools."
    [[ -n "$apksigner" ]] || fail "apksigner not found; install Android SDK Build Tools."

    local store_file store_password key_alias key_password aligned
    store_file="$(prop_value storeFile)"
    store_password="$(prop_value storePassword)"
    key_alias="$(prop_value keyAlias)"
    key_password="$(prop_value keyPassword)"
    [[ "$store_file" = /* ]] || store_file="$ROOT_DIR/$store_file"
    aligned="${signed_apk%.apk}-aligned.apk"

    "$zipalign" -f -p 4 "$unsigned_apk" "$aligned"
    "$apksigner" sign \
        --v4-signing-enabled false \
        --ks "$store_file" \
        --ks-key-alias "$key_alias" \
        --ks-pass "pass:$store_password" \
        --key-pass "pass:$key_password" \
        --out "$signed_apk" \
        "$aligned"
    rm -f "$aligned"
    "$apksigner" verify --verbose --print-certs "$signed_apk"
}

verify_gradle_build_graph() {
    log "Verifying active Gradle build graph and SmartTube JVM targets"
    "$ROOT_DIR/gradlew" --no-daemon --no-configuration-cache verifySmartTubeBuildGraph
}

prepare_nnedi3_quality_ladder() {
    local required="${1:-0}"
    [[ "${SKIP_NNEDI3_VENDOR:-0}" == "1" ]] && { [[ "$required" == "1" ]] && fail "NNEDI3 vendoring was explicitly skipped." || return 0; }
    [[ -f "$NNEDI3_VENDOR_SCRIPT" ]] || { [[ "$required" == "1" ]] && fail "NNEDI3 vendor helper missing: $NNEDI3_VENDOR_SCRIPT" || { warn "NNEDI3 vendor helper missing; additional NNEDI3 presets remain unavailable."; return 0; }; }
    command -v python3 >/dev/null 2>&1 || { [[ "$required" == "1" ]] && fail "python3 is required to vendor NNEDI3 shaders." || { warn "python3 unavailable; additional NNEDI3 presets remain unavailable."; return 0; }; }
    log "Preparing complete NNEDI3 quality ladder (win8x4 plus nns256/win8x6 Expert)"
    if ! python3 "$NNEDI3_VENDOR_SCRIPT"; then
        [[ "$required" == "1" ]] && fail "Could not pre-bundle the additional NNEDI3 assets from the pinned mpv-prescalers revision."
        warn "Could not pre-bundle every additional NNEDI3 shader; unavailable presets stay out of the APK."
    fi
}

build_debug() {
    prepare_nnedi3_quality_ladder
    ensure_spotify
    ensure_youtube_engine
    ensure_mpv
    ensure_vlc_vulkan
    ensure_projectm
    verify_gradle_build_graph
    log "Building Ambient TV DEBUG APK"
    "$ROOT_DIR/gradlew" --no-daemon --no-configuration-cache assembleDebug
    mkdir -p "$DIST_DIR"
    local debug_dir="$ROOT_DIR/app/build/outputs/apk/debug"
    local src
    src="$(find "$debug_dir" -maxdepth 1 -type f -name '*.apk' ! -name '*arm64*' ! -name '*armeabi*' ! -name '*x86*' -print 2>/dev/null | head -n 1 || true)"
    local dest="$DIST_DIR/ambient-tv-debug-$(apk_version_label).apk"
    [[ -n "$src" && -f "$src" ]] || fail "Debug APK was not created under $debug_dir"
    cp "$src" "$dest"
    log "Debug APK: ${dest#$ROOT_DIR/}"
}

package_release_variant_output() {
    local variant="$1"
    local release_dir="$2"
    local src=""
    # Select only the output AGP reports for this invocation, never an old matching filename.
    src="$(python3 - "$release_dir/output-metadata.json" "$variant" <<'PY_METADATA'
import json
from pathlib import Path
import sys
metadata = Path(sys.argv[1])
abi = {"ARM64": "arm64-v8a", "ARMV7": "armeabi-v7a", "X86": "x86", "X86_64": "x86_64"}[sys.argv[2]]
elements = json.loads(metadata.read_text())["elements"]
matches = [element for element in elements if any(
    item.get("filterType") == "ABI" and item.get("value", item.get("identifier")) == abi for item in element.get("filters", []))]
if len(matches) != 1:
    raise SystemExit(f"Expected exactly one {abi} output in {metadata}")
print(metadata.parent / matches[0]["outputFile"])
PY_METADATA
)"
    [[ -n "$src" && -f "$src" ]] || fail "${variant} release APK was not created under app/build/outputs/apk/release/."

    local suffix
    suffix="$(printf '%s' "$variant" | tr '[:upper:]' '[:lower:]')"
    local signed_dest="$DIST_DIR/ambient-tv-${suffix}-$(apk_version_label).apk"
    local unsigned_dest="$DIST_DIR/ambient-tv-${suffix}-unsigned-$(apk_version_label).apk"

    if [[ "$(basename "$src")" != *unsigned* ]]; then
        cp "$src" "$signed_dest"
        LAST_BUILT_APK="$signed_dest"
        LAST_BUILT_APK_SIGNED=1
        log "${variant} APK: ${signed_dest#$ROOT_DIR/}"
    elif signing_available; then
        log "Signing ${variant} release APK (env or keystore.properties)"
        sign_release_apk "$src" "$signed_dest"
        LAST_BUILT_APK="$signed_dest"
        LAST_BUILT_APK_SIGNED=1
        log "Signed ${variant} APK: ${signed_dest#$ROOT_DIR/}"
    else
        cp "$src" "$unsigned_dest"
        LAST_BUILT_APK="$unsigned_dest"
        LAST_BUILT_APK_SIGNED=0
        warn "No complete signing config (env or keystore.properties) found; ${variant} APK remains unsigned."
        log "Unsigned ${variant} APK: ${unsigned_dest#$ROOT_DIR/}"
    fi
}

# Only APKs produced by this invocation are eligible; stale dist artifacts must not leak in.
UPDATE_APKS=()
UPDATE_ABIS=()
generate_release_update_manifest() {
    local output="$DIST_DIR/update.json"
    rm -f "$output"
    local apk
    for apk in "${UPDATE_APKS[@]}"; do
        if [[ "$apk" == *-unsigned-* ]]; then
            warn "Update manifest not generated: configure release signing first."
            return 0
        fi
    done
    local aapt2 apksigner expected
    aapt2="$(find_build_tool aapt2)"
    apksigner="$(find_build_tool apksigner)"
    [[ -n "$aapt2" && -n "$apksigner" ]] || fail "aapt2 and apksigner are required for the update manifest."
    expected="$(IFS=,; printf '%s' "${UPDATE_ABIS[*]}")"
    python3 "$ROOT_DIR/scripts/generate_update_manifest.py" --output "$output" \
        --aapt2 "$aapt2" --apksigner "$apksigner" --expected-abis "$expected" "${UPDATE_APKS[@]}"
}

build_release_variant() {
    prepare_nnedi3_quality_ladder
    local variant="$1"
    local abi_mode="$2"
    UPDATE_APKS=()
    UPDATE_ABIS=("$abi_mode")
    rm -f "$DIST_DIR/update.json"

    ensure_spotify
    ensure_youtube_engine
    ensure_mpv
    ensure_vlc_vulkan
    ensure_projectm
    verify_gradle_build_graph
    log "Building Ambient TV ${variant} RELEASE APK"
    run_release_gradle :app:packageRelease -PAPK_ABI="$abi_mode"
    mkdir -p "$DIST_DIR"

    package_release_variant_output "$variant" "$ROOT_DIR/app/build/outputs/apk/release"
    UPDATE_APKS+=("$LAST_BUILT_APK")
    generate_release_update_manifest
}

find_adb_binary() {
    if command -v adb >/dev/null 2>&1; then
        command -v adb
        return 0
    fi
    local candidate
    for candidate in \
        "${ANDROID_HOME:-}/platform-tools/adb" \
        "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
        "$HOME/Android/Sdk/platform-tools/adb"; do
        [[ -n "$candidate" && -x "$candidate" ]] && { printf '%s\n' "$candidate"; return 0; }
    done
    return 1
}

normalize_adb_target() {
    local target="$1"
    [[ "$target" == *:* ]] && printf '%s\n' "$target" || printf '%s:5555\n' "$target"
}

deploy_apk_nonfatal() {
    local apk="$1"
    local raw_target="${2:-${ADB_DEVICE:-}}"

    if [[ -z "$raw_target" ]]; then
        warn "ADB deploy skipped: no ADB_DEVICE configured."
        return 0
    fi
    if [[ "${LAST_BUILT_APK_SIGNED:-0}" != "1" ]]; then
        warn "ADB deploy skipped: release APK is unsigned. Configure keystore.properties first."
        return 0
    fi

    local adb
    adb="$(find_adb_binary || true)"
    if [[ -z "$adb" ]]; then
        warn "ADB deploy skipped: adb was not found in PATH or Android SDK platform-tools."
        return 0
    fi

    local target
    target="$(normalize_adb_target "$raw_target")"
    log "ADB target: $target"

    local output rc
    set +e
    output="$($adb connect "$target" 2>&1)"; rc=$?
    set -e
    if (( rc != 0 )) || { [[ "$output" != *connected* ]] && [[ "$output" != *already* ]]; }; then
        warn "ADB deploy skipped: $target is not reachable (${output:-adb connect failed})."
        return 0
    fi

    set +e
    output="$($adb -s "$target" get-state 2>&1)"; rc=$?
    set -e
    if (( rc != 0 )) || [[ "$output" != *device* ]]; then
        warn "ADB deploy skipped: $target is not in device state (${output:-unknown state})."
        return 0
    fi

    log "Installing $(basename "$apk") to $target"
    set +e
    output="$($adb -s "$target" install -r "$apk" 2>&1)"; rc=$?
    set -e
    if (( rc == 0 )) && [[ "$output" == *Success* ]]; then
        log "ADB install successful."
    else
        warn "ADB install failed, but build remains successful: ${output:-adb install failed}"
    fi
}

build_release_variant_adb() {
    local variant="$1"
    local build_task="$2"
    local cli_target="${3:-}"

    build_release_variant "$variant" "$build_task"
    deploy_apk_nonfatal "$LAST_BUILT_APK" "${cli_target:-${ADB_DEVICE:-}}"
}

build_release() {
    UPDATE_APKS=()
    UPDATE_ABIS=(arm64-v8a armeabi-v7a x86 x86_64)
    rm -f "$DIST_DIR/update.json"
    prepare_nnedi3_quality_ladder
    ensure_spotify
    ensure_youtube_engine
    ensure_mpv
    ensure_vlc_vulkan
    ensure_projectm
    verify_gradle_build_graph
    log "Building Ambient TV RELEASE APKs (arm64-v8a, armeabi-v7a, x86, x86_64)"
    run_release_gradle :app:packageRelease
    mkdir -p "$DIST_DIR"

    local release_dir="$ROOT_DIR/app/build/outputs/apk/release"
    local variant
    for variant in ARM64 ARMV7 X86 X86_64; do
        package_release_variant_output "$variant" "$release_dir"
        UPDATE_APKS+=("$LAST_BUILT_APK")
    done
    generate_release_update_manifest
}

gen_keystore() {
    command -v keytool >/dev/null 2>&1 || fail "keytool not found; install/use a full JDK 17."
    local out="${2:-keystore/release.jks}"
    [[ "$out" = /* ]] || out="$ROOT_DIR/$out"
    [[ ! -e "$out" ]] || fail "Keystore already exists: $out"
    mkdir -p "$(dirname "$out")"

    log "Generating Ambient TV release keystore"
    keytool -genkeypair -v \
        -keystore "$out" \
        -alias ambienttv \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000

    local rel="$out"
    [[ "$out" == "$ROOT_DIR/"* ]] && rel="${out#$ROOT_DIR/}"
    cat <<EOT

Keystore created: $out

Create keystore.properties in the project root:
storeFile=$rel
storePassword=YOUR_STORE_PASSWORD
keyAlias=ambienttv
keyPassword=YOUR_KEY_PASSWORD

Keep the keystore and passwords backed up securely.
EOT
}

clean_android() {
    log "Cleaning Android build outputs"
    "$ROOT_DIR/gradlew" --no-daemon clean
    rm -rf "$DIST_DIR"
}

if [[ "$MODE" == "libs-check" || "$MODE" == "lib-check" || "$MODE" == "deps-check" || "$MODE" == "libraries-check" ]]; then
    command -v python3 >/dev/null 2>&1 || fail "python3 is required"
    [[ -f "$LIB_UPDATE_SCRIPT" ]] || fail "Missing library updater: $LIB_UPDATE_SCRIPT"
    [[ $# -le 2 ]] || fail "Usage: $0 $MODE [svp]"
    library_args=()
    [[ $# -eq 1 ]] || library_args=(--only "$2")
    python3 "$LIB_UPDATE_SCRIPT" --check "${library_args[@]}"
    exit 0
fi
if [[ "$MODE" == "libs-update" || "$MODE" == "lib-update" || "$MODE" == "deps-update" || "$MODE" == "libraries-update" ]]; then
    command -v python3 >/dev/null 2>&1 || fail "python3 is required"
    [[ -f "$LIB_UPDATE_SCRIPT" ]] || fail "Missing library updater: $LIB_UPDATE_SCRIPT"
    [[ $# -le 2 ]] || fail "Usage: $0 $MODE [svp]"
    library_args=()
    [[ $# -eq 1 ]] || library_args=(--only "$2")
    python3 "$LIB_UPDATE_SCRIPT" --apply "${library_args[@]}"
    log "External pins updated. Review the diff and rebuild/test before committing."
    exit 0
fi

if [[ "$MODE" == "svp" ]]; then
    # Native-only work does not need to rewrite/clean the Android Gradle graph.
    bash "$ROOT_DIR/scripts/build_svp_android.sh"
    exit 0
fi

require_project

case "$MODE" in
    youtube-engine|smarttube)
        ensure_youtube_engine
        ;;
    projectm)
        build_projectm 0
        ;;
    clean-projectm)
        build_projectm 1
        ;;
    debug)
        build_debug
        ;;
    release)
        build_release
        ;;
    arm64|arm64-v8a)
        build_release_variant "ARM64" "arm64-v8a"
        ;;
    armv7|armeabi-v7a)
        build_release_variant "ARMV7" "armeabi-v7a"
        ;;
    x86)
        build_release_variant "X86" "x86"
        ;;
    x86_64|x86-64)
        build_release_variant "X86_64" "x86_64"
        ;;
    arm64-adb|arm64-v8a-adb)
        build_release_variant_adb "ARM64" "arm64-v8a" "${2:-}"
        ;;
    armv7-adb|armeabi-v7a-adb)
        build_release_variant_adb "ARMV7" "armeabi-v7a" "${2:-}"
        ;;
    x86-adb)
        build_release_variant_adb "X86" "x86" "${2:-}"
        ;;
    x86_64-adb|x86-64-adb)
        build_release_variant_adb "X86_64" "x86_64" "${2:-}"
        ;;
    all)
        build_debug
        build_release
        ;;
    spotify)
        build_spotify 0
        ;;
    clean-spotify)
        build_spotify 1
        ;;
    mpv)
        build_mpv 0
        ;;
    nnedi3|nnedi3-shaders)
        prepare_nnedi3_quality_ladder 1
        ;;
    clean-mpv)
        build_mpv 1
        ;;
    clean)
        clean_android
        ;;
    gen-keystore)
        gen_keystore "$@"
        ;;
    *)
        cat >&2 <<EOT
Usage: $0 [debug|release|arm64|armv7|x86|x86_64|arm64-adb|armv7-adb|x86-adb|x86_64-adb|all|youtube-engine|spotify|clean-spotify|mpv|clean-mpv|svp|nnedi3-shaders|projectm|clean-projectm|libs-check|libs-update|clean|gen-keystore] [adb-ip[:port] or svp for library updates]
EOT
        exit 2
        ;;
esac
