package com.ambient.tv.update

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The APK's own baseline profile (AGP packs it as assets/dexopt/baseline.prof and .profm) as a
 * dex metadata file. Installed next to the APK in one session ("base.dm"), ART compiles the
 * profiled code at install time; without it an updated app runs interpreted/JIT until the
 * device's idle dexopt, which on a TV box can take days and makes navigation stutter.
 * Same layout as scripts/install_profiled_release.sh. Returns false when the APK has no profile.
 */
internal fun writeDexMetadata(apk: File, out: OutputStream): Boolean = ZipFile(apk).use { zip ->
    val profile = zip.getEntry(PROFILE) ?: return false
    val metadata = zip.getEntry(PROFILE_METADATA)
    ZipOutputStream(out).use { dm ->
        dm.putNextEntry(ZipEntry("primary.prof"))
        zip.getInputStream(profile).use { it.copyTo(dm) }
        dm.closeEntry()
        if (metadata != null) {
            dm.putNextEntry(ZipEntry("primary.profm"))
            zip.getInputStream(metadata).use { it.copyTo(dm) }
            dm.closeEntry()
        }
    }
    true
}

private const val PROFILE = "assets/dexopt/baseline.prof"
private const val PROFILE_METADATA = "assets/dexopt/baseline.profm"
