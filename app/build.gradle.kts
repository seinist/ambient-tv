import java.io.File
import java.net.URI
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

fun loadProjectProperties(fileName: String): Properties = Properties().apply {
    val file = rootProject.file(fileName)
    if (file.isFile) file.reader(Charsets.UTF_8).use { load(it) }
}


val spotifyRedirectUriForBuild: String = run {
    val fromGradle = (project.findProperty("SPOTIFY_REDIRECT_URI") as? String).orEmpty().trim()
    val fromEnv = System.getenv("SPOTIFY_REDIRECT_URI").orEmpty().trim()
    val fromSpotifyFile = loadProjectProperties("spotify.properties")
        .getProperty("SPOTIFY_REDIRECT_URI", "")
        .trim()
    fromGradle.ifBlank { fromEnv }.ifBlank { fromSpotifyFile }.ifBlank { "ambienttv://spotify-auth" }
}
// HTTPS relay page for the TV QR sign-in (seinist/ambient-tv-spotify-callback, served by GitHub Pages).
// It must also be registered as a redirect URI in the Spotify app. "off" disables QR sign-in.
val spotifyQrRedirectUriForBuild: String = run {
    val fromGradle = (project.findProperty("SPOTIFY_QR_REDIRECT_URI") as? String).orEmpty().trim()
    val fromEnv = System.getenv("SPOTIFY_QR_REDIRECT_URI").orEmpty().trim()
    val fromSpotifyFile = loadProjectProperties("spotify.properties")
        .getProperty("SPOTIFY_QR_REDIRECT_URI", "")
        .trim()
    val configured = fromGradle.ifBlank { fromEnv }.ifBlank { fromSpotifyFile }
        .ifBlank { "https://seinist.github.io/ambient-tv-spotify-callback/spotify-callback.html" }
    if (configured.equals("off", ignoreCase = true)) "" else configured
}
require(spotifyQrRedirectUriForBuild.isEmpty() || URI(spotifyQrRedirectUriForBuild).scheme == "https") {
    "SPOTIFY_QR_REDIRECT_URI must be an https URL (or \"off\")"
}
val spotifyRedirectParsed = URI(spotifyRedirectUriForBuild)
require(!spotifyRedirectParsed.scheme.isNullOrBlank() && !spotifyRedirectParsed.host.isNullOrBlank()) {
    "SPOTIFY_REDIRECT_URI must contain a scheme and host"
}

fun quotedBuildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Rewrites only the text between version markers in both READMEs (idempotent; no commit).
// versionName: -PversionName (release builds pass the tag without "v") or VERSION_NAME from version.properties.
val appVersionName: String = (project.findProperty("versionName") as? String)?.trim()?.takeIf(String::isNotEmpty)
    ?: loadProjectProperties("version.properties").getProperty("VERSION_NAME", "").trim().takeIf(String::isNotEmpty)
    ?: error("version.properties must contain VERSION_NAME")
require(appVersionName.matches(Regex("[0-9A-Za-z._+-]{1,64}"))) { "versionName must be a simple version string" }

fun updateReadmeVersions(code: Int) {
    val name = appVersionName
    val badge = "https://img.shields.io/badge/Version-" +
        name.replace("-", "--").replace("_", "__").replace(" ", "%20") + "%20%28" + code + "%29-E5A00D"
    for (fileName in listOf("README.md", "README.de.md")) {
        val file = rootProject.file(fileName)
        if (!file.isFile) continue
        val before = file.readText(Charsets.UTF_8)
        val after = before
            .replace(Regex("<!-- version:start -->.*?<!-- version:end -->")) {
                "<!-- version:start -->`$name` ($code)<!-- version:end -->"
            }
            .replace(Regex("<!-- version-badge:start -->.*?<!-- version-badge:end -->", RegexOption.DOT_MATCHES_ALL)) {
                "<!-- version-badge:start -->\n\n[![Version]($badge)](#)\n\n<!-- version-badge:end -->"
            }
        if (after != before) file.writeText(after, Charsets.UTF_8)
    }
}

// versionCode comes from version.properties and goes up by one on every local build that produces
// an APK/AAB (assemble*/bundle*/install*/package* requested). Sync, tests-only, clean and CI (CI env
// set) leave it unchanged. The README version markers are updated alongside. -PversionCode=N overrides without touching the file. The file is not committed
// automatically.
val versionPropertiesFile = rootProject.file("version.properties")
val appVersionCode: Int = run {
    (project.findProperty("versionCode") as? String)?.trim()?.toIntOrNull()?.let { return@run it }
    val stored = loadProjectProperties("version.properties").getProperty("VERSION_CODE", "").trim().toIntOrNull()
        ?: error("version.properties must contain VERSION_CODE=<integer>")
    val producesArtifact = gradle.startParameter.taskNames.any { requested ->
        val name = requested.substringAfterLast(':')
        Regex("^(assemble|bundle|install|package)").containsMatchIn(name) &&
            !name.contains("UnitTest") && !name.contains("AndroidTest")
    }
    val isCi = !System.getenv("CI").isNullOrBlank()
    if (!producesArtifact || isCi) return@run stored
    val next = stored + 1
    val text = versionPropertiesFile.readText(Charsets.UTF_8)
    versionPropertiesFile.writeText(
        text.replace(Regex("(?m)^\\s*VERSION_CODE\\s*=.*$"), "VERSION_CODE=$next"),
        Charsets.UTF_8,
    )
    logger.lifecycle("versionCode $stored -> $next (version.properties)")
    updateReadmeVersions(next)
    next
}

val appDisplayName = loadProjectProperties("branding.properties")
    .getProperty("APP_DISPLAY_NAME", "Ambient TV").trim()
require(appDisplayName.isNotBlank() && appDisplayName.none { it == '\n' || it == '\r' || it == '"' || it == '\\' }) {
    "APP_DISPLAY_NAME must be a non-empty single-line name without quotes or backslashes"
}

val externalVersions = loadProjectProperties("gradle/external-versions.properties")
val vulkanVlcAar = file("libs/libvlc-vulkan.aar")
fun externalVersion(key: String): String = externalVersions.getProperty(key, "").trim()

// APK_ABI controls which release artifact is produced by :app:packageRelease.
// all (default): arm64 + armv7 + x86 + x86_64 split APKs plus universal APK
// universal: one universal APK containing all supported ABIs
// arm64-v8a / armeabi-v7a / x86 / x86_64: one ABI-only APK
val apkAbiMode = providers.gradleProperty("APK_ABI")
    .orElse(providers.environmentVariable("APK_ABI"))
    .orElse("all")
    .get()
    .trim()
    .lowercase()

val selectedAbis = when (apkAbiMode) {
    "arm64", "arm64-v8a" -> listOf("arm64-v8a")
    "arm", "armv7", "armeabi-v7a" -> listOf("armeabi-v7a")
    "x86" -> listOf("x86")
    "x86_64", "x86-64" -> listOf("x86_64")
    else -> listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
}
if (vulkanVlcAar.isFile) {
    ZipFile(vulkanVlcAar).use { aar ->
        val missing = selectedAbis.filter { aar.getEntry("jni/$it/libvlcjni.so") == null || aar.getEntry("jni/$it/libvlc.so") == null }
        require(missing.isEmpty()) { "Vulkan LibVLC is missing $missing. Run scripts/build_vlc_vulkan.sh for these ABIs before packaging." }
    }
}


android {
    namespace = "com.ambient.tv"
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.ambient.tv"
        minSdk = 29
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "com.ambient.tv.ui.player.PlaybackAuditInstrumentation"

        // Native playback runtimes: ARM (TV devices) + x86/x86_64 (emulators / rare x86 TV).
        // ABI selection is handled exclusively by splits.abi below. Do not also set
        // ndk.abiFilters here: AGP 8.11+ rejects using both mechanisms together.

        buildConfigField("String", "APP_DISPLAY_NAME", quotedBuildConfigString(appDisplayName))
        resValue("string", "brand_app_name", quotedBuildConfigString(appDisplayName))
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", quotedBuildConfigString(spotifyRedirectUriForBuild))
        buildConfigField("String", "SPOTIFY_QR_REDIRECT_URI", quotedBuildConfigString(spotifyQrRedirectUriForBuild))
        manifestPlaceholders["spotifyRedirectScheme"] = spotifyRedirectParsed.scheme
        manifestPlaceholders["spotifyRedirectHost"] = spotifyRedirectParsed.host

        // Exact component versions shown by Settings > About. Native/upstream pins come from
        // gradle/external-versions.properties, while Maven/Gradle dependencies come from the
        // version catalog so the About page cannot drift from the actual build inputs.
        buildConfigField("String", "CORE_KTX_VERSION", quotedBuildConfigString(libs.versions.coreKtx.get()))
        buildConfigField("String", "LIFECYCLE_VERSION", quotedBuildConfigString(libs.versions.lifecycle.get()))
        buildConfigField("String", "ACTIVITY_COMPOSE_VERSION", quotedBuildConfigString(libs.versions.activityCompose.get()))
        // Only the app's own VLC 4 build (scripts/build_vlc_vulkan.sh) carries the ambientsvp
        // filter; the Maven libVLC fallback cannot interpolate, so SVP is hidden there.
        buildConfigField("boolean", "VLC_SVP", vulkanVlcAar.isFile.toString())
        buildConfigField("String", "LIBVLC_VERSION", quotedBuildConfigString(if (vulkanVlcAar.isFile) "4.0.0-dev-vulkan+c84ef1e" else libs.versions.libvlc.get()))
        buildConfigField("String", "MEDIA3_VERSION", quotedBuildConfigString(libs.versions.media3.get()))
        buildConfigField("String", "COMPOSE_BOM_VERSION", quotedBuildConfigString(libs.versions.composeBom.get()))
        buildConfigField("String", "TV_FOUNDATION_VERSION", quotedBuildConfigString(libs.versions.tvFoundation.get()))
        buildConfigField("String", "TV_MATERIAL_VERSION", quotedBuildConfigString(libs.versions.tvMaterial.get()))
        buildConfigField("String", "COIL_VERSION", quotedBuildConfigString(libs.versions.coil.get()))
        buildConfigField("String", "OKHTTP_VERSION", quotedBuildConfigString(libs.versions.okhttp.get()))
        buildConfigField("String", "RETROFIT_VERSION", quotedBuildConfigString(libs.versions.retrofit.get()))
        buildConfigField("String", "RETROFIT_SERIALIZATION_CONVERTER_VERSION", quotedBuildConfigString(libs.versions.retrofit.get()))
        buildConfigField("String", "SERIALIZATION_VERSION", quotedBuildConfigString(libs.versions.kotlinxSerialization.get()))
        buildConfigField("String", "HILT_VERSION", quotedBuildConfigString(libs.versions.hilt.get()))
        buildConfigField("String", "HILT_NAVIGATION_VERSION", quotedBuildConfigString(libs.versions.hiltNavigationCompose.get()))
        buildConfigField("String", "NAVIGATION_VERSION", quotedBuildConfigString(libs.versions.navigationCompose.get()))
        buildConfigField("String", "COROUTINES_VERSION", quotedBuildConfigString(libs.versions.coroutines.get()))
        buildConfigField("String", "KOTLIN_VERSION", quotedBuildConfigString(libs.versions.kotlin.get()))
        buildConfigField("String", "SECURITY_CRYPTO_VERSION", quotedBuildConfigString(libs.versions.securityCrypto.get()))
        buildConfigField("String", "TIMBER_VERSION", quotedBuildConfigString(libs.versions.timber.get()))
        buildConfigField("String", "AGP_VERSION", quotedBuildConfigString(libs.versions.agp.get()))

        buildConfigField("String", "SMARTTUBE_VERSION", quotedBuildConfigString(externalVersion("SMARTTUBE_VERSION")))
        buildConfigField("String", "SMARTTUBE_REF", quotedBuildConfigString(externalVersion("SMARTTUBE_REF")))
        buildConfigField("String", "MEDIA_SERVICE_CORE_REF", quotedBuildConfigString(externalVersion("SMARTTUBE_MEDIA_SERVICE_REF")))
        buildConfigField("String", "SHARED_MODULES_REF", quotedBuildConfigString(externalVersion("SMARTTUBE_SHARED_MODULES_REF")))
        buildConfigField("String", "MPV_ANDROID_VERSION", quotedBuildConfigString(externalVersion("MPV_ANDROID_VERSION")))
        buildConfigField("String", "MPV_ANDROID_REF", quotedBuildConfigString(externalVersion("MPV_ANDROID_REF")))
        buildConfigField("String", "PROJECTM_VERSION", quotedBuildConfigString(externalVersion("PROJECTM_REF")))
        buildConfigField("String", "SPOTIFY_RUSTY_VERSION", quotedBuildConfigString(externalVersion("SPOTIFY_RUSTY_REF")))
        buildConfigField("String", "SVPFLOW_VERSION", quotedBuildConfigString(externalVersion("SVPFLOW_VERSION")))
        buildConfigField("String", "SVPFLOW_REF", quotedBuildConfigString(externalVersion("SVPFLOW_REF")))
    }

    splits {
        abi {
            val universalOnly = apkAbiMode == "universal"
            isEnable = !universalOnly
            reset()
            selectedAbis.forEach { include(it) }
            isUniversalApk = apkAbiMode == "all"
        }
    }

    sourceSets {
        named("main") {
            jniLibs.directories.add(layout.buildDirectory.dir("generated/vulkan/jniLibs").get().asFile.path)
            jniLibs.directories.add(layout.buildDirectory.dir("generated/localAudio/jniLibs").get().asFile.path)
        }
    }

    // Device audits run against debug; the baseline profile capture runs them against profileCapture.
    testBuildType = providers.gradleProperty("testBuildType").orNull ?: "debug"

    buildTypes {
        getByName("debug") {
            // Optional side-by-side device audit without replacing a differently signed release.
            applicationIdSuffix = providers.gradleProperty("AUDIT_APP_SUFFIX").orNull
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Baseline profile capture (scripts/capture_baseline_profile.sh): release code, but not
        // obfuscated so recorded rules name the real classes (R8 maps them for release), and not
        // debuggable so ART profiles it like the release app. Installs beside the real app.
        create("profileCapture") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            applicationIdSuffix = ".profilecapture"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }



    bundle { language { enableSplit = false } }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    packaging {
        resources {
            // Signed JVM crypto jars contain metadata that Android does not load as code.
            excludes += setOf("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**")
            merges += setOf("META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/LICENSE.md", "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/NOTICE.md")
        }
        jniLibs {
            // Universal APKs also collect dependency ABIs outside splits.abi.include.
            // Ship only ABIs for which app-owned native engines (mpv/projectM/Spotify) are built.
            // Multiple runtimes bundle libc++_shared.so (app/mpv + VLC). Choose explicitly
            // to avoid duplicate packaging; combined-engine compatibility still needs a device test.
            pickFirsts += selectedAbis.map { "**/$it/libc++_shared.so" }

            // AGP's llvm-strip rejects these prebuilt ELFs and would keep their debug info.
            // The release strip below still runs llvm-strip --strip-unneeded on every .so.
            keepDebugSymbols += setOf(
                "**/libandroidx.graphics.path.so",
                "**/libcronet*.so",
            )
        }
    }
}

val buildVulkanProbe = tasks.register<Exec>("buildVulkanProbe") {
    val sdk = loadProjectProperties("local.properties").getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("Android SDK not configured")
    val ndk = File(sdk).resolve("ndk/30.0.16248370")
    inputs.file(rootProject.file("native/vulkan/probe.cpp"))
    inputs.file(rootProject.file("scripts/build_vulkan_probe.sh"))
    inputs.property("abis", selectedAbis)
    outputs.dir(layout.buildDirectory.dir("generated/vulkan/jniLibs"))
    commandLine(listOf("bash", rootProject.file("scripts/build_vulkan_probe.sh").path,
        ndk.path, layout.buildDirectory.dir("generated/vulkan/jniLibs").get().asFile.path) + selectedAbis)
}
tasks.named("preBuild").configure { dependsOn(buildVulkanProbe) }

// Run before Gradle snapshots the decoder inputs, including on a fresh checkout.
val prepareLocalAudio = tasks.register<Exec>("prepareLocalAudio") {
    onlyIf { System.getenv("SKIP_MPV_NATIVE").isNullOrBlank() }
    commandLine(listOf("bash", rootProject.file("scripts/prepare_local_audio.sh").path) + selectedAbis)
}

val buildLocalAudio = tasks.register<Exec>("buildLocalAudio") {
    dependsOn(prepareLocalAudio)
    val sdk = loadProjectProperties("local.properties").getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("Android SDK not configured")
    inputs.file(rootProject.file("native/ambientaudio/local_audio.c"))
    inputs.file(rootProject.file("scripts/build_local_audio.sh"))
    inputs.property("abis", selectedAbis)
    selectedAbis.forEach { abi ->
        inputs.files(listOf("avcodec", "avutil", "swresample").map { rootProject.file("app/src/main/jniLibs/$abi/lib$it.so") })
    }
    val mpvBuildRoot = System.getenv("MPV_BUILD_ROOT")
        ?: rootProject.file(".native/mpv-android").path
    inputs.files(selectedAbis.map { abi ->
        val arch = when (abi) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a" -> "armv7l"
            else -> abi
        }
        fileTree("$mpvBuildRoot/buildscripts/prefix/$arch/include") {
            include("libavcodec/**", "libavutil/**", "libswresample/**")
        }
    })
    outputs.dir(layout.buildDirectory.dir("generated/localAudio/jniLibs"))
    val localAudioNdk = File(sdk).resolve("ndk/30.0.16248370")
    // Like the other native builds: SKIP_MPV_NATIVE or a missing NDK skips the local
    // decoder build instead of failing the whole compile.
    onlyIf { System.getenv("SKIP_MPV_NATIVE").isNullOrBlank() && localAudioNdk.isDirectory }
    commandLine(listOf("bash", rootProject.file("scripts/build_local_audio.sh").path,
        File(sdk).resolve("ndk/30.0.16248370").path,
        layout.buildDirectory.dir("generated/localAudio/jniLibs").get().asFile.path) + selectedAbis)
}
tasks.named("preBuild").configure { dependsOn(buildLocalAudio) }

// FFmpeg is configured with --disable-stripping, J2V8 ships with NDK r18 debug
// info, and SVP keeps its symtab. AGP strips what it can; this pass strips every
// remaining release .so with the NDK llvm-strip. --strip-unneeded keeps dynsym,
// so JNI exports stay. build.sh restores the unstripped jniLibs afterwards.
fun releaseNativeStripTool(): String? {
    val fromProperty = providers.gradleProperty("RELEASE_STRIP_TOOL").orNull?.trim().orEmpty()
    if (fromProperty.isNotEmpty() && File(fromProperty).canExecute()) return fromProperty
    val sdk = loadProjectProperties("local.properties").getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: return null
    val prebuilt = File(sdk).resolve("ndk/${android.ndkVersion}/toolchains/llvm/prebuilt")
    if (!prebuilt.isDirectory) return null
    return prebuilt.walkTopDown()
        .firstOrNull { it.isFile && it.name == "llvm-strip" && it.canExecute() }
        ?.absolutePath
}

tasks.configureEach {
    if (name == "stripReleaseDebugSymbols") {
        outputs.upToDateWhen { false }
        doLast {
            val stripTool = releaseNativeStripTool()
            val nativeRoot = layout.buildDirectory.dir(
                "intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out"
            ).get().asFile
            if (stripTool == null) {
                logger.warn("NDK llvm-strip not found; release native libraries stay unstripped.")
                return@doLast
            }
            if (!nativeRoot.isDirectory) return@doLast
            nativeRoot.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".so") }
                .forEach { library ->
                    val before = library.length()
                    val process = ProcessBuilder(stripTool, "--strip-unneeded", library.absolutePath)
                        .redirectErrorStream(true)
                        .start()
                    val output = process.inputStream.bufferedReader().readText().trim()
                    val exit = process.waitFor()
                    if (exit != 0) {
                        logger.warn("Could not strip ${library.name}: $output")
                    } else if (library.length() < before) {
                        logger.lifecycle(
                            "Stripped ${library.parentFile.name}/${library.name}: $before -> ${library.length()}"
                        )
                    }
                }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        suppressWarnings.set(false)
    }
}

dependencies {
    implementation(libs.smbj)
    implementation(libs.jcifs.ng)
    // QR decoding for the phone's pairing scanner (pure Java, no transitive dependencies).
    implementation(libs.zxing.core)
    // WireGuard X25519 (the platform JCA has it only from API 33); also required by smbj/jcifs-ng.
    implementation(libs.bouncycastle.prov)
    // SFTP file sources. bcpkix uses the published 1.85 release; bcprov retains its 1.85.2 patch.
    implementation(libs.sshj)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.session)

    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization.converter)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)


    implementation(libs.androidx.security.crypto)

    implementation(libs.androidx.tvprovider)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.android.svg)

    if (vulkanVlcAar.isFile) {
        implementation(files(vulkanVlcAar))
        implementation("androidx.legacy:legacy-support-v4:1.0.0")
    } else {
        implementation(libs.libvlc.all)
    }

    implementation(libs.timber)

    // SmartTube MediaServiceCore is fetched/pinned by scripts/fetch_smarttube_engine.sh.
    // The UI talks to it through SmartTubeEngine so upstream API changes stay isolated.
    if (rootProject.findProject(":youtubeapi") != null) {
        implementation(project(":youtubeapi"))
    }

    testImplementation(libs.junit4)
    // Compose UI focus tests for the TV sidebar (run on the JVM through Robolectric).
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.17")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // SmartTube types used by ReactiveContentTest are compileOnly/transitive at runtime only.
    testImplementation("io.reactivex.rxjava2:rxjava:2.2.21")
}

// Optional post-build ADB deployment for Android TV devices on the LAN.
// Examples:
//   ./gradlew :app:packageRelease -PADB_DEPLOY=true -PADB_DEVICE=192.168.1.50 -PADB_APK=auto
//   ./gradlew :app:deployReleaseAdb -PADB_DEVICE=192.168.1.50:5555 -PADB_APK=universal
// ADB_APK: auto | universal | arm64-v8a | armeabi-v7a | x86 | x86_64
val adbDeployEnabled = providers.gradleProperty("ADB_DEPLOY")
    .orElse(providers.environmentVariable("ADB_DEPLOY"))
    .map { it.equals("true", ignoreCase = true) || it == "1" }
    .orElse(false)

val adbDevice = providers.gradleProperty("ADB_DEVICE")
    .orElse(providers.environmentVariable("ADB_DEVICE"))

val adbApkMode = providers.gradleProperty("ADB_APK")
    .orElse(providers.environmentVariable("ADB_APK"))
    .orElse("auto")

fun normalizeAdbTarget(raw: String): String {
    val value = raw.trim()
    if (value.isBlank()) return value
    // Plain IPv4/hostname defaults to the standard wireless-debugging port.
    // Explicit host:port and bracketed IPv6 are left unchanged.
    return if (!value.contains(':')) "$value:5555" else value
}

fun runAdb(vararg args: String): Pair<Int, String> = try {
    val process = ProcessBuilder(listOf("adb") + args)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    process.waitFor() to output
} catch (t: Throwable) {
    -1 to (t.message ?: t::class.java.simpleName)
}

fun findReleaseApk(mode: String, detectedAbi: String? = null): File? {
    val releaseDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
    if (!releaseDir.isDirectory) return null

    val requested = when (mode.lowercase()) {
        "auto" -> when {
            detectedAbi?.contains("arm64-v8a", ignoreCase = true) == true -> "arm64-v8a"
            detectedAbi?.contains("armeabi-v7a", ignoreCase = true) == true -> "armeabi-v7a"
            detectedAbi?.contains("x86_64", ignoreCase = true) == true -> "x86_64"
            detectedAbi?.equals("x86", ignoreCase = true) == true ||
                detectedAbi?.contains(",x86", ignoreCase = true) == true -> "x86"
            else -> "universal"
        }
        "arm64", "arm64-v8a" -> "arm64-v8a"
        "arm", "armv7", "armeabi-v7a" -> "armeabi-v7a"
        "x86_64", "x86-64" -> "x86_64"
        "x86" -> "x86"
        else -> "universal"
    }

    val apks = releaseDir.walkTopDown()
        .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
        .toList()

    fun score(file: File): Int {
        val name = file.name.lowercase()
        return when (requested) {
            "arm64-v8a" -> when {
                "arm64-v8a" in name || "arm64" in name -> 100
                else -> 0
            }
            "armeabi-v7a" -> when {
                "armeabi-v7a" in name || "armeabi" in name -> 100
                else -> 0
            }
            "x86_64" -> when {
                "x86_64" in name || "x86-64" in name -> 100
                else -> 0
            }
            "x86" -> when {
                "x86_64" in name || "x86-64" in name -> 0
                "x86" in name -> 100
                else -> 0
            }
            else -> when {
                "universal" in name -> 100
                "arm64" !in name && "armeabi" !in name && "x86" !in name -> 50
                else -> 0
            }
        }
    }

    return apks.map { it to score(it) }
        .filter { it.second > 0 }
        .maxByOrNull { it.second }
        ?.first
}

fun deployReleaseApkToAdb() {
    val configuredTarget = adbDevice.orNull?.trim().orEmpty()
    if (configuredTarget.isBlank()) {
        logger.lifecycle("ADB deploy skipped: no ADB_DEVICE configured.")
        return
    }

    val target = normalizeAdbTarget(configuredTarget)
    val (connectCode, connectOutput) = runAdb("connect", target)
    if (connectCode != 0 || (!connectOutput.contains("connected", true) && !connectOutput.contains("already", true))) {
        logger.lifecycle("ADB deploy skipped: device $target is not reachable (${connectOutput.ifBlank { "adb connect failed" }}).")
        return
    }

    val (stateCode, stateOutput) = runAdb("-s", target, "get-state")
    if (stateCode != 0 || !stateOutput.contains("device", true)) {
        logger.lifecycle("ADB deploy skipped: $target is not in device state (${stateOutput.ifBlank { "unknown state" }}).")
        return
    }

    val mode = adbApkMode.get().trim().ifBlank { "auto" }
    val detectedAbi = if (mode.equals("auto", true)) {
        val (abiCode, abiOutput) = runAdb("-s", target, "shell", "getprop", "ro.product.cpu.abi")
        if (abiCode == 0) abiOutput.trim() else ""
    } else null

    val apk = findReleaseApk(mode, detectedAbi)
    if (apk == null) {
        logger.lifecycle("ADB deploy skipped: no release APK found for mode '$mode' (device ABI: ${detectedAbi ?: "not queried"}).")
        return
    }

    logger.lifecycle("ADB deploy: installing ${apk.name} to $target${detectedAbi?.takeIf { it.isNotBlank() }?.let { " (ABI $it)" } ?: ""} ...")
    val (installCode, installOutput) = runAdb("-s", target, "install", "-r", apk.absolutePath)
    if (installCode == 0 && installOutput.contains("Success", true)) {
        logger.lifecycle("ADB deploy: installed ${apk.name} successfully.")
        // adb install ignores the APK's baseline profile until the device's idle dexopt (days on a
        // TV box), so menus stutter meanwhile. Write the profile and compile with it right away.
        val pkg = "com.ambient.tv"
        runAdb("-s", target, "shell", "am", "broadcast", "-a", "androidx.profileinstaller.action.INSTALL_PROFILE",
            "-n", "$pkg/androidx.profileinstaller.ProfileInstallReceiver")
        val (compileCode, compileOutput) = runAdb("-s", target, "shell", "cmd", "package", "compile", "-f", "-m", "speed-profile", pkg)
        if (compileCode == 0 && compileOutput.contains("Success", true)) logger.lifecycle("ADB deploy: compiled with the baseline profile.")
        else logger.lifecycle("ADB deploy warning: profile compilation failed (${compileOutput.ifBlank { "no output" }}); the app works, it may stutter until the device compiles it.")
    } else {
        // Deployment is deliberately non-fatal so a missing/offline TV never breaks the build.
        logger.lifecycle("ADB deploy warning: install failed but build remains successful. ${installOutput.ifBlank { "adb install failed" }}")
    }
}

val deployReleaseAdb = tasks.register("deployReleaseAdb") {
    group = "deployment"
    description = "Builds release APKs and optionally installs universal/ABI-matching APK to ADB_DEVICE without failing the build."
    dependsOn("packageRelease")
    doLast { deployReleaseApkToAdb() }
}

// Opt-in automatic deployment after a normal release build.
// Disabled by default; enable with -PADB_DEPLOY=true or ADB_DEPLOY=true.
// AGP registers variant tasks lazily. Do not require packageRelease to exist
// during build-script configuration (AGP 8.11+ may register it later).
tasks.configureEach {
    if (name == "packageRelease") {
        doLast {
            if (adbDeployEnabled.get()) deployReleaseApkToAdb()
        }
    }
}


// Convenience entry points for building exactly one ABI-specific APK.
// These launch an isolated Gradle invocation with APK_ABI set, so they do not
// interfere with the normal packageRelease task or with each other.
fun registerAbiBuildTask(taskName: String, abiMode: String, descriptionText: String) {
    tasks.register<org.gradle.api.tasks.GradleBuild>(taskName) {
        group = "build"
        description = descriptionText
        tasks = listOf(":app:packageRelease")
        // Pin the already-incremented code so the nested build does not count again.
        startParameter.projectProperties = gradle.startParameter.projectProperties +
            ("APK_ABI" to abiMode) + ("versionCode" to appVersionCode.toString())
    }
}

fun registerAbiDeployTask(taskName: String, abiMode: String, adbMode: String, descriptionText: String) {
    tasks.register<org.gradle.api.tasks.GradleBuild>(taskName) {
        group = "deployment"
        description = descriptionText
        tasks = listOf(":app:packageRelease")
        startParameter.projectProperties = gradle.startParameter.projectProperties + mapOf(
            "APK_ABI" to abiMode,
            "ADB_DEPLOY" to "true",
            "ADB_APK" to adbMode,
        )
    }
}

registerAbiBuildTask("assembleUniversalRelease", "universal", "Builds only the universal (all ABI) release APK.")
registerAbiBuildTask("assembleArm64Release", "arm64-v8a", "Builds only the arm64-v8a release APK.")
registerAbiBuildTask("assembleArmv7Release", "armeabi-v7a", "Builds only the armeabi-v7a release APK.")
registerAbiBuildTask("assembleX86Release", "x86", "Builds only the x86 release APK.")
registerAbiBuildTask("assembleX86_64Release", "x86_64", "Builds only the x86_64 release APK.")

registerAbiDeployTask("deployUniversalReleaseAdb", "universal", "universal", "Builds the universal APK and non-fatally deploys it to ADB_DEVICE.")
registerAbiDeployTask("deployArm64ReleaseAdb", "arm64-v8a", "arm64-v8a", "Builds the arm64-v8a APK and non-fatally deploys it to ADB_DEVICE.")
registerAbiDeployTask("deployArmv7ReleaseAdb", "armeabi-v7a", "armeabi-v7a", "Builds the armeabi-v7a APK and non-fatally deploys it to ADB_DEVICE.")
registerAbiDeployTask("deployX86ReleaseAdb", "x86", "x86", "Builds the x86 APK and non-fatally deploys it to ADB_DEVICE.")
registerAbiDeployTask("deployX86_64ReleaseAdb", "x86_64", "x86_64", "Builds the x86_64 APK and non-fatally deploys it to ADB_DEVICE.")

val verifyAppNativeMediaInputs = tasks.register("verifyAppNativeMediaInputs") {
    group = "verification"
    description = "Fail fast when app-owned Spotify/projectM/SVP native libraries have not been prepared."
    doLast {
        val required = selectedAbis.flatMap { abi ->
            listOf(
                rootProject.file("app/src/main/jniLibs/$abi/libspotify_receiver_core.so"),
                rootProject.file("app/src/main/jniLibs/$abi/libprojectM-4.so"),
                rootProject.file("app/src/main/jniLibs/$abi/libambientprojectm.so"),
                rootProject.file("app/src/main/jniLibs/$abi/libambientsvp.so"),
            )
        }
        val missing = required.filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing app native media libraries:\n" + missing.joinToString("\n") { " - ${it.relativeTo(rootProject.projectDir)}" } +
                    "\nRun ./build.sh (or the Spotify/projectM/SVP native build scripts) before invoking Android packaging directly."
            )
        }
    }
}

tasks.configureEach {
    val packagingTask = name.startsWith("assemble", ignoreCase = true) ||
        name.startsWith("package", ignoreCase = true) ||
        name.startsWith("install", ignoreCase = true)
    // Unit-test and resource packaging ("packageDebugUnitTestForUnitTest", "packageDebugResources")
    // never ship native libraries; only APK/bundle assembly needs them.
    val testOrResources = "UnitTest" in name || "AndroidTest" in name || name.endsWith("Resources") || name.endsWith("Assets")
    if (packagingTask && !testOrResources && name != verifyAppNativeMediaInputs.name) dependsOn(verifyAppNativeMediaInputs)
}

// Raw Gradle builds can already be signed by a local signing setup. build.sh signs/renames
// unsigned outputs afterwards and regenerates dist/update.json from those final bytes.
val generateUpdateManifest = tasks.register<Exec>("generateUpdateManifest") {
    group = "distribution"
    description = "Generates update.json from this release's signed APKs (deferred when unsigned)."
    dependsOn("packageRelease")
    val output = layout.buildDirectory.file("outputs/update/update.json")
    doFirst {
        val tools = File(androidComponents.sdkComponents.sdkDirectory.get().asFile, "build-tools/${android.buildToolsVersion}")
        val expected = if (apkAbiMode == "universal") listOf("universal")
            else selectedAbis + if (apkAbiMode == "all") listOf("universal") else emptyList()
        commandLine("python3", rootProject.file("scripts/generate_update_manifest.py"),
            "--output", output.get().asFile, "--aapt2", File(tools, "aapt2"),
            "--apksigner", File(tools, "apksigner"), "--expected-abis", expected.joinToString(","),
            "--gradle-metadata", layout.buildDirectory.file("outputs/apk/release/output-metadata.json").get().asFile,
            "--skip-unsigned")
    }
}
tasks.matching { it.name == "packageRelease" }.configureEach {
    doFirst { layout.buildDirectory.file("outputs/update/update.json").get().asFile.delete() }
    finalizedBy(generateUpdateManifest)
}
generateUpdateManifest.configure { onlyIf { tasks.named("packageRelease").get().state.failure == null } }
