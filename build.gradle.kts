// AMBIENT_TV_ROOT_BUILD_V036
buildscript {
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21") }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

subprojects {
    configurations.configureEach {
        resolutionStrategy.force(
            "org.chromium.net:cronet-api:143.7445.0",
            "org.chromium.net:cronet-common:143.7445.0",
            "org.chromium.net:cronet-embedded:143.7445.0",
        )
    }
}

// Build-graph preflight used by build.sh. It validates the actual project descriptors and
// concrete compile-task targets before any Android compilation starts.
// The other projects are inspected once all are configured; the task action only checks the
// collected values, so it stays configuration-cache compatible.
run {
    val findings = mutableListOf<String>()
    val problems = mutableListOf<String>()
    val wrappers = mapOf(
        "youtubeapi" to "youtubeapi",
        "sharedutils" to "sharedutils",
        "mediaserviceinterfaces" to "mediaserviceinterfaces",
        "commons-io-2.8.0" to "commons-io",
        "j2v8" to "j2v8",
    )
    val rootDirectory = rootProject.projectDir
    // Only inspect (and realize the compile tasks) when this preflight was requested.
    val requested = gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "verifySmartTubeBuildGraph" }
    if (requested) gradle.projectsEvaluated {
        wrappers.forEach { (projectName, wrapperDir) ->
            val child = rootProject.findProject(":$projectName")
            if (child == null) {
                problems += "Project with path ':$projectName' could not be found in root project."
                return@forEach
            }
            val expectedDir = File(rootDirectory, "smarttube_wrappers/$wrapperDir").canonicalFile
            if (child.projectDir.canonicalFile != expectedDir) {
                problems += "$projectName is using ${child.projectDir}, expected host wrapper $expectedDir"
            }
            if (child.buildFile.name != "build.gradle.kts") {
                problems += "$projectName is using unexpected build file ${child.buildFile}"
            }

            child.tasks.withType(org.gradle.api.tasks.compile.JavaCompile::class.java).forEach { javaTask ->
                val target = javaTask.targetCompatibility
                findings += "Ambient TV preflight: ${javaTask.path} Java target = $target"
                if (target != "17") problems += "${javaTask.path} Java target is $target, expected 17"
            }

            child.tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile::class.java).forEach { kotlinTask ->
                val target = kotlinTask.compilerOptions.jvmTarget.get()
                findings += "Ambient TV preflight: ${kotlinTask.path} Kotlin target = $target"
                if (target != org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) {
                    problems += "${kotlinTask.path} Kotlin target is $target, expected JVM_17"
                }
            }
        }
    }
    tasks.register("verifySmartTubeBuildGraph") {
        group = "verification"
        description = "Verifies SmartTube host wrappers and Java/Kotlin JVM target alignment."
        doLast {
            findings.forEach(::println)
            check(problems.isEmpty()) { problems.joinToString("\n") }
            println("Ambient TV preflight: SmartTube wrappers and JVM targets are correct.")
        }
    }
}
