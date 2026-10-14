plugins {
    id("com.android.library")
}

val upstream = rootProject.file("third_party/smarttube/SharedModules/commons-io-2.8.0")

android {
    namespace = "org.apache.commons.io.android"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
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
        }
    }
    lint { abortOnError = false }
}


dependencies {
    implementation(fileTree(mapOf("dir" to upstream.resolve("libs"), "include" to listOf("*.jar"))))
}


tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    sourceCompatibility = JavaVersion.VERSION_17.toString()
    targetCompatibility = JavaVersion.VERSION_17.toString()
}
