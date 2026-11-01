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
