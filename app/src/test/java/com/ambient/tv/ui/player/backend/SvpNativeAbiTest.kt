package com.ambient.tv.ui.player.backend

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** M7: the native engine, adapters and JNI check must agree on one ABI; checked on every test run. */
class SvpNativeAbiTest {
    private val svp: File = listOf(File("../native/ambientsvp"), File("native/ambientsvp")).first { it.isDirectory }

    @Test fun headerEngineAndAppAgreeOnTheAbi() {
        val header = Regex("""#define\s+AMBIENTSVP_ABI\s+(\d+)""")
            .find(File(svp, "include/ambientsvp.h").readText())!!.groupValues[1].toInt()
        val engine = Regex("""pub const AMBIENTSVP_ABI: u32 = (\d+);""")
            .find(File(svp, "src/lib.rs").readText())!!.groupValues[1].toInt()
        assertEquals("include/ambientsvp.h vs src/lib.rs", header, engine)
        assertEquals("include/ambientsvp.h vs SvpNative.kt", header, SVP_NATIVE_ABI)
        // No second literal ABI left in the engine that could drift.
        assertEquals(false, Regex("""fn ambient_svp_abi\(\) -> u32 \{\s*\d""").containsMatchIn(File(svp, "src/lib.rs").readText()))
    }

    /** M5: the adapters' timing-gap retry (C header) runs its own test when a C compiler exists. */
    @Test fun timingGapRetryRecovers() {
        val gcc = listOf("/usr/bin/gcc", "/usr/bin/cc").map(::File).firstOrNull { it.canExecute() }
        assumeTrue("no C compiler", gcc != null)
        val out = File.createTempFile("svp_retry", ".bin").apply { deleteOnExit() }
        val build = ProcessBuilder(gcc!!.path, "-std=c11", "-Wall", "-Werror", "-I", File(svp, "include").path,
            File(svp, "tests/svp_retry_test.c").path, "-o", out.path).redirectErrorStream(true).start()
        assumeTrue("compile timed out", build.waitFor(60, TimeUnit.SECONDS))
        assertEquals(build.inputStream.bufferedReader().readText(), 0, build.exitValue())
        val run = ProcessBuilder(out.path).redirectErrorStream(true).start()
        run.waitFor(30, TimeUnit.SECONDS)
        assertEquals(run.inputStream.bufferedReader().readText(), 0, run.exitValue())
    }
}
