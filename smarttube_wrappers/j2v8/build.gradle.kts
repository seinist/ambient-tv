plugins {
    id("com.android.library")
}

val upstream = rootProject.file("third_party/smarttube/SharedModules/j2v8")
val sourceTestDir = providers.gradleProperty("J2V8_SOURCE_TEST_DIR").orNull?.let { rootProject.file(it) }
if (sourceTestDir != null) {
    check(listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").all {
        sourceTestDir.resolve("$it/libj2v8.so").let { binary -> binary.isFile && binary.length() > 0 }
    }) { "J2V8 experimental source-test directory must contain all four validated ABIs" }
}

// A source-test override must never be used for a release artifact.
androidComponents.beforeVariants(androidComponents.selector().withBuildType("release")) { variant ->
    if (sourceTestDir != null) variant.enable = false
}

android {
    namespace = "com.eclipsesource.v8"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP 9.2+: use the sourceSets {} block (new DSL type). getByName() hits the
    // legacy AndroidLibrarySourceSet ClassCastException on library modules.
    sourceSets {
        named("main") {
            manifest.srcFile(upstream.resolve("src/main/AndroidManifest.xml"))
            java.directories.add(upstream.resolve("src/main/java").path)
            res.directories.add(upstream.resolve("src/main/res").path)
            assets.directories.add(upstream.resolve("src/main/assets").path)
            jniLibs.directories.add((sourceTestDir ?: upstream.resolve("src/main/jniLibs")).path)
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to upstream.resolve("libs"), "include" to listOf("*.jar"))))
    implementation(libs.androidx.annotation)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    sourceCompatibility = JavaVersion.VERSION_17.toString()
    targetCompatibility = JavaVersion.VERSION_17.toString()
}
