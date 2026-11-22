// LibretroDroid (Argosy launcher fork, GPL-3.0) built from third_party/libretrodroid, which
// scripts/fetch_libretrodroid.sh fetches at the pinned commit and patches.
plugins {
    id("com.android.library")
}

val upstream = rootProject.file("third_party/libretrodroid")

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
                    arguments += "-DCMAKE_SHARED_LINKER_FLAGS_RELWITHDEBINFO=-Wl,--strip-debug"
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
