package com.ambient.tv.moonlight

import org.junit.Assert.*
import org.junit.Test

class MoonlightDiagnosticTest {
    @Test fun textIsBoundedWithoutSplittingSurrogates() {
        assertEquals("a\nb\tc", moonlightBoundedText("a\u0000\nb\tc"))
        assertEquals("a".repeat(511), moonlightBoundedText("a".repeat(511) + "😀"))
        assertEquals("a".repeat(510) + "😀", moonlightBoundedText("a".repeat(510) + "😀z"))
        assertEquals("x", moonlightBoundedText("\uD800x\uDC00"))
    }

    @Test fun motionUsesGameStreamUnits() {
        assertEquals(9.80665f, moonlightAccelerationFromG(1f), .0001f)
        assertEquals(180f, moonlightGyroDegrees(kotlin.math.PI.toFloat()), .001f)
        assertEquals(-90f, moonlightGyroDegrees((-kotlin.math.PI / 2).toFloat()), .001f)
    }

    @Test fun colorEvidenceRequiresBothReportedFields() {
        val hdr = MoonlightVideoMetrics(hdr = true)
        assertEquals("unknown", hdr.copy(outputColorStandard = 6).hdrOutputEvidence)
        assertEquals("confirmed", hdr.copy(outputColorStandard = 6, outputColorTransfer = 6).hdrOutputEvidence)
        assertEquals("mismatch", hdr.copy(outputColorStandard = 1, outputColorTransfer = 6).hdrOutputEvidence)
        assertEquals("unknown", hdr.copy(hdr = false, outputColorStandard = 6, outputColorTransfer = 6).hdrOutputEvidence)
    }

    @Test fun snapshotReportsMissingRatherThanInventedMeasurements() {
        val report = moonlightDiagnosticReport(MoonlightStreamMetrics(), false, "")
        assertTrue(report.contains("presentation_p95_ms=null"))
        assertTrue(report.contains("decoder_hdr_evidence=unknown"))
        assertTrue(report.contains("not physical display"))
    }
}
