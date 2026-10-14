plugins {
    id("com.android.library")
}

val upstream = rootProject.file("third_party/smarttube/SharedModules/sharedutils")

android {
    namespace = "com.liskovsoft.sharedutils"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        consumerProguardFiles(upstream.resolve("proguard-rules.pro"))
    }

    buildFeatures {
        buildConfig = true
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
    implementation(fileTree(mapOf("dir" to upstream.resolve("libs"), "include" to listOf("*.jar"))))
    implementation(project(":commons-io-2.8.0"))

    implementation("androidx.appcompat:appcompat:1.2.0")
    implementation("androidx.preference:preference:1.1.0")
    implementation("androidx.core:core-ktx:1.12.0")

    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:5.5.0")
    implementation("com.squareup.okhttp3:logging-interceptor:5.5.0")
    implementation("com.squareup.okhttp3:okhttp-brotli:5.5.0")
    implementation("com.localebro:okhttpprofiler:1.0.8") {
        exclude(group = "com.squareup.okhttp3", module = "okhttp")
    }

    implementation("org.chromium.net:cronet-api:143.7445.0")
    implementation("org.chromium.net:cronet-embedded:143.7445.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("io.reactivex.rxjava2:rxandroid:2.1.1")
    implementation("io.reactivex.rxjava2:rxjava:2.2.21")
    implementation("com.jakewharton:disklrucache:2.0.2")
    implementation("info.guardianproject.netcipher:netcipher:2.1.0")
    implementation("dnsjava:dnsjava:2.1.9")
    // Android-safe SLF4J 1.x binding required by dnsjava during R8.
    implementation("org.slf4j:slf4j-nop:1.7.36")
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
