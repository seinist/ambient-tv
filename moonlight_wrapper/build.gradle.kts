// GPL-3.0 Moonlight GameStream protocol module, compiled from the exact upstream release pinned in
// gradle/external-versions.properties (./build.sh libs-update moonlight moves it after checking the
// reviewed patches). No upstream launcher screens, resources or activities are compiled.
plugins { id("com.android.library") }

val upstream = rootProject.file("third_party/moonlight-android/app")
val sourceRoot = upstream.resolve("src/main/java")
val nativeRoot = upstream.resolve("src/main/jni")
require(sourceRoot.resolve("com/limelight/nvstream/http/NvHTTP.java").isFile) {
    "Moonlight native sources missing. Run: bash scripts/fetch_moonlight_sources.sh"
}
require(nativeRoot.resolve("moonlight-core/opus/source/CMakeLists.txt").isFile) {
    "Pinned Opus source missing. Re-run bash scripts/fetch_moonlight_sources.sh"
}
require(nativeRoot.resolve("moonlight-core/moonlight-common-c/enet/include/enet/enet.h").isFile) {
    "Pinned modified ENet source missing. Re-fetch the release recursively."
}

val apkAbiMode = providers.gradleProperty("APK_ABI")
    .orElse(providers.environmentVariable("APK_ABI")).orElse("all").get().trim().lowercase()
val abis = when (apkAbiMode) {
    "arm64", "arm64-v8a" -> listOf("arm64-v8a")
    "arm", "armv7", "armeabi-v7a" -> listOf("armeabi-v7a")
    "x86" -> listOf("x86")
    "x86_64", "x86-64" -> listOf("x86_64")
    else -> listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
}
// The release's own decoder helper parses and rewrites H.264 SPS with jcodec. Take the exact
// version from the pinned upstream build file, so ./build.sh libs-update moonlight keeps it in step.
val upstreamJcodec = Regex("""org\.jcodec:jcodec:([0-9][0-9.]*)""")
    .find(upstream.resolve("build.gradle").readText())?.groupValues?.get(1)
    ?: error("Pinned Moonlight build.gradle no longer declares org.jcodec:jcodec; review the update")

for (abi in abis) {
    require(nativeRoot.resolve("moonlight-core/opus/$abi/libopus.a").isFile) {
        "Missing pinned upstream Opus static library for $abi"
    }
    require(nativeRoot.resolve("moonlight-core/openssl/$abi/libcrypto.a").isFile) {
        "Missing pinned upstream OpenSSL static library for $abi"
    }
}

// Build the static archive from verified source before ndk-build configures or links.
val opusRoot = nativeRoot.resolve("moonlight-core/opus")
val opusScript = rootProject.file("scripts/build_moonlight_opus.sh")
val opusOutputs = abis.map { opusRoot.resolve("ambient-built/$it/libopus.a") }
val buildPinnedOpus = tasks.register("buildPinnedMoonlightOpus") {
    inputs.file(opusScript)
    inputs.dir(opusRoot.resolve("source"))
    inputs.property("abis", abis)
    inputs.property("ndk", "30.0.16248370")
    outputs.files(opusOutputs)
    val sdk = androidComponents.sdkComponents.sdkDirectory
    doLast {
        val ndk = sdk.get().asFile.resolve("ndk/30.0.16248370")
        abis.forEach { abi ->
            val process = ProcessBuilder("bash", opusScript.absolutePath,
                opusRoot.absolutePath, ndk.absolutePath, abi)
                .directory(rootProject.projectDir).inheritIO().start()
            check(process.waitFor() == 0) { "Pinned Opus build failed for $abi" }
        }
    }
}
tasks.configureEach {
    if (name == "preBuild" || name.startsWith("configureNdkBuild") ||
        name.startsWith("buildNdkBuild") || name.startsWith("externalNativeBuild")) {
        dependsOn(buildPinnedOpus)
    }
}

android {
    namespace = "com.limelight"
    compileSdk = 37
    ndkVersion = "30.0.16248370"
    buildFeatures { buildConfig = true }
    defaultConfig {
        minSdk = 29
        // JNI calls back into MoonBridge by name; R8 must keep that contract in release APKs.
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += abis }
        externalNativeBuild {
            ndkBuild { arguments += listOf("APP_PLATFORM=android-29", "APP_SUPPORT_FLEXIBLE_PAGE_SIZES=true") }
        }
    }
    // Release-only native tuning and ThinLTO live in the audited Android.mk patch.
    externalNativeBuild {
        ndkBuild { path = nativeRoot.resolve("Android.mk") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets {
        named("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            // The fetch script stages a protocol-only Java tree. AGP 9's Java source
            // set no longer supports Ant-style include filters here.
            java.directories.add(upstream.resolve("protocol-only-src").path)
            assets.directories.add(upstream.resolve("protocol-only-assets").path)
        }
    }
    lint { abortOnError = false }
}
dependencies {
    // H264Utils/SeqParameterSet are used by Ambient TV's renderer for the upstream SPS fixups.
    api("org.jcodec:jcodec:$upstreamJcodec")
    implementation(libs.okhttp.core)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
}
