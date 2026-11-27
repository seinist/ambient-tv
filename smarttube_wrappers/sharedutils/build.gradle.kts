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
    implementation(libs.commons.io)

    implementation(libs.smarttube.appcompat)
    implementation(libs.smarttube.preference)
    implementation(libs.smarttube.core.ktx)

    implementation(libs.okhttp.core)
    implementation(libs.okhttp.dns.over.https)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.okhttp.brotli)
    implementation(libs.okhttp.profiler) {
        exclude(group = "com.squareup.okhttp3", module = "okhttp")
    }

    implementation(libs.gson)
    implementation(libs.rxandroid2)
    implementation(libs.rxjava2)
    implementation(libs.disklrucache)
    implementation(libs.netcipher)
    implementation(libs.smarttube.dnsjava)
    // Android-safe SLF4J 1.x binding required by dnsjava during R8.
    implementation(libs.smarttube.slf4j)
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
