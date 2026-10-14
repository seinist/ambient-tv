# SmartTube host wrappers

These modules intentionally do not evaluate SmartTube's legacy Gradle build files.
They compile the pinned SmartTube source trees with the host project's AGP/Kotlin toolchain.
This keeps Java/Kotlin bytecode targets aligned (JVM 17) and isolates upstream build-script deprecations.
