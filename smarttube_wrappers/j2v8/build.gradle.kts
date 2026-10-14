plugins {
    id("com.android.library")
}

val upstream = rootProject.file("third_party/smarttube/SharedModules/j2v8")

android {
    namespace = "com.eclipsesource.v8"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
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
            jniLibs.directories.add(upstream.resolve("src/main/jniLibs").path)
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to upstream.resolve("libs"), "include" to listOf("*.jar"))))
    implementation("androidx.annotation:annotation:1.11.0")
}

tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    sourceCompatibility = JavaVersion.VERSION_17.toString()
    targetCompatibility = JavaVersion.VERSION_17.toString()
}
