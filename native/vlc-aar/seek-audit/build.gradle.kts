plugins { id("com.android.application") version "9.4.1" }
val repo = rootDir.resolve("../../..").canonicalFile
val aar = repo.resolve("native/vlc-aar/build/experimental/libvlc-vulkan-ahb-experimental.aar")
require(aar.isFile) { "Build the experimental VLC AAR first" }
val auditAbi = providers.gradleProperty("AUDIT_ABI").orElse("arm64-v8a").get()
require(auditAbi in listOf("arm64-v8a","x86_64"))
val syncSources = tasks.register<Sync>("syncProductionReceiver") {
    from(repo.resolve("app/src/main/java/com/ambient/tv/ui/player/backend")) {
        include("VlcTimedMetadata.kt", "VlcTimedMetadataReceiver.kt")
    }
    from(repo.resolve("app/src/androidTest/java/com/ambient/tv/ui/player")) {
        include("VlcMetadataSocketAudit.kt")
    }
    into(layout.buildDirectory.dir("generated/receiver"))
}
android {
    namespace = "com.ambient.vlcseekaudit"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.ambient.vlcseekaudit"
        minSdk = 29
        targetSdk = 37
        ndk { abiFilters.add(auditAbi) }
    }
    packaging.jniLibs.keepDebugSymbols.add("**/libvlc*.so")
    sourceSets.getByName("main").kotlin.directories.add(layout.buildDirectory.dir("generated/receiver").get().asFile.path)
}
tasks.named("preBuild").configure { dependsOn(syncSources) }
dependencies {
    implementation(files(aar))
    implementation("androidx.annotation:annotation:1.11.0")
    implementation("androidx.legacy:legacy-support-v4:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
