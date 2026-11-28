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
    implementation(libs.commons.io)
    implementation(project(":sharedutils"))
    implementation(project(":mediaserviceinterfaces"))
    implementation(project(":j2v8"))

    implementation(libs.retrofit.core)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.gson)
    implementation(libs.smarttube.json.path)
    constraints {
        // Keep json-smart independently security-updateable, including for JSON Path 3.x.
        implementation(libs.json.smart) {
            because("Prevent vulnerable transitive json-smart versions (e.g. CVE-2023-1370)")
        }
    }
    implementation(libs.rxandroid2)
    implementation(libs.rxjava2)
    implementation(libs.androidx.annotation)
    // Upstream Java PoToken and random-string sources use JSR-305 nullability annotations.
    compileOnly(libs.jsr305)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.smarttube.regex)
    implementation(libs.smarttube.webkit)
    implementation(libs.nanojson)
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

// Keep CI failures diagnosable without downloading test-report artifacts.
tasks.withType<Test>().configureEach {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
    }
}
