package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.data.local.VlcImageOptions
import com.ambient.tv.data.local.VideoImageCatalog
import org.junit.Assert.*
import org.junit.Test

class ImageProcessingPolicyTest {
    @Test fun overloadedSharpeningOnlyReducesTheShaderActuallyRunning() {
        assertEquals("cas", mpvSharpeningAfterOverload("adaptive", listOf("adaptive")))
        assertEquals("off", mpvSharpeningAfterOverload("cas", listOf("cas")))
        assertNull(mpvSharpeningAfterOverload("adaptive", emptyList()))
        assertNull(mpvSharpeningAfterOverload("adaptive", listOf("nis")))
        assertNull(mpvSharpeningAfterOverload("off", listOf("adaptive")))
    }

    @Test fun svpOverloadSamplingSurvivesMissingTrackFrameRate() {
        assertEquals(60.0, vlcRenderSampleFps(null, "light", 60.0)!!, 0.0)
        assertEquals(60.0, vlcRenderSampleFps(25.0, "light", 60.0)!!, 0.0)
        assertTrue(vlcRenderOverloaded(200, vlcRenderSampleFps(null, "light", 60.0)!!, 6.0))
        assertNull(vlcRenderSampleFps(null, "off", 60.0))
        assertEquals(25.0, vlcRenderSampleFps(25.0, "off", 60.0)!!, 0.0)
    }

    @Test fun obsoleteHeightLimitCannotDisableSavedFilters() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val mpv = json.decodeFromString<MpvImageOptions>("""{"filterMaxSourceHeight":720,"sharpening":"cas","deband":"medium"}""")
        assertEquals("cas", effectiveMpvImageOptions(mpv, false).sharpening)
        assertEquals("medium", effectiveMpvImageOptions(mpv, false).deband)
        val vlc = json.decodeFromString<VlcImageOptions>("""{"filterMaxSourceHeight":720,"softwareProcessing":true,"deband":"light"}""")
        assertEquals("light", effectiveVlcImageOptions(vlc, false).deband)
        val media3 = json.decodeFromString<com.ambient.tv.data.local.Media3ImageOptions>("""{"filterMaxSourceHeight":720,"enabled":true}""")
        assertTrue(effectiveMedia3ImageOptions(media3, false).enabled)
    }
    @Test fun everyShaderPresetHasAResolvableDescriptorAndOnlyYuvHooksRequestCopyBack() {
        assertTrue(MPV_IMAGE_SHADERS.keys.containsAll(VideoImageCatalog.mpvShaders.keys))
        assertEquals(
            listOf("nnedi3_nns16", "nnedi3_nns32", "nnedi3_nns64", "nnedi3_nns128", "nnedi3_nns256", "nnedi3_nns256_win8x6"),
            com.ambient.tv.data.local.Nnedi3QualityCatalog.presets.map { it.key },
        )
        com.ambient.tv.data.local.Nnedi3QualityCatalog.presets.forEach { preset ->
            val descriptor = MPV_IMAGE_SHADERS.getValue(preset.key)
            assertTrue(descriptor.requiresCopyBack)
            assertNotNull(descriptor.resourceId)
            assertEquals(preset.bundledResourceName, descriptor.resourceName)
        }
        assertTrue(MPV_IMAGE_SHADERS.getValue("artcnn16").requiresCopyBack)
        assertTrue(MPV_IMAGE_SHADERS.getValue("artcnn32_dn").requiresCopyBack)
        assertTrue(MPV_IMAGE_SHADERS.getValue("cunny_fast_ds").requiresCopyBack)
        assertTrue(MPV_IMAGE_SHADERS.getValue("krig").requiresCopyBack)
        assertTrue(MPV_IMAGE_SHADERS.getValue("nis").requiresCopyBack)
        assertFalse(MPV_IMAGE_SHADERS.getValue("anime4k").requiresCopyBack)
        assertFalse(MPV_IMAGE_SHADERS.getValue("anime4k_m").requiresCopyBack)
    }

    @Test fun hdrAndUnknownTransferBypassSdrProcessing() {
        val options = MpvImageOptions(scaler = "artcnn16", chromaScaler = "krig", downscaler = "ssim_down",
            denoise = "light", deblock = "medium", sharpening = "cas", superResolution = true)
        listOf(null, "pq", "hlg", "st2084", "unknown").forEach { transfer ->
            assertFalse(isMpvSdrTransfer(transfer))
            assertTrue(mpvImageShaderKeys(options, isMpvSdrTransfer(transfer)).isEmpty())
            assertTrue(mpvCpuImageFilters(options, isMpvSdrTransfer(transfer)).isEmpty())
        }
        assertTrue(isMpvSdrTransfer("bt.1886"))
    }

    @Test fun independentShaderSelectionsComposeInStableOrder() {
        assertEquals(listOf("krig", "artcnn16", "ssim_super", "ssim_down", "cas"), mpvImageShaderKeys(
            MpvImageOptions(scaler = "artcnn16", chromaScaler = "krig", downscaler = "ssim_down", sharpening = "cas", superResolution = true), true,
        ))
    }

    @Test fun casUsesTheDetectedTransferProfile() {
        assertEquals(4, mpvCasTransferProfile("bt.1886")?.sourceTrc)
        assertEquals(3, mpvCasTransferProfile("srgb")?.sourceTrc)
        assertEquals("2.4", mpvCasTransferProfile("gamma2.4")?.customGamma)
        assertEquals(0, mpvCasTransferProfile("linear")?.targetTrc)
        // HDR sources while mpv tone-maps (see HdrShaderGateTest); unknown curves still have none.
        assertEquals(2, mpvCasTransferProfile("pq")?.sourceTrc)
        assertNull(mpvCasTransferProfile("v-log"))
    }

    @Test fun fsrDoesNotDoubleSharpen() {
        assertEquals(listOf("fsr"), mpvImageShaderKeys(MpvImageOptions(scaler = "fsr", sharpening = "cas"), true))
    }

    @Test fun nisDoesNotDoubleSharpen() {
        assertEquals(listOf("nis"), mpvImageShaderKeys(MpvImageOptions(scaler = "nis", sharpening = "adaptive"), true))
    }

    @Test fun fsrAndNisDoNotStackSsimSuperRes() {
        assertEquals(listOf("fsr"), mpvImageShaderKeys(MpvImageOptions(scaler = "fsr", superResolution = true), true))
        assertEquals(listOf("nis"), mpvImageShaderKeys(MpvImageOptions(scaler = "nis", superResolution = true), true))
    }

    @Test fun cpuFiltersOwnOnlyTheirNamedSlots() {
        val filters = mpvCpuImageFilters(MpvImageOptions(denoise = "light", deblock = "medium"), true)
        assertEquals(setOf("plex-denoise", "plex-deblock"), filters.keys)
        assertTrue(filters.values.any { it.contains("hqdn3d") })
        assertTrue(filters.values.any { it.contains("deblock=filter=strong") })
        assertTrue(mpvCpuImageFilters(MpvImageOptions(), true).isEmpty())
    }

    @Test fun cpuFiltersHaveCanonicalOrder() {
        val filters = mpvCpuImageFilters(MpvImageOptions(denoise = "light", deblock = "medium"), true)
        assertEquals(listOf("plex-denoise", "plex-deblock"), orderedMpvCpuImageFilters(filters).map { it.first })
    }

    @Test fun hardwareVlcCannotAccidentallyEnableCpuFilters() {
        val options = vlcImageMediaOptions(VlcImageOptions(softwareProcessing = false, denoise = "medium", deband = "medium", brightness = 15))
        assertEquals(listOf(":swscale-mode=1"), options)
    }

    @Test fun softwareVlcBuildsOneValidatedFilterChain() {
        val options = vlcImageMediaOptions(VlcImageOptions(true, "spline", "light", "medium", brightness = 10))
        assertTrue(":swscale-mode=10" in options)
        assertEquals(1, options.count { it.startsWith(":video-filter=") })
        assertTrue(":video-filter=hqdn3d:gradfun:adjust" in options)
        assertTrue(":brightness=1.1" in options)
        assertFalse(options.any { "sharpen" in it })
    }

    @Test fun everyVlcScalerHasItsOwnDocumentedNativeMode() {
        assertEquals(listOf(0, 1, 2, 4, 5, 6, 7, 8, 9, 10), VideoImageCatalog.vlcScalers.keys.map(::vlcSwscaleMode))
        assertEquals(1, vlcSwscaleMode("invalid"))
    }

    @Test fun rapidVlcRestartsPreservePendingVodPositionAndLiveStartsAtDefault() {
        assertEquals(90000L, vlcRestartPosition(90000, 0, true))
        assertEquals(41000L, vlcRestartPosition(null, 41000, true))
        assertEquals(0L, vlcRestartPosition(90000, 41000, false))
        assertEquals(0L, vlcRestartPosition(null, -1, true))
    }

    @Test fun upscalerShadersLoadOnlyWhenTheFittedVideoIsEnlarged() {
        assertTrue(mpvUpscaling(1280.0, 720.0, 1920, 1080))
        assertFalse(mpvUpscaling(1920.0, 1080.0, 1920, 1080))
        assertFalse(mpvUpscaling(3840.0, 2160.0, 1920, 1080))
        // Scope 1920x800 fits the 1080p surface at 1:1.
        assertFalse(mpvUpscaling(1920.0, 800.0, 1920, 1080))
        assertTrue(mpvUpscaling(0.0, 0.0, 1920, 1080))
        val options = MpvImageOptions(scaler = "artcnn16", chromaScaler = "krig", superResolution = true)
        assertEquals(listOf("krig"), mpvImageShaderKeys(options, sdr = true, upscaling = false))
        assertEquals(listOf("krig", "artcnn16", "ssim_super"), mpvImageShaderKeys(options, sdr = true, upscaling = true))
    }

    @Test fun lumaUpscalerStaysUnloadedUntilItsOwnWhenCanPass() {
        // 720p -> 900p is 1.25×: above the old 1.01 gate, below NNEDI/RAVU Lite (~1.414) and ArtCNN (1.3).
        val mild = mpvFittedScale(1280.0, 720.0, 1600, 900)
        assertTrue(mild > MPV_ANY_UPSCALE_SCALE)
        assertFalse(mpvLumaUpscalerWouldRun("nnedi3_nns32", mild))
        assertFalse(mpvLumaUpscalerWouldRun("ravu_lite_r3", mild))
        assertFalse(mpvLumaUpscalerWouldRun("artcnn16", mild))
        assertTrue(mpvLumaUpscalerWouldRun("anime4k", mild))
        assertTrue(mpvLumaUpscalerWouldRun("ravu_zoom_r3", mild))
        assertTrue(mpvLumaUpscalerWouldRun("fsr", mild))

        val nnedi = MpvImageOptions(scaler = "nnedi3_nns32", chromaScaler = "krig", sharpening = "cas", superResolution = true)
        assertEquals(listOf("krig", "ssim_super", "cas"), mpvImageShaderKeys(nnedi, true, fittedScale = mild))
        // 720p -> 1080p is 1.5×, enough for the 0.707106 NNEDI/RAVU gate.
        val fullHd = mpvFittedScale(1280.0, 720.0, 1920, 1080)
        assertEquals(
            listOf("krig", "nnedi3_nns32", "ssim_super", "cas"),
            mpvImageShaderKeys(nnedi, true, fittedScale = fullHd),
        )
        // Anime4K's WHEN is strict > 1.2 on both axes. Exactly 1.2 does not run.
        assertFalse(mpvLumaUpscalerWouldRun("anime4k_denoise", 1.2))
        assertEquals(
            listOf("ssim_super"),
            mpvImageShaderKeys(MpvImageOptions(scaler = "anime4k", superResolution = true), true, fittedScale = 1.2),
        )
        assertEquals(
            listOf("anime4k"),
            mpvImageShaderKeys(MpvImageOptions(scaler = "anime4k"), true, fittedScale = mild),
        )
        // ArtCNN/FSRCNN/CuNNy need > 1.3. Exactly 1.3 stays on lanczos.
        assertFalse(mpvLumaUpscalerWouldRun("fsrcnnx8", 1.3))
        assertFalse(mpvLumaUpscalerWouldRun("cunny_fast_ds", 1.3))
        assertEquals(emptyList<String>(), mpvImageShaderKeys(MpvImageOptions(scaler = "artcnn32"), true, fittedScale = 1.3))
    }

    @Test fun svpSkipsRendererUpscalersBecauseGlslCannotRunBeforeVf() {
        val options = MpvImageOptions(
            scaler = "nnedi3_nns32", chromaScaler = "krig", downscaler = "ssim_down",
            sharpening = "cas", superResolution = true,
        )
        val scale = mpvFittedScale(1280.0, 720.0, 1920, 1080)
        assertEquals(
            listOf("krig", "ssim_down", "cas"),
            mpvImageShaderKeys(options, true, fittedScale = scale, svpActive = true),
        )
        assertEquals(
            listOf("krig", "nnedi3_nns32", "ssim_super", "ssim_down", "cas"),
            mpvImageShaderKeys(options, true, fittedScale = scale, svpActive = false),
        )
        // Chroma and sharpen hooks are not the upscaler; they stay while SVP interpolates.
        assertEquals(
            listOf("krig", "cas"),
            mpvImageShaderKeys(
                MpvImageOptions(scaler = "anime4k", chromaScaler = "krig", sharpening = "cas"),
                true, fittedScale = scale, svpActive = true,
            ),
        )
    }

    @Test fun renderOverloadNeedsMoreThanTenPercentDroppedFrames() {
        assertFalse(mpvRenderOverloaded(14, 23.976, 6.0))
        assertTrue(mpvRenderOverloaded(15, 23.976, 6.0))
        assertFalse(mpvRenderOverloaded(100, 0.0, 6.0))
    }

    @Test fun svpOverloadAlsoTripsOnNativeCostOrBypassAndUsesAShorterWindow() {
        assertEquals(3, mpvRenderWarmupSeconds(false))
        assertEquals(6.0, mpvRenderSampleSeconds(false), 0.0)
        assertEquals(1, mpvRenderWarmupSeconds(true))
        assertEquals(2.0, mpvRenderSampleSeconds(true), 0.0)
        // 10% of a 2s SVP sample at 60fps is 12 frames.
        assertFalse(mpvRenderOverloaded(12, 60.0, 2.0))
        assertTrue(mpvRenderOverloaded(13, 60.0, 2.0))
        // Disabled gate and engine errors/passthrough are not an overload. Cost and the compute bypass are.
        assertFalse(mpvRenderOverloaded(0, 60.0, 2.0, svpBypassReason = 2))
        assertTrue(mpvRenderOverloaded(0, 60.0, 2.0, svpBypassReason = 1))
        assertFalse(mpvRenderOverloaded(0, 0.0, 0.0, svpBypassReason = 3))
        assertFalse(mpvRenderOverloaded(0, 60.0, 2.0, svpNativeCostUs = 2 * MPV_SVP_SLOW_WINDOW_US))
        assertTrue(mpvRenderOverloaded(0, 60.0, 2.0, svpNativeCostUs = 2 * MPV_SVP_SLOW_WINDOW_US + 1))
        assertFalse(mpvRenderOverloaded(0, 0.0, 0.0, svpNativeCostUs = 5_000_000))
        // VLC's helper stays the dropped-frame check only.
        assertFalse(vlcRenderOverloaded(0, 60.0, 2.0))
    }

    @Test fun qualitySvpStaysOnLightUntilTheProbeClears() {
        assertEquals("light", mpvSvpAppliedMode("quality", false))
        assertEquals("quality", mpvSvpAppliedMode("quality", true))
        assertEquals("balanced", mpvSvpAppliedMode("balanced", false))
        assertEquals("auto", mpvSvpAppliedMode("auto", false))
        assertTrue(mpvSvpFilter(mpvSvpAppliedMode("quality", false), 24.0, 60.0)!!.contains("preset=1"))
        assertTrue(mpvSvpFilter(mpvSvpAppliedMode("quality", true), 24.0, 60.0)!!.contains("preset=3"))
        assertEquals("light", MPV_SVP_PROBE_MODE)
    }

    @Test fun heavySvpFailureKeepsGlobalDefaultsUntilExplicitSave() {
        val live = MpvImageOptions(svpMode = "quality", scaler = "lanczos")
        val stored = MpvImageOptions(svpMode = "quality", scaler = "bilinear", deband = "light")
        val update = mpvSvpLevelAfterHeavyFailure(live, stored, saveImageDefaults = false)
        assertNotNull(update)
        assertEquals("light", update!!.live.svpMode)
        assertEquals("lanczos", update.live.scaler)
        assertEquals("quality", update.stored.svpMode)
        assertEquals("bilinear", update.stored.scaler)
        assertEquals("light", update.stored.deband)
        assertFalse(update.persisted)
        // A session-only quality trial must not overwrite a different saved preset.
        val trial = mpvSvpLevelAfterHeavyFailure(live, MpvImageOptions(svpMode = "balanced", scaler = "bilinear"), saveImageDefaults = false)
        assertEquals("light", trial!!.live.svpMode)
        assertEquals("balanced", trial.stored.svpMode)
        assertFalse(trial.persisted)
        val savedDefaults = mpvSvpLevelAfterHeavyFailure(live, stored, saveImageDefaults = true)
        assertEquals("light", savedDefaults!!.stored.svpMode)
        assertEquals("lanczos", savedDefaults.stored.scaler)
        assertNull(mpvSvpLevelAfterHeavyFailure(MpvImageOptions(svpMode = "light"), stored))
        val saved = MpvImageOptions(svpMode = "quality")
        assertEquals("off", effectiveMpvImageOptions(saved, reducedEffects = true, allowSvp = true).svpMode)
    }

    @Test fun renderWatchdogRestartsForEveryLiveProcessingChange() {
        val base = mpvRenderCheckSignature(emptyList(), emptyMap(), false, false, null, MpvImageOptions())
        val deinterlace = mpvRenderCheckSignature(emptyList(), emptyMap(), true, false, "lavfi=[bwdif]", MpvImageOptions())
        val svpLight = mpvRenderCheckSignature(emptyList(), mapOf("plex-svp" to "ambientsvp=preset=1"), false, false, null, MpvImageOptions())
        val svpQuality = mpvRenderCheckSignature(emptyList(), mapOf("plex-svp" to "ambientsvp=preset=3"), false, false, null, MpvImageOptions())
        val shader = mpvRenderCheckSignature(listOf("artcnn16"), emptyMap(), false, false, null, MpvImageOptions())

        assertNull(base)
        assertNotEquals(base, deinterlace)
        assertNotEquals(svpLight, svpQuality)
        assertNotEquals(base, shader)
    }

    @Test fun vlcWatchdogUsesOnlyActiveSoftwareVideoProcessing() {
        assertNull(vlcRenderCheckSignature(VlcImageOptions(), "off", "auto"))
        assertNotNull(vlcRenderCheckSignature(VlcImageOptions(softwareProcessing = true, denoise = "medium"), "off", "auto"))
        assertNotNull(vlcRenderCheckSignature(VlcImageOptions(), "auto", "yadif"))
        assertNotNull(vlcRenderCheckSignature(VlcImageOptions(svpMode = "balanced"), "off", "auto"))
        assertNotEquals(
            vlcRenderCheckSignature(VlcImageOptions(svpMode = "light"), "off", "auto"),
            vlcRenderCheckSignature(VlcImageOptions(svpMode = "quality"), "off", "auto"),
        )
        assertFalse(vlcRenderOverloaded(14, 23.976, 6.0))
        assertTrue(vlcRenderOverloaded(15, 23.976, 6.0))
    }

    @Test fun sparsamForcesCheapMpvEffectiveOptionsWithoutTouchingIntentionalAdjust() {
        val saved = MpvImageOptions(
            scaler = "artcnn16", chromaScaler = "krig", downscaler = "ssim_down",
            deband = "strong", sharpening = "cas", denoise = "medium", deblock = "light",
            superResolution = true, antiring = 80, sigmoid = true,
            brightness = 5, contrast = -3, saturation = 2, gamma = 1, toneMapping = "hable",
        )
        val effective = effectiveMpvImageOptions(saved, reducedEffects = true)
        assertEquals("bilinear", effective.scaler)
        assertEquals("bilinear", effective.chromaScaler)
        assertEquals("bilinear", effective.downscaler)
        assertEquals("off", effective.deband)
        assertEquals("off", effective.sharpening)
        assertEquals("off", effective.denoise)
        assertEquals("off", effective.deblock)
        assertFalse(effective.superResolution)
        assertEquals(0, effective.antiring)
        assertFalse(effective.sigmoid)
        assertEquals(5, effective.brightness)
        assertEquals(-3, effective.contrast)
        assertEquals(2, effective.saturation)
        assertEquals(1, effective.gamma)
        assertEquals("hable", effective.toneMapping)
        assertTrue(mpvImageShaderKeys(effective, sdr = true).isEmpty())
        assertTrue(mpvCpuImageFilters(effective, sdr = true).isEmpty())
        // Leaving Low restores the saved selection.
        assertEquals(listOf("krig", "artcnn16", "ssim_super", "ssim_down", "cas"),
            mpvImageShaderKeys(effectiveMpvImageOptions(saved, reducedEffects = false), sdr = true))
    }

    @Test fun sparsamForcesVlcHardwareDirectWithoutSoftwareFilters() {
        val saved = VlcImageOptions(softwareProcessing = true, scaler = "lanczos", denoise = "medium", deband = "light", brightness = 8)
        val effective = effectiveVlcImageOptions(saved, reducedEffects = true)
        assertFalse(effective.softwareProcessing)
        assertEquals("fast_bilinear", effective.scaler)
        assertEquals("off", effective.denoise)
        assertEquals("off", effective.deband)
        assertEquals(8, effective.brightness)
        assertEquals(listOf(":swscale-mode=0"), vlcImageMediaOptions(effective))
        assertTrue(vlcImageMediaOptions(effectiveVlcImageOptions(saved, false)).any { it.startsWith(":video-filter=") })
    }

    @Test fun sparsamDisablesMedia3GpuEffectChain() {
        val saved = com.ambient.tv.data.local.Media3ImageOptions(enabled = true, scaler = "lanczos", brightness = 4)
        val effective = effectiveMedia3ImageOptions(saved, reducedEffects = true)
        assertFalse(effective.enabled)
        assertEquals("lanczos", effective.scaler) // preference retained for restore
        assertEquals(4, effective.brightness)
        assertTrue(effectiveMedia3ImageOptions(saved, false).enabled)
    }

    @Test fun sparsamSuppressesOnlyDoubleRateDeinterlace() {
        assertEquals("yadif", effectiveDeinterlaceFilter("yadif2x", true))
        assertEquals("bwdif", effectiveDeinterlaceFilter("bwdif2x", true))
        assertEquals("yadif", effectiveDeinterlaceFilter("bob", true))
        assertEquals("yadif", effectiveDeinterlaceFilter("yadif", true))
        assertEquals("auto", effectiveDeinterlaceFilter("auto", true))
        assertEquals("yadif2x", effectiveDeinterlaceFilter("yadif2x", false))
    }

    @Test fun sparsamForcesScreensaverEconomyQuality() {
        assertEquals("economy", effectiveScreensaverQualityKey("high", true))
        assertEquals("high", effectiveScreensaverQualityKey("high", false))
    }



    @Test fun heavyFiltersUseAShortRenderSampleAndBuiltinsKeepTheLongOne() {
        assertEquals(3, mpvRenderWarmupSeconds(false))
        assertEquals(6.0, mpvRenderSampleSeconds(false), 0.0)
        assertEquals(1, mpvRenderWarmupSeconds(true))
        assertEquals(2.0, mpvRenderSampleSeconds(true), 0.0)
        assertFalse(mpvRenderOverloaded(12, 60.0, 2.0))
        assertTrue(mpvRenderOverloaded(13, 60.0, 2.0))
        assertTrue(mpvFastRenderSample(true, "nnedi", false))
        assertTrue(mpvFastRenderSample(true, "nnedi2x", false))
        assertFalse(mpvFastRenderSample(true, "yadif", false))
        assertFalse(mpvFastRenderSample(false, "nnedi", false))
        assertTrue(mpvFastRenderSample(false, "yadif", true))
        assertFalse(mpvFastRenderSample(false, "lanczos", false))
        listOf(
            "nnedi3_nns16", "ravu_lite_r2", "anime4k", "fsrcnnx8", "artcnn16",
            "cunny_fast_ds", "fsr", "nis",
        ).forEach { assertTrue(it, mpvHeavyLumaUpscaler(it)) }
        assertFalse(mpvHeavyLumaUpscaler("lanczos"))
        assertFalse(mpvHeavyLumaUpscaler("ewa_lanczos"))
        assertFalse(mpvHeavyLumaUpscaler("krig"))
        assertTrue(mpvHeavyScalerLoaded("artcnn16", listOf("krig", "artcnn16")))
        assertFalse(mpvHeavyScalerLoaded("bilinear", listOf("artcnn16")))
        assertFalse(mpvHeavyScalerLoaded("artcnn16", listOf("krig")))
    }

    @Test fun removingALumaShaderDropsCopyBackUnlessSomethingElseNeedsIt() {
        assertTrue(mpvCopyBackNeeded(listOf("artcnn16"), cpuFilters = false, deinterlaceEnabled = false))
        assertTrue(mpvCopyBackNeeded(listOf("nnedi3_nns64"), cpuFilters = false, deinterlaceEnabled = false))
        assertFalse(mpvCopyBackNeeded(emptyList(), cpuFilters = false, deinterlaceEnabled = false))
        assertFalse(mpvCopyBackNeeded(listOf("anime4k"), cpuFilters = false, deinterlaceEnabled = false))
        assertTrue(mpvCopyBackNeeded(listOf("krig"), cpuFilters = false, deinterlaceEnabled = false))
        assertTrue(mpvCopyBackNeeded(emptyList(), cpuFilters = true, deinterlaceEnabled = false))
        assertTrue(mpvCopyBackNeeded(emptyList(), cpuFilters = false, deinterlaceEnabled = true))
        val eased = mpvImageShaderKeys(MpvImageOptions(scaler = "lanczos", chromaScaler = "lanczos"), sdr = true)
        assertTrue(eased.isEmpty())
        assertFalse(mpvCopyBackNeeded(eased, cpuFilters = false, deinterlaceEnabled = false))
    }

    @Test fun nnediDeinterlaceFallsBackOneStepAndDoesNotClobberADifferentPreset() {
        val toYadif = mpvHeavyProcessingBackoff(
            deinterlaceEnabled = true,
            deinterlaceMode = "on",
            deinterlaceFilter = "nnedi2x",
            storedDeinterlaceMode = "on",
            storedDeinterlaceFilter = "nnedi2x",
            steppedFromNnedi = false,
            allowDeinterlacePersist = true,
            liveImage = MpvImageOptions(scaler = "artcnn16", deband = "light"),
            storedImage = MpvImageOptions(scaler = "artcnn16", deband = "medium"),
            heavyScalerLoaded = true,
            saveImageDefaults = false,
        )
        assertTrue(toYadif is MpvHeavyBackoff.Deinterlace)
        val yadif = (toYadif as MpvHeavyBackoff.Deinterlace).update
        assertEquals("on", yadif.mode)
        assertEquals("yadif", yadif.filter)
        assertEquals("on", yadif.storedMode)
        assertEquals("yadif", yadif.storedFilter)
        assertTrue(yadif.persisted)
        assertFalse(yadif.turnedOff)

        val trial = mpvDeinterlaceAfterOverload(
            liveMode = "auto", liveFilter = "nnedi", storedMode = "on", storedFilter = "bwdif",
            step = MpvDeinterlaceStep.YADIF, allowPersist = true,
        )
        assertEquals("auto", trial.mode)
        assertEquals("yadif", trial.filter)
        assertEquals("on", trial.storedMode)
        assertEquals("bwdif", trial.storedFilter)
        assertFalse(trial.persisted)

        val reduced = mpvDeinterlaceAfterOverload(
            liveMode = "on", liveFilter = "nnedi", storedMode = "on", storedFilter = "nnedi",
            step = MpvDeinterlaceStep.YADIF, allowPersist = false,
        )
        assertEquals("yadif", reduced.filter)
        assertEquals("nnedi", reduced.storedFilter)
        assertFalse(reduced.persisted)

        val shaderFirst = mpvHeavyProcessingBackoff(
            deinterlaceEnabled = true,
            deinterlaceMode = "on",
            deinterlaceFilter = "yadif",
            storedDeinterlaceMode = "on",
            storedDeinterlaceFilter = "yadif",
            steppedFromNnedi = true,
            allowDeinterlacePersist = true,
            liveImage = MpvImageOptions(scaler = "ravu_lite_r3", brightness = 2),
            storedImage = MpvImageOptions(scaler = "ravu_lite_r3", brightness = 0, deband = "light"),
            heavyScalerLoaded = true,
            saveImageDefaults = false,
        )
        assertTrue(shaderFirst is MpvHeavyBackoff.Upscaler)
        assertNull(mpvHeavyProcessingBackoff(
            deinterlaceEnabled = true, deinterlaceMode = "on", deinterlaceFilter = "yadif",
            storedDeinterlaceMode = "on", storedDeinterlaceFilter = "yadif",
            steppedFromNnedi = false, allowDeinterlacePersist = true,
            liveImage = MpvImageOptions(), storedImage = MpvImageOptions(),
            heavyScalerLoaded = false, saveImageDefaults = false,
        ))
        val off = mpvHeavyProcessingBackoff(
            deinterlaceEnabled = true, deinterlaceMode = "auto", deinterlaceFilter = "yadif",
            storedDeinterlaceMode = "auto", storedDeinterlaceFilter = "yadif",
            steppedFromNnedi = true, allowDeinterlacePersist = true,
            liveImage = MpvImageOptions(scaler = "lanczos"), storedImage = MpvImageOptions(scaler = "lanczos"),
            heavyScalerLoaded = false, saveImageDefaults = false,
        )
        assertTrue(off is MpvHeavyBackoff.Deinterlace)
        val disabled = (off as MpvHeavyBackoff.Deinterlace).update
        assertEquals("off", disabled.mode)
        assertEquals("yadif", disabled.filter)
        assertEquals("off", disabled.storedMode)
        assertEquals("yadif", disabled.storedFilter)
        assertTrue(disabled.persisted)
        assertTrue(disabled.turnedOff)
    }

    @Test fun heavyUpscalerFallsBackToLanczosWithoutTouchingOtherImageFields() {
        val live = MpvImageOptions(scaler = "artcnn32", deband = "light", brightness = 4, chromaScaler = "krig")
        val stored = MpvImageOptions(scaler = "artcnn32", deband = "medium", brightness = 0, sharpening = "cas")
        val update = mpvUpscalerAfterHeavyFailure(live, stored, saveImageDefaults = false)
        assertNotNull(update)
        assertEquals("lanczos", update!!.live.scaler)
        assertEquals("light", update.live.deband)
        assertEquals(4, update.live.brightness)
        assertEquals("krig", update.live.chromaScaler)
        assertEquals("artcnn32", update.stored.scaler)
        assertEquals("medium", update.stored.deband)
        assertEquals(0, update.stored.brightness)
        assertEquals("cas", update.stored.sharpening)
        assertFalse(update.persisted)
        assertEquals(listOf("krig"), mpvImageShaderKeys(update.live, sdr = true))
        assertFalse(mpvImageShaderKeys(update.live, sdr = true).contains("artcnn32"))

        val trial = mpvUpscalerAfterHeavyFailure(live, MpvImageOptions(scaler = "bilinear", deband = "strong"), saveImageDefaults = false)
        assertEquals("lanczos", trial!!.live.scaler)
        assertEquals("bilinear", trial.stored.scaler)
        assertEquals("strong", trial.stored.deband)
        assertFalse(trial.persisted)

        val defaults = mpvUpscalerAfterHeavyFailure(live, stored.copy(scaler = "ewa_lanczos"), saveImageDefaults = true)
        assertEquals("lanczos", defaults!!.stored.scaler)
        assertEquals("medium", defaults.stored.deband)
        assertEquals(0, defaults.stored.brightness)
        assertEquals("cas", defaults.stored.sharpening)
        assertTrue(defaults.persisted)
        assertNull(mpvUpscalerAfterHeavyFailure(MpvImageOptions(scaler = "lanczos"), stored))
        assertEquals("bilinear", effectiveMpvImageOptions(live, reducedEffects = true).scaler)
        assertFalse(mpvHeavyScalerLoaded(effectiveMpvImageOptions(live, reducedEffects = true).scaler, listOf("artcnn32")))
    }

    @Test fun hdrTransferHidesOnlyConfirmedHdrNotUnknownOrSdr() {
        listOf("pq", "HLG", "smpte2084", "st2084", "arib-std-b67").forEach {
            assertTrue(it, isMpvHdrTransfer(it))
            assertFalse(it, isMpvSdrTransfer(it))
        }
        assertFalse(isMpvHdrTransfer(null))
        assertFalse(isMpvHdrTransfer("unknown"))
        assertFalse(isMpvHdrTransfer("bt.1886"))
        assertTrue(isMpvSdrTransfer("bt.1886"))
        assertEquals(com.ambient.tv.ui.player.HdrStandard.HDR10, hdrStandardFromMpvTransfer("pq"))
        assertEquals(com.ambient.tv.ui.player.HdrStandard.HLG, hdrStandardFromMpvTransfer("HLG"))
        assertNull(hdrStandardFromMpvTransfer("bt.1886"))
    }

    @Test fun nativeHdrSuspendsVlcCpuFiltersWithoutErasingThePreset() {
        val saved = VlcImageOptions(softwareProcessing = true, denoise = "medium", deband = "light",
            brightness = 12, svpMode = "balanced")
        val active = vlcOptionsForNativeHdr(saved, true)
        assertFalse(active.softwareProcessing)
        assertEquals("off", active.denoise)
        assertEquals("off", active.deband)
        assertEquals("off", active.svpMode)
        assertEquals(0, active.brightness)
        assertEquals("medium", saved.denoise)
        assertEquals(saved, vlcOptionsForNativeHdr(saved, false))
    }

    @Test fun vlcScalerStaysAvailableWhenSoftwareProcessingUsesSwscale() {
        assertTrue(vlcSurfaceBypassesSwscale("android", softwareProcessing = false))
        assertTrue(vlcSurfaceBypassesSwscale("auto", softwareProcessing = false))
        assertFalse(vlcSurfaceBypassesSwscale("vulkan", softwareProcessing = false))
        assertFalse(vlcSurfaceBypassesSwscale("android", softwareProcessing = true))
        assertFalse(vlcSurfaceBypassesSwscale("auto", softwareProcessing = true))
        assertTrue(vlcSurfaceBypassesSwscale(androidSurface = true, softwareProcessing = false))
        assertFalse(vlcSurfaceBypassesSwscale(androidSurface = true, softwareProcessing = true))
        assertFalse(vlcSurfaceBypassesSwscale(androidSurface = false, softwareProcessing = false))
    }

    @Test fun hdrOnSdrDisplayStillSuspendsCpuFiltersAndRetainsSavedPreset() {
        val saved = VlcImageOptions(softwareProcessing = true, denoise = "medium", deband = "light",
            brightness = 12, svpMode = "balanced")
        for (standard in listOf(com.ambient.tv.ui.player.HdrStandard.HDR10, com.ambient.tv.ui.player.HdrStandard.HLG)) {
            val active = vlcOptionsForNativeHdr(saved, nativeHdrPreferred = false, sourceHdrStandard = standard)
            assertFalse(active.softwareProcessing)
            assertEquals("off", active.svpMode)
            assertEquals("off", active.denoise)
            assertEquals(0, active.brightness)
        }
        assertTrue(saved.softwareProcessing)
        assertEquals(saved, vlcOptionsForNativeHdr(saved, false, null))
    }

}
