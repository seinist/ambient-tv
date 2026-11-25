// AMBIENT_TV_SETTINGS_KTS_V68
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
        // Only needed in GPL-enabled Monogram builds; do not resolve the full
        // Monogram project or its unrelated UI/database dependencies.
        maven(url = "https://raw.githubusercontent.com/rustls/rustls-platform-verifier/maven-archive/android-release-support/maven/") {
            content { includeGroup("org.rustls") }
        }
    }
}

rootProject.name = "ambient-tv"
rootProject.buildFileName = "build.gradle.kts"
include(":app")
project(":app").buildFileName = "build.gradle.kts"

// Compile the official GPL-3.0 Moonlight GameStream core only when explicitly requested.
// All four ABIs are prepared by bash scripts/fetch_moonlight_sources.sh.
// Normal builds do not resolve or package this native dependency.
// "1" and "true" both opt in, like MONOGRAM_NATIVE; ./build.sh passes the environment variable.
val moonlightNative = providers.gradleProperty("MOONLIGHT_NATIVE")
    .orElse(providers.environmentVariable("MOONLIGHT_NATIVE")).orElse("0").get()
    .let { it == "1" || it.equals("true", ignoreCase = true) }
if (moonlightNative) {
    require(file("third_party/moonlight-android/app/src/main/java/com/limelight/nvstream/http/NvHTTP.java").isFile) {
        "Moonlight upstream sources absent. Run: bash scripts/fetch_moonlight_sources.sh"
    }
    include(":moonlight_native")
    project(":moonlight_native").projectDir = file("moonlight_wrapper")
    project(":moonlight_native").buildFileName = "build.gradle.kts"
}

// Games: LibretroDroid (scripts/fetch_libretrodroid.sh). Required by :app.
if (file("third_party/libretrodroid/src/main/cpp/CMakeLists.txt").isFile) {
    include(":libretrodroid")
    project(":libretrodroid").projectDir = file("libretrodroid_wrapper")
    project(":libretrodroid").buildFileName = "build.gradle.kts"
}

val smartRoot = file("third_party/smarttube")
val msc = smartRoot.resolve("MediaServiceCore")
val shared = smartRoot.resolve("SharedModules")
val wrappers = file("smarttube_wrappers")
if (msc.resolve("youtubeapi/src/main").isDirectory && shared.resolve("sharedutils/src/main").isDirectory) {
    include(":sharedutils", ":j2v8", ":mediaserviceinterfaces", ":youtubeapi")

    project(":sharedutils").projectDir = wrappers.resolve("sharedutils")
    project(":sharedutils").buildFileName = "build.gradle.kts"

    project(":j2v8").projectDir = wrappers.resolve("j2v8")
    project(":j2v8").buildFileName = "build.gradle.kts"

    project(":mediaserviceinterfaces").projectDir = wrappers.resolve("mediaserviceinterfaces")
    project(":mediaserviceinterfaces").buildFileName = "build.gradle.kts"

    project(":youtubeapi").projectDir = wrappers.resolve("youtubeapi")
    project(":youtubeapi").buildFileName = "build.gradle.kts"
}
