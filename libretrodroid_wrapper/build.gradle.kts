// LibretroDroid (Argosy launcher fork, GPL-3.0) built from third_party/libretrodroid, which
// scripts/fetch_libretrodroid.sh fetches at the pinned commit and patches.
plugins {
    id("com.android.library")
}

val upstream = rootProject.file("third_party/libretrodroid")

// Candidate release builds are opt-in until the same ABI and runtime benchmarks pass.
// The baseline release flags and all debug builds remain unchanged.
val nativeProfile = providers.gradleProperty("LIBRETRO_NATIVE_PROFILE")
    .orElse(providers.environmentVariable("LIBRETRO_NATIVE_PROFILE"))
    .orElse("baseline")
    .get()
val nativeProfiles = setOf("baseline", "o3", "thinlto", "o3-thinlto")
require(nativeProfile in nativeProfiles) { "Unknown LIBRETRO_NATIVE_PROFILE: $nativeProfile" }
require(nativeProfile == "baseline" || providers.gradleProperty("LIBRETRO_NATIVE_EXPERIMENT").orNull == "true") {
    "Native performance candidates require -PLIBRETRO_NATIVE_EXPERIMENT=true"
}

// Same ABI selection as :app (APK_ABI); one ABI keeps CI and single-ABI builds short.
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

android {
    namespace = "com.swordfish.libretrodroid"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = 29
        consumerProguardFiles(upstream.resolve("consumer-rules.pro"))
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
        ndk {
            abiFilters += selectedAbis
        }
    }

    externalNativeBuild {
        cmake {
            path = upstream.resolve("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            externalNativeBuild {
                cmake {
                    // AGP uses RelWithDebInfo for release. Strip DWARF from every shared library at link time.
                    val optimize = if (nativeProfile.startsWith("o3")) "-O3" else "-O2"
                    val lto = if (nativeProfile.endsWith("thinlto")) " -flto=thin" else ""
                    arguments += "-DCMAKE_SHARED_LINKER_FLAGS_RELWITHDEBINFO=-Wl,--strip-debug$lto"
                    if (nativeProfile != "baseline") {
                        arguments += "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON"
                        for (language in listOf("C", "CXX")) {
                            arguments += "-DCMAKE_${language}_FLAGS_RELWITHDEBINFO=$optimize -g -DNDEBUG -DAMBIENT_CORE_PERF_EXPERIMENT=1$lto"
                            arguments += "-DCMAKE_${language}_FLAGS_RELEASE=$optimize -DNDEBUG -DAMBIENT_CORE_PERF_EXPERIMENT=1$lto"
                        }
                        if (lto.isNotEmpty()) {
                            arguments += "-DCMAKE_SHARED_LINKER_FLAGS_RELEASE=$lto"
                            arguments += "-DCMAKE_MODULE_LINKER_FLAGS=$lto"
                            arguments += "-DCMAKE_EXE_LINKER_FLAGS=$lto"
                        }
                    }
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        named("main") {
            // Own manifest: the upstream one still carries a package attribute.
            manifest.srcFile("src/main/AndroidManifest.xml")
            java.directories.add(upstream.resolve("src/main/java").path)
            kotlin.directories.add(upstream.resolve("src/main/java").path)
            res.directories.add(upstream.resolve("src/main/res").path)
        }
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(false)
    }
}

dependencies {
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit4)
}
