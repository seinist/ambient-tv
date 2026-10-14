plugins { alias(libs.plugins.android.library) }

val vlcSource = file(providers.gradleProperty("VLC_SOURCE").orElse("../../.native/libvlcjni").get())
android {
    namespace = "org.videolan"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    buildFeatures { buildConfig = true }
    // AGP 9.2+: use the sourceSets {} block (new DSL type). getByName() hits the
    // legacy AndroidLibrarySourceSet ClassCastException on library modules.
    sourceSets {
        named("main") {
            manifest.srcFile(vlcSource.resolve("libvlc/src/main/AndroidManifest.xml"))
            java.directories.add(vlcSource.resolve("libvlc/src/main/java").path)
            res.directories.add(vlcSource.resolve("libvlc/src/main/res").path)
            assets.directories.add(layout.buildDirectory.dir("generated/assets").get().asFile.path)
            jniLibs.directories.add(vlcSource.resolve("libvlc/jni/libs").path)
        }
    }
    buildTypes.getByName("release") { consumerProguardFiles("consumer-rules.pro") }
}
val copyVlcAssets = tasks.register<Copy>("copyVlcAssets") {
    from(vlcSource.resolve("vlc/share")) {
        include("hrtfs/**", "lua/playlist/**", "lua/modules/**", "lua/meta/**")
        exclude("**/*.txt")
    }
    into(layout.buildDirectory.dir("generated/assets"))
}
tasks.named("preBuild").configure { dependsOn(copyVlcAssets) }
dependencies {
    implementation("androidx.annotation:annotation:1.11.0")
    implementation("androidx.legacy:legacy-support-v4:1.0.0")
}
