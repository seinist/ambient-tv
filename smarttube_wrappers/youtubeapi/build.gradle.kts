plugins {
    id("com.android.library")
}

val upstream = rootProject.file("third_party/smarttube/MediaServiceCore/youtubeapi")

android {
    namespace = "com.liskovsoft.youtubeapi"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        consumerProguardFiles(upstream.resolve("proguard-rules.pro"))
    }

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
            kotlin.directories.add(upstream.resolve("src/main/java").path)
            res.directories.add(upstream.resolve("src/main/res").path)
            assets.directories.add(upstream.resolve("src/main/assets").path)
            jniLibs.directories.add(upstream.resolve("src/main/jniLibs").path)
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
    implementation(libs.okhttp.core)
    implementation(fileTree(mapOf("dir" to upstream.resolve("libs"), "include" to listOf("*.jar"))))
    implementation(project(":commons-io-2.8.0"))
    implementation(project(":sharedutils"))
    implementation(project(":mediaserviceinterfaces"))
    implementation(project(":j2v8"))

    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.jayway.jsonpath:json-path:2.4.0")
    constraints {
        // json-path 2.4.0 (pinned to match SmartTube upstream) pulls json-smart 2.3, which has
        // known denial-of-service bugs on malformed or deeply nested JSON (e.g. CVE-2023-1370).
        // Raise only json-smart; json-path keeps its upstream version and 2.x API.
        implementation("net.minidev:json-smart:2.6.0") {
            because("json-smart 2.3 from json-path 2.4.0 has known DoS vulnerabilities")
        }
    }
    implementation("com.google.net.cronet:cronet-okhttp:0.1.0")
    implementation("org.chromium.net:cronet-api:143.7445.0")
    implementation("io.reactivex.rxjava2:rxandroid:2.1.1")
    implementation("io.reactivex.rxjava2:rxjava:2.2.21")
    implementation("androidx.annotation:annotation:1.11.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.github.florianingerl.util:regex:1.1.11")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.grack:nanojson:1.7")
    testImplementation(libs.junit4)
}


// Hard task-level alignment. This is intentionally redundant with android.compileOptions:
// Gradle's JVM compatibility check reads the concrete Java/Kotlin compile tasks.
tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    sourceCompatibility = JavaVersion.VERSION_17.toString()
    targetCompatibility = JavaVersion.VERSION_17.toString()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    jvmTargetValidationMode.set(org.jetbrains.kotlin.gradle.dsl.jvm.JvmTargetValidationMode.ERROR)
}
