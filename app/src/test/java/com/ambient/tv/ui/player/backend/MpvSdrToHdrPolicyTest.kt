package com.ambient.tv.ui.player.backend

import com.ambient.tv.ui.display.SdrToHdrRequest
import com.ambient.tv.ui.display.SdrToHdrStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvSdrToHdrPolicyTest {
    private val request = SdrToHdrRequest(400, 1, 2)
    private val originals = linkedMapOf(
        "target-colorspace-hint-strict" to "no",
        "target-colorspace-hint" to "auto",
        "target-colorspace-hint-mode" to "source",
        "target-prim" to "display-p3",
        "target-trc" to "gamma2.2",
        "target-peak" to "287.5",
        "tone-mapping" to "bt.2390",
        "inverse-tone-mapping" to "no",
    )
    private val applied = listOf(
        "target-colorspace-hint-strict" to "yes",
        "target-colorspace-hint" to "yes",
        "target-colorspace-hint-mode" to "target",
        "target-prim" to "bt.2020",
        "target-trc" to "pq",
        "target-peak" to "400",
        "tone-mapping" to "spline",
        "inverse-tone-mapping" to "yes",
    )
    private val restoreOrder = listOf(
        "inverse-tone-mapping", "target-trc", "target-prim", "target-peak",
        "tone-mapping", "target-colorspace-hint-mode", "target-colorspace-hint",
        "target-colorspace-hint-strict",
    )
    private val target = mapOf(
        "gamma" to "pq",
        "primaries" to "bt.2020",
        "sig-peak" to (400.0 / 203.0).toString(),
        "pixelformat" to "rgb10a2",
    )

    @Test fun inputHelperAcceptsOnlyExplicitSdrAtEveryStage() {
        val transfers = listOf("bt.1886", "srgb", "gamma1.8", "gamma2.0", "gamma2.2", "gamma2.4", "gamma2.6", "gamma2.8")
        for (transfer in transfers) {
            for (stage in 0..2) {
                val pipeline = MutableList(3) { "bt.1886" }
                pipeline[stage] = transfer
                for (primaries in listOf("bt.709", "bt.601-525", "bt.601-625")) {
                    assertTrue(supportsMpvSdrToHdrInput("gpu-next", "vulkan", pipeline[0], pipeline[1], pipeline[2], primaries))
                }
            }
        }
        assertTrue(supportsMpvSdrToHdrInput(" GPU-NEXT ", " Vulkan ", " SRGB ", "BT.1886", "gamma2.2", " BT.709 "))
    }

    @Test fun inputHelperRejectsMissingUnknownAndHdrAtEveryStage() {
        for (transfer in listOf(null, "", " ", "unknown", "auto", "linear", "pq", "smpte2084", "hlg", "arib-std-b67", "bt.2020-10", "bt.2020-12")) {
            for (stage in 0..2) {
                val pipeline = MutableList<String?>(3) { "bt.1886" }
                pipeline[stage] = transfer
                assertFalse("stage=$stage transfer=$transfer", supportsMpvSdrToHdrInput("gpu-next", "vulkan", pipeline[0], pipeline[1], pipeline[2], "bt.709"))
            }
        }
        for (primaries in listOf(null, "", "unknown", "auto", "bt.2020", "display-p3", "bt.601")) {
            assertFalse(supportsMpvSdrToHdrInput("gpu-next", "vulkan", "srgb", "srgb", "srgb", primaries))
        }
    }

    @Test fun activePqTargetKeepsSdrInputButNeverHidesAnHdrFilterOrSourceChange() {
        for (stage in 0..2) {
            for (changed in listOf(null, "pq", "hlg", "linear")) {
                val native = FakeProperties().apply { target() }
                val controller = begin(native)
                val input = MutableList<String?>(3) { "bt.1886" }
                assertTrue(controller.active)
                val supported = supportsMpvSdrToHdrInput("gpu-next", "vulkan", input[0], input[1], input[2], "bt.709")
                assertEquals(SdrToHdrStatus.ACTIVE, controller.update(request, supported, 1_500))
                assertEquals(applied, native.writes) // PQ target did not change the SDR inputs.
                input[stage] = changed
                val changedSupported = supportsMpvSdrToHdrInput("gpu-next", "vulkan", input[0], input[1], input[2], "bt.709")
                assertEquals(SdrToHdrStatus.UNAVAILABLE, controller.update(request, changedSupported, 2_000))
                assertFalse(controller.engaged)
                assertRestored(native)
            }
        }
    }

    @Test fun gpuHdrReportingUsesTheFinalTargetWhileEmbedUsesItsDirectVideoFormat() {
        for (vo in listOf("gpu", "gpu-next")) {
            assertNull(mpvNativeHdrOutput(vo, mpvHdrOutputGamma(vo, "pq", "bt.1886")))
            assertNull(mpvNativeHdrOutput(vo, mpvHdrOutputGamma(vo, "pq", null)))
            assertEquals("hdr10", mpvNativeHdrOutput(vo, mpvHdrOutputGamma(vo, "bt.1886", "pq")))
            assertEquals("hlg", mpvNativeHdrOutput(vo, mpvHdrOutputGamma(vo, "bt.1886", "hlg")))
        }
        assertEquals("hdr10", mpvNativeHdrOutput("mediacodec_embed", mpvHdrOutputGamma("mediacodec_embed", "pq", null)))
        assertNull(mpvHdrOutputGamma(null, "pq", "pq"))
    }

    @Test fun vulkanAndGpuNativeHdrCountLikeMediacodecEmbed() {
        assertEquals("hdr10", mpvNativeHdrOutput("mediacodec_embed", "pq"))
        assertEquals("hdr10", mpvNativeHdrOutput("gpu-next", "smpte2084"))
        assertEquals("hlg", mpvNativeHdrOutput("gpu", "arib-std-b67"))
        assertNull(mpvNativeHdrOutput("gpu-next", "bt.1886"))
        assertNull(mpvNativeHdrOutput("gpu", "srgb"))
        assertNull(mpvNativeHdrOutput(null, "pq"))
    }

    @Test fun inputHelperRejectsDirectGpuAndUnknownBackends() {
        for (vo in listOf(null, "", "unknown", "gpu", "mediacodec_embed", "direct", "null")) {
            assertFalse(supportsMpvSdrToHdrInput(vo, "vulkan", "srgb", "srgb", "srgb", "bt.709"))
        }
        for (api in listOf(null, "", "unknown", "auto", "opengl", "opengl-es", "d3d11")) {
            assertFalse(supportsMpvSdrToHdrInput("gpu-next", api, "srgb", "srgb", "srgb", "bt.709"))
        }
    }

    @Test fun missingOrNonSdrInitialTargetIsUnavailableWithoutWritesAndCanRecover() {
        for ((name, value) in listOf(
            "gamma" to null, "gamma" to "pq", "gamma" to "smpte2084", "gamma" to "hlg",
            "gamma" to "linear", "gamma" to "unknown", "primaries" to null,
            "primaries" to "bt.2020", "sig-peak" to null, "sig-peak" to "NaN",
            "sig-peak" to "Infinity", "sig-peak" to "0", "sig-peak" to "2",
        )) {
            val native = FakeProperties()
            native.values["video-target-params/$name"] = value
            val controller = MpvSdrToHdrController(native)
            controller.update(request, true, 0)
            assertEquals("$name=$value", SdrToHdrStatus.UNAVAILABLE, controller.update(request, true, 1_000))
            assertTrue(native.writes.isEmpty())
            assertFalse(controller.engaged)
            assertFalse(controller.rollbackFailed)
            native.sdrTarget()
            assertEquals(SdrToHdrStatus.WAITING, controller.update(request, true, 1_001))
            controller.update(request, true, 2_000)
            assertTrue(native.writes.isEmpty())
            controller.update(request, true, 2_001)
            assertTrue(controller.engaged)
        }
    }

    @Test fun previousPqTargetCannotSatisfyANewTransaction() {
        val native = FakeProperties()
        native.target()
        val controller = begin(native)
        assertTrue(controller.active)
        val changed = request.copy(displayId = 9)
        assertEquals(SdrToHdrStatus.WAITING, controller.update(changed, true, 1_500))
        val count = native.writes.size
        assertEquals(SdrToHdrStatus.UNAVAILABLE, controller.update(changed, true, 2_500))
        assertEquals(count, native.writes.size)
        assertFalse(controller.engaged)
        assertFalse(controller.active)
    }

    @Test fun newPqTargetCannotConfirmOnTheApplyingPollOrBefore250Milliseconds() {
        val native = FakeProperties()
        val controller = MpvSdrToHdrController(native)
        native.onSet = { name, _ ->
            if (name == "inverse-tone-mapping") native.target()
            true
        }
        controller.update(request, true, 0)
        assertEquals(SdrToHdrStatus.WAITING, controller.update(request, true, 1_000))
        assertEquals(SdrToHdrStatus.WAITING, controller.update(request, true, 1_001))
        assertEquals(SdrToHdrStatus.WAITING, controller.update(request, true, 1_249))
        assertTrue(controller.engaged)
        assertFalse(controller.active)
        assertEquals(SdrToHdrStatus.ACTIVE, controller.update(request, true, 1_250))
    }

    @Test fun changedHintOrStrictOptionFailsBothPendingAndActiveTransactions() {
        for (confirmed in listOf(false, true)) {
            for (name in listOf("target-colorspace-hint", "target-colorspace-hint-strict")) {
                for (value in listOf(null, "no", "auto")) {
                    val native = FakeProperties()
                    if (confirmed) native.target()
                    val controller = begin(native)
                    native.values[name] = value
                    assertEquals(SdrToHdrStatus.FAILED, controller.update(request, true, 1_251))
                    assertRestored(native)
                    assertFalse(controller.rollbackFailed)
                }
            }
        }
    }

    @Test fun aPqTargetWithoutTheConversionOptionsCannotRemainActive() {
        for ((name, value) in listOf("inverse-tone-mapping" to "no", "tone-mapping" to "clip",
            "target-colorspace-hint-mode" to "source", "target-trc" to "bt.1886",
            "target-prim" to "bt.709", "target-peak" to "1000", "target-peak" to "NaN")) {
            for (confirmed in listOf(false, true)) {
                val native = FakeProperties()
                if (confirmed) native.target()
                val controller = begin(native)
                native.values[name] = value
                assertEquals(SdrToHdrStatus.FAILED, controller.update(request, true, 1_500))
                assertRestored(native)
                assertFalse(controller.engaged)
            }
        }
        val native = FakeProperties().apply { target() }
        val controller = begin(native)
        native.values["target-peak"] = "400.000000"
        assertEquals(SdrToHdrStatus.ACTIVE, controller.update(request, true, 1_500))
    }

    @Test fun eachRestoreFailureOrExceptionLatchesRollbackFailedPermanently() {
        for (throwing in listOf(false, true)) {
            for (failedOption in restoreOrder) {
                val native = FakeProperties()
                val controller = begin(native)
                native.onSet = { name, _ ->
                    if (name == failedOption) {
                        if (throwing) error("restore failed")
                        false
                    } else true
                }
                controller.reset()
                assertTrue(failedOption, controller.rollbackFailed)
                assertEquals(restoreOrder, native.writes.takeLast(restoreOrder.size).map { it.first })
                val count = native.writes.size
                native.onSet = null
                controller.reset()
                controller.update(null, true, 2_000)
                controller.update(request, false, 3_000)
                controller.fail()
                assertTrue(controller.rollbackFailed)
                assertEquals(SdrToHdrStatus.FAILED, controller.update(request, true, 4_000))
                assertEquals(count, native.writes.size)
            }
        }
    }

    @Test fun nullRequestIsOffWithoutAnyNativeAccess() {
        val native = FakeProperties()
        val controller = MpvSdrToHdrController(native)
        for (supported in listOf(false, true)) {
            assertEquals(SdrToHdrStatus.OFF, controller.update(null, supported, 0))
            assertEquals(SdrToHdrStatus.OFF, controller.update(null, supported, 10_000))
        }
        assertTrue(native.events.isEmpty())
        assertFalse(controller.engaged)
        assertFalse(controller.active)
    }

    @Test fun callerRejectsAbsentUnknownAndNativeHdrSourcesOrUnsupportedRenderer() {
        // Source/renderer eligibility is the caller's input, not inferred by this policy.
        for (reason in listOf("no source", "unknown source", "native HDR", "non Vulkan", "non gpu-next")) {
            val native = FakeProperties()
            val controller = MpvSdrToHdrController(native)
            assertEquals(reason, SdrToHdrStatus.UNAVAILABLE, controller.update(request, false, 0))
            assertEquals(reason, SdrToHdrStatus.UNAVAILABLE, controller.update(request, false, 10_000))
            assertTrue(reason, native.events.isEmpty())
        }
    }

    @Test fun startupRequiresOneSecondOfStableEqualRequests() {
        val native = FakeProperties()
        val controller = MpvSdrToHdrController(native)
        assertEquals(SdrToHdrStatus.WAITING, controller.update(request, true, 100))
        assertEquals(SdrToHdrStatus.WAITING, controller.update(request.copy(), true, 1_099))
        assertTrue(native.events.isEmpty())
        assertFalse(controller.engaged)
        assertEquals(SdrToHdrStatus.WAITING, controller.update(request.copy(), true, 1_100))
        assertEquals(applied, native.writes)
        assertTrue(controller.engaged)
        assertFalse(controller.active)
    }

    @Test fun snapshotsAllOptionsBeforeWritingStrictFirstAndInverseLast() {
        val native = FakeProperties()
        begin(native)
        val firstWrite = native.events.indexOfFirst { it.startsWith("set:") }
        assertEquals(originals.keys.map { "get:$it" }, native.events.take(firstWrite).filter { it.removePrefix("get:") in originals })
        assertEquals(applied.map { "set:${it.first}=${it.second}" }, native.events.drop(firstWrite).take(applied.size))
        assertEquals(applied, native.writes)
        assertFalse(native.events.any { "hdr-reference-white" in it })
        assertFalse(native.events.any { it == "get:vo" || it.startsWith("set:vo=") || "gpu-api" in it })
    }

    @Test fun validTargetBecomesActiveWithoutRepeatingWrites() {
        val native = FakeProperties()
        val controller = begin(native)
        native.target()
        assertEquals(SdrToHdrStatus.ACTIVE, controller.update(request, true, 1_251))
        assertTrue(controller.engaged)
        assertTrue(controller.active)
        assertEquals(SdrToHdrStatus.ACTIVE, controller.update(request, true, 50_000))
        assertEquals(applied, native.writes)
    }

    @Test fun hdrVideoOutDoesNotConfirmTheRenderingTarget() {
        val native = FakeProperties()
        for (prefix in listOf("video-out-params", "video-params")) {
            for ((name, value) in target) native.values["$prefix/$name"] = value
        }
        val controller = begin(native)
        assertEquals(SdrToHdrStatus.WAITING, controller.update(request, true, 2_999))
        assertEquals(SdrToHdrStatus.FAILED, controller.update(request, true, 3_000))
        assertFalse(native.events.any { it.startsWith("get:video-out-params") || it.startsWith("get:video-params") })
        assertRestored(native)
    }

    @Test fun negotiatedSdrTargetTimesOutAndRestores() {
        val native = FakeProperties()
        native.target(mapOf("gamma" to "srgb", "primaries" to "bt.709", "sig-peak" to "1", "pixelformat" to "rgba8"))
        val controller = begin(native)
        assertEquals(SdrToHdrStatus.WAITING, controller.update(request, true, 2_999))
        assertEquals(SdrToHdrStatus.FAILED, controller.update(request, true, 3_000))
        assertRestored(native)
    }

    @Test fun validTargetCanConfirmAtTheDeadlineButNotAfterIt() {
        for (time in listOf(3_000L, 3_001L)) {
            val native = FakeProperties()
            val controller = begin(native)
            native.target()
            val expected = if (time == 3_000L) SdrToHdrStatus.ACTIVE else SdrToHdrStatus.FAILED
            assertEquals(expected, controller.update(request, true, time))
        }
    }

    @Test fun targetNeedsPqBt2020FiniteHdrPeakAndKnownHighPrecisionFormat() {
        val invalid = listOf(
            "gamma" to null, "gamma" to "srgb", "gamma" to "hlg",
            "primaries" to null, "primaries" to "bt.709",
            "sig-peak" to null, "sig-peak" to "", "sig-peak" to "unknown",
            "sig-peak" to "NaN", "sig-peak" to "Infinity", "sig-peak" to "-Infinity",
            "sig-peak" to "0", "sig-peak" to "-2", "sig-peak" to "1",
            "sig-peak" to "1e300", "sig-peak" to (10_000.0 / 203.0).toString(),
            "pixelformat" to null, "pixelformat" to "rgba8", "pixelformat" to "bgra8",
            "pixelformat" to "rgb8", "pixelformat" to "unknown10", "pixelformat" to "rgba16unknown",
            "pixelformat" to "rg16hf", "pixelformat" to "rgb565", "pixelformat" to "rgb10a2u",
            "pixelformat" to "rgb10_a2", "pixelformat" to "bgr10_a2", "pixelformat" to "a2rgb10",
            "pixelformat" to "rgba32f", "pixelformat" to "rgb16hf",
        )
        for ((name, value) in invalid) {
            val native = FakeProperties()
            native.target(mapOf(name to value))
            val controller = begin(native)
            assertEquals("$name=$value", SdrToHdrStatus.FAILED, controller.update(request, true, 3_000))
            assertRestored(native)
        }
    }

    @Test fun acceptsTheConfirmedLocalLibplaceboFormats() {
        for (format in listOf(
            "rgb10a2", "bgr10a2", "rgba16", "rgba16hf", "rgba16f",
        )) {
            val native = FakeProperties()
            native.target(mapOf("pixelformat" to format, "gamma" to " SMPTE2084 "))
            val controller = begin(native)
            assertTrue(format, controller.active)
        }
    }

    @Test fun peakCapUses203NitUnitsAndFivePercentTolerance() {
        for ((nits, valid) in listOf(203.0 to false, 204.0 to true, 400.0 to true, 419.999 to true, 420.001 to false)) {
            val native = FakeProperties()
            native.target(mapOf("sig-peak" to (nits / 203.0).toString()))
            val controller = begin(native)
            assertEquals("$nits nits", valid, controller.active)
            assertEquals(if (valid) SdrToHdrStatus.ACTIVE else SdrToHdrStatus.FAILED, controller.update(request, true, 3_000))
        }
    }

    @Test fun losingAnyTargetRequirementWhileActiveFailsImmediately() {
        for ((name, value) in listOf(
            "gamma" to "srgb", "primaries" to "bt.709", "sig-peak" to "1",
            "sig-peak" to "20", "pixelformat" to "rgba8", "gamma" to null,
        )) {
            val native = FakeProperties()
            native.target()
            val controller = begin(native)
            assertTrue(controller.active)
            native.values["video-target-params/$name"] = value
            assertEquals(SdrToHdrStatus.FAILED, controller.update(request, true, 1_251))
            assertFalse(controller.engaged)
            assertFalse(controller.active)
            assertRestored(native)
        }
    }

    @Test fun everyPartialApplyFailureOrExceptionRollsBackAllOptions() {
        for (throwing in listOf(false, true)) {
            for ((index, option) in applied.withIndex()) {
                val native = FakeProperties()
                // Simulate a native write that mutates first, then reports failure.
                native.onSet = { name, value ->
                    if (name == option.first && value == option.second) {
                        if (throwing) error("native setter")
                        false
                    } else true
                }
                val controller = begin(native)
                assertEquals(SdrToHdrStatus.FAILED, controller.update(request, true, 1_251))
                assertEquals(applied.take(index + 1), native.writes.dropLast(restoreOrder.size))
                assertRestored(native)
                assertFalse(controller.engaged)
                val count = native.writes.size
                controller.reset()
                controller.fail()
                controller.update(request, true, 10_000)
                assertEquals(count, native.writes.size)
            }
        }
    }

    @Test fun missingOrThrowingSnapshotReadsNeverWriteAnything() {
        for (throwing in listOf(false, true)) {
            for (name in originals.keys) {
                val native = FakeProperties()
                if (throwing) native.throwOnGet = name else native.values.remove(name)
                val controller = begin(native)
                assertEquals(SdrToHdrStatus.FAILED, controller.update(request, true, 1_251))
                assertFalse(controller.engaged)
                assertTrue(native.writes.isEmpty())
                val events = native.events.toList()
                controller.reset()
                controller.update(request, true, 10_000)
                assertEquals(events, native.events)
            }
        }
    }

    @Test fun targetReadExceptionsRollbackAndLatch() {
        for (name in target.keys) {
            val native = FakeProperties()
            native.target()
            val controller = begin(native)
            native.throwOnGet = "video-target-params/$name"
            assertEquals(SdrToHdrStatus.FAILED, controller.update(request, true, 1_251))
            assertRestored(native)
            assertEquals(SdrToHdrStatus.FAILED, controller.update(request, true, 10_000))
        }
    }

    @Test fun failuresStayLatchedAcrossOffUnsupportedResetAndChangedRequests() {
        val native = FakeProperties()
        val controller = begin(native)
        controller.fail()
        assertRestored(native)
        assertFalse(controller.rollbackFailed)
        val count = native.writes.size
        assertEquals(SdrToHdrStatus.OFF, controller.update(null, true, 2_000))
        assertEquals(SdrToHdrStatus.UNAVAILABLE, controller.update(request, false, 3_000))
        controller.reset()
        controller.fail()
        assertEquals(SdrToHdrStatus.FAILED, controller.update(request.copy(displayId = 3), true, 4_000))
        assertEquals(count, native.writes.size)
    }

    @Test fun failBeforeEngagementLatchesWithoutNativeWrites() {
        val native = FakeProperties()
        val controller = MpvSdrToHdrController(native)
        controller.fail()
        controller.reset()
        assertEquals(SdrToHdrStatus.FAILED, controller.update(request, true, 0))
        assertTrue(native.events.isEmpty())
    }

    @Test fun allRestoresAreAttemptedEvenWhenMultipleWritesFailOrThrow() {
        val native = FakeProperties()
        val controller = begin(native)
        native.onSet = { name, _ ->
            when (name) {
                "inverse-tone-mapping", "target-colorspace-hint" -> false
                "target-trc", "tone-mapping" -> error("restore failure")
                else -> true
            }
        }
        controller.reset()
        assertRestored(native)
        assertFalse(controller.engaged)
        assertTrue(controller.rollbackFailed)
        assertEquals(SdrToHdrStatus.FAILED, controller.update(request, true, 2_000))
        val count = native.writes.size
        controller.reset()
        controller.fail()
        assertEquals(count, native.writes.size)
        assertTrue(controller.rollbackFailed)
    }

    @Test fun offAndUnsupportedReportTheirStatusEvenWhenRestoreFails() {
        for (off in listOf(false, true)) {
            val native = FakeProperties()
            val controller = begin(native)
            native.onSet = { _, _ -> false }
            assertEquals(
                if (off) SdrToHdrStatus.OFF else SdrToHdrStatus.UNAVAILABLE,
                controller.update(if (off) null else request, false, 1_251),
            )
            assertRestored(native)
            assertTrue(controller.rollbackFailed)
            assertEquals(SdrToHdrStatus.FAILED, controller.update(request, true, 2_000))
        }
    }

    @Test fun requestPeakDisplayOrModeChangeRestoresThenSettlesAgain() {
        for (changed in listOf(request.copy(peakNits = 500), request.copy(displayId = 7), request.copy(displayModeId = 8))) {
            for (confirmed in listOf(false, true)) {
                val native = FakeProperties()
                if (confirmed) native.target()
                val controller = begin(native)
                assertEquals(SdrToHdrStatus.WAITING, controller.update(changed, true, 1_500))
                assertRestored(native)
                assertFalse(controller.active)
                assertFalse(controller.engaged)
                val count = native.writes.size
                assertEquals(SdrToHdrStatus.WAITING, controller.update(changed.copy(), true, 2_499))
                assertEquals(count, native.writes.size)
                native.sdrTarget()
                controller.update(changed, true, 2_500)
                assertTrue(controller.engaged)
                assertEquals(count + applied.size, native.writes.size)
                assertEquals(changed.peakNits.toString(), native.values["target-peak"])
            }
        }
    }

    @Test fun changeDuringStartupRestartsSettlingWithoutWrites() {
        val native = FakeProperties()
        val controller = MpvSdrToHdrController(native)
        controller.update(request, true, 0)
        val changed = request.copy(displayId = 7)
        controller.update(changed, true, 999)
        controller.update(changed, true, 1_998)
        assertTrue(native.events.isEmpty())
        controller.update(changed, true, 1_999)
        assertTrue(controller.engaged)
    }

    @Test fun restoreFailureOnRequestChangePreventsTheNextTransaction() {
        val native = FakeProperties()
        val controller = begin(native)
        native.onSet = { _, _ -> false }
        val changed = request.copy(displayModeId = 8)
        assertEquals(SdrToHdrStatus.FAILED, controller.update(changed, true, 1_500))
        assertRestored(native)
        assertEquals(SdrToHdrStatus.FAILED, controller.update(changed, true, 2_100))
        assertEquals(applied.size + restoreOrder.size, native.writes.size)
    }

    @Test fun offAndUnsupportedRestoreExactCustomValuesAndAllowFreshSettling() {
        for (off in listOf(false, true)) {
            val native = FakeProperties()
            native.values["hdr-reference-white"] = "203"
            native.values["unowned"] = "custom"
            val controller = begin(native)
            assertEquals(
                if (off) SdrToHdrStatus.OFF else SdrToHdrStatus.UNAVAILABLE,
                controller.update(if (off) null else request, false, 1_500),
            )
            assertRestored(native)
            assertEquals("203", native.values["hdr-reference-white"])
            assertEquals("custom", native.values["unowned"])
            assertEquals(SdrToHdrStatus.WAITING, controller.update(request, true, 2_000))
            assertEquals(SdrToHdrStatus.WAITING, controller.update(request, true, 2_999))
            assertEquals(applied.size + restoreOrder.size, native.writes.size)
            controller.update(request, true, 3_000)
            assertTrue(controller.engaged)
        }
    }

    @Test fun interruptionDuringStartupRequiresAnotherFullSettle() {
        for (off in listOf(false, true)) {
            val native = FakeProperties()
            val controller = MpvSdrToHdrController(native)
            controller.update(request, true, 0)
            controller.update(if (off) null else request, false, 999)
            controller.update(request, true, 1_000)
            controller.update(request, true, 1_999)
            assertTrue(native.events.isEmpty())
            controller.update(request, true, 2_000)
            assertTrue(controller.engaged)
        }
    }

    @Test fun directResetRestoresAndDoesNotLatchSuccessfulTransactions() {
        val native = FakeProperties()
        native.target()
        val controller = begin(native)
        controller.reset()
        assertRestored(native)
        assertFalse(controller.active)
        assertFalse(controller.engaged)
        controller.reset()
        assertEquals(applied.size + restoreOrder.size, native.writes.size)
        assertEquals(SdrToHdrStatus.WAITING, controller.update(request, true, 2_000))
        native.sdrTarget()
        assertEquals(SdrToHdrStatus.WAITING, controller.update(request, true, 3_000))
        native.target()
        assertEquals(SdrToHdrStatus.ACTIVE, controller.update(request, true, 3_250))
    }

    @Test fun invalidRequestPeaksFailBeforeNativeAccessAndStayLatched() {
        for (peak in listOf(Int.MIN_VALUE, 0, 203, 601, Int.MAX_VALUE)) {
            val native = FakeProperties()
            val controller = MpvSdrToHdrController(native)
            assertEquals(SdrToHdrStatus.FAILED, controller.update(request.copy(peakNits = peak), true, 0))
            assertEquals(SdrToHdrStatus.FAILED, controller.update(request, true, 1_000))
            assertTrue(native.events.isEmpty())
        }
    }

    @Test fun validRequestPeakBoundsAreAppliedExactly() {
        for (peak in listOf(204, 600)) {
            val native = FakeProperties()
            val controller = MpvSdrToHdrController(native)
            val bounded = request.copy(peakNits = peak)
            controller.update(bounded, true, 0)
            assertEquals(SdrToHdrStatus.WAITING, controller.update(bounded, true, 1_000))
            native.target(mapOf("sig-peak" to (peak / 203.0).toString()))
            assertEquals(SdrToHdrStatus.ACTIVE, controller.update(bounded, true, 1_250))
            assertEquals(peak.toString(), native.values["target-peak"])
        }
    }

    @Test fun invalidPeakWhileEngagedRestoresBeforeLatching() {
        val native = FakeProperties()
        val controller = begin(native)
        assertEquals(SdrToHdrStatus.FAILED, controller.update(request.copy(peakNits = 601), true, 1_251))
        assertRestored(native)
    }

    @Test fun baselineUpdatesInterceptOnlyOwnedOptionsWhileEngaged() {
        for (confirmed in listOf(false, true)) {
            val native = FakeProperties()
            val controller = MpvSdrToHdrController(native)
            assertFalse(controller.updateBaseline("tone-mapping", "clip"))
            controller.update(request, true, 0)
            assertFalse(controller.updateBaseline("tone-mapping", "clip"))
            controller.update(request, true, 1_000)
            if (confirmed) {
                native.target()
                controller.update(request, true, 1_250)
            }
            assertFalse(controller.updateBaseline("hdr-reference-white", "100"))
            assertFalse(controller.updateBaseline("unowned", "value"))
            assertTrue(controller.updateBaseline("tone-mapping", "reinhard"))
            assertTrue(controller.updateBaseline("tone-mapping", "mobius"))
            assertEquals("spline", native.values["tone-mapping"])
            assertEquals(applied, native.writes)
            controller.update(null, true, 1_251)
            assertRestored(native, originals + ("tone-mapping" to "mobius"))
            assertFalse(controller.updateBaseline("tone-mapping", "clip"))
        }
    }

    @Test fun baselineUpdatesArePreservedOnFailureAndSubsequentTransactions() {
        val native = FakeProperties()
        val controller = begin(native)
        assertTrue(controller.updateBaseline("tone-mapping", "reinhard"))
        controller.reset()
        controller.update(request, true, 2_000)
        controller.update(request, true, 3_000)
        controller.fail()
        assertRestored(native, originals + ("tone-mapping" to "reinhard"))
    }

    @Test fun everyOwnedBaselineCanBeReplacedWithoutAnImmediateWrite() {
        val native = FakeProperties()
        val controller = begin(native)
        val replacement = originals.mapValues { "custom:${it.key}" }
        for ((name, value) in replacement) assertTrue(controller.updateBaseline(name, value))
        assertEquals(applied, native.writes)
        controller.reset()
        assertRestored(native, replacement)
    }

    @Test fun backwardsClockRestartsStartupAndFailsAnEngagedTransaction() {
        val native = FakeProperties()
        val controller = MpvSdrToHdrController(native)
        controller.update(request, true, 500)
        controller.update(request, true, 0)
        controller.update(request, true, 999)
        assertTrue(native.events.isEmpty())
        controller.update(request, true, 1_000)
        assertTrue(controller.engaged)
        assertEquals(SdrToHdrStatus.FAILED, controller.update(request, true, 999))
        assertRestored(native)
    }

    @Test fun rendererPqWithoutAndroidPqSurfaceTimesOutAndRestoresSdr() {
        val native = FakeProperties().apply { target(); surfaceDataSpace = 142671872 }
        val controller = begin(native)
        assertFalse(controller.active)
        assertEquals(SdrToHdrStatus.FAILED, controller.update(request, true, 3_000))
        assertRestored(native)
    }

    @Test fun losingPqSurfaceRollsBackAnActiveConversion() {
        val native = FakeProperties().apply { target() }
        val controller = begin(native)
        assertTrue(controller.active)
        native.surfaceDataSpace = 0
        assertEquals(SdrToHdrStatus.FAILED, controller.update(request, true, 1_500))
        assertRestored(native)
    }

    @Test fun autoReferenceWhiteLeavesMpvDefaultUntouched() {
        val native = FakeProperties().apply { values["hdr-reference-white"] = "auto"; target() }
        val controller = begin(native)
        assertTrue(controller.active)
        controller.reset()
        assertFalse(native.writes.any { it.first == "hdr-reference-white" })
        assertRestored(native)
    }

    @Test fun chosenReferenceWhiteIsOwnedClampedToPeakAndRestoredFirst() {
        val native = FakeProperties().apply { values["hdr-reference-white"] = "auto"; target() }
        val bright = request.copy(referenceWhiteNits = 600)
        val controller = beginWith(native, bright)
        assertTrue(controller.active)
        assertEquals("400", native.values["hdr-reference-white"])
        controller.reset()
        assertEquals("hdr-reference-white" to "auto", native.writes[native.writes.size - restoreOrder.size - 1])
        assertEquals("auto", native.values["hdr-reference-white"])
        assertRestored(native)
    }

    @Test fun referenceWhiteChangesLiveWithoutLeavingHdr() {
        val native = FakeProperties().apply { values["hdr-reference-white"] = "auto"; target() }
        val controller = begin(native)
        native.writes.clear()
        assertEquals(SdrToHdrStatus.ACTIVE, controller.update(request.copy(referenceWhiteNits = 300), true, 2_000))
        assertEquals(listOf("hdr-reference-white" to "300"), native.writes)
        assertEquals(SdrToHdrStatus.ACTIVE, controller.update(request.copy(referenceWhiteNits = 100), true, 2_100))
        assertEquals("100", native.values["hdr-reference-white"])
        assertEquals(SdrToHdrStatus.ACTIVE, controller.update(request, true, 2_200))
        assertEquals("auto", native.values["hdr-reference-white"])
        assertFalse(native.writes.any { it.first == "target-trc" })
        controller.reset()
        assertRestored(native)
        assertEquals("auto", native.values["hdr-reference-white"])
    }

    @Test fun peakChangeStillRenegotiatesInsteadOfLiveWhite() {
        val other = request.copy(peakNits = 500, referenceWhiteNits = 300)
        assertFalse(other.sameOutput(request))
        assertTrue(request.copy(referenceWhiteNits = 300).sameOutput(request))
        assertFalse(request.sameOutput(null))
    }

    private fun beginWith(native: FakeProperties, start: SdrToHdrRequest): MpvSdrToHdrController =
        MpvSdrToHdrController(native).also {
            val eventualTarget = native.values.filterKeys { name -> name.startsWith("video-target-params/") }
            native.sdrTarget()
            assertEquals(SdrToHdrStatus.WAITING, it.update(start, true, 0))
            it.update(start, true, 1_000)
            native.values.putAll(eventualTarget)
            it.update(start, true, 1_250)
        }

    private fun begin(native: FakeProperties): MpvSdrToHdrController =
        MpvSdrToHdrController(native).also {
            val eventualTarget = native.values.filterKeys { name -> name.startsWith("video-target-params/") }
            native.sdrTarget()
            assertEquals(SdrToHdrStatus.WAITING, it.update(request, true, 0))
            it.update(request, true, 1_000)
            native.values.putAll(eventualTarget)
            it.update(request, true, 1_250)
        }

    private fun assertRestored(native: FakeProperties, expected: Map<String, String> = originals) {
        assertEquals(restoreOrder.map { it to expected.getValue(it) }, native.writes.takeLast(restoreOrder.size))
        for ((name, value) in expected) assertEquals(name, value, native.values[name])
    }

    private inner class FakeProperties : MpvHdrProperties {
        val values: MutableMap<String, String?> = originals.toMutableMap()
        val writes = mutableListOf<Pair<String, String>>()
        val events = mutableListOf<String>()
        var throwOnGet: String? = null
        var onSet: ((String, String) -> Boolean)? = null

        var surfaceDataSpace = (6 shl 16) or (7 shl 22) or (1 shl 27)
        override fun outputDataSpace() = surfaceDataSpace
        init { sdrTarget() }

        override fun get(name: String): String? {
            events += "get:$name"
            if (name == throwOnGet) error("native getter")
            return values[name]
        }

        override fun set(name: String, value: String): Boolean {
            events += "set:$name=$value"
            writes += name to value
            values[name] = value
            return onSet?.invoke(name, value) ?: true
        }

        fun target(overrides: Map<String, String?> = emptyMap()) {
            for ((name, value) in target + overrides) values["video-target-params/$name"] = value
        }

        fun sdrTarget() = target(mapOf("gamma" to "bt.1886", "primaries" to "bt.709", "sig-peak" to "1", "pixelformat" to "rgba8"))
    }
}
