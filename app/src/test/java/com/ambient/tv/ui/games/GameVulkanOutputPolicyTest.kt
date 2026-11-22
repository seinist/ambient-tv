package com.ambient.tv.ui.games

import org.junit.Assert.assertEquals
import org.junit.Test

class GameVulkanOutputPolicyTest {
    private val hdrDisplay = GameHdrDisplayCapabilities(hdr10 = true, hlg = true, peakNits = 750f)
    private val ready = GameVulkanHdrOutputReadiness(
        presentingOnRealWindow = true,
        hasHdrSwapchain = true,
        hasReliableSourceContract = true,
        hasColorManagedPipeline = true,
        hasOutputMetadata = true,
        pqSwapchainFormat = true,
        hlgSwapchainFormat = true,
    )

    @Test fun actualGlesBridgeCannotEverClaimNativeHdr() {
        val noNativePresenter = ready.copy(presentingOnRealWindow = false)
        assertEquals(GameVulkanOutputMode.SDR,
            GameVulkanOutputPolicy.select(hdrDisplay, noNativePresenter, GameHdrNegotiatedSource.HDR10_PQ_BT2020))
        assertEquals(GameVulkanOutputMode.SDR,
            GameVulkanOutputPolicy.select(hdrDisplay, GameVulkanHdrOutputReadiness(), GameHdrNegotiatedSource.HLG_BT2020))
    }

    @Test fun requirementsCannotBeGuessedFromFp16TransportOrDisplayAlone() {
        for (missing in listOf(
            ready.copy(hasHdrSwapchain = false),
            ready.copy(hasReliableSourceContract = false),
            ready.copy(hasColorManagedPipeline = false),
            ready.copy(hasOutputMetadata = false),
            ready.copy(pqSwapchainFormat = false),
        )) {
            assertEquals(GameVulkanOutputMode.SDR,
                GameVulkanOutputPolicy.select(hdrDisplay, missing, GameHdrNegotiatedSource.HDR10_PQ_BT2020))
        }
    }

    @Test fun selectedEotfNeedsTheMatchingPhysicalPanelAndVulkanSurface() {
        assertEquals(GameVulkanOutputMode.HDR10_PQ_BT2020,
            GameVulkanOutputPolicy.select(hdrDisplay, ready, GameHdrNegotiatedSource.HDR10_PQ_BT2020))
        assertEquals(GameVulkanOutputMode.HLG_BT2020,
            GameVulkanOutputPolicy.select(hdrDisplay, ready, GameHdrNegotiatedSource.HLG_BT2020))
        assertEquals(GameVulkanOutputMode.SDR,
            GameVulkanOutputPolicy.select(hdrDisplay.copy(hlg = false), ready, GameHdrNegotiatedSource.HLG_BT2020))
        assertEquals(GameVulkanOutputMode.SDR,
            GameVulkanOutputPolicy.select(hdrDisplay.copy(hdr10 = false), ready, GameHdrNegotiatedSource.HDR10_PQ_BT2020))
        assertEquals(GameVulkanOutputMode.SDR,
            GameVulkanOutputPolicy.select(hdrDisplay, ready.copy(hlgSwapchainFormat = false), GameHdrNegotiatedSource.HLG_BT2020))
    }

    @Test fun scrgbAndSdrCannotAccidentallySelectThePqOutput() {
        assertEquals(GameVulkanOutputMode.SDR,
            GameVulkanOutputPolicy.select(hdrDisplay, ready, GameHdrNegotiatedSource.SDR))
        assertEquals(GameVulkanOutputMode.SDR,
            GameVulkanOutputPolicy.select(hdrDisplay, ready, GameHdrNegotiatedSource.SCRGB_LINEAR))
    }

    @Test fun displaySummaryDoesNotClaimHdrOnUnknownPanel() {
        assertEquals("SDR / unknown", GameHdrDisplayCapabilities().supportedTypesLabel())
        assertEquals("HDR10 · HLG", GameHdrDisplayCapabilities(hdr10 = true, hlg = true).supportedTypesLabel())
        assertEquals("HDR10+ · Dolby Vision", GameHdrDisplayCapabilities(hdr10Plus = true, dolbyVision = true).supportedTypesLabel())
    }
}
