package com.ambient.tv.ui.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class SettingsCopyTest {
    private fun strings(locale: String, file: String = "strings.xml"): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val nodes = factory.newDocumentBuilder().parse(File("src/main/res/$locale/$file"))
            .getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    private val revisedKeys = listOf(
        "settings_display_adjustment_hint", "settings_display_modes_allowed_hint",
        "settings_screensaver_mode_hint", "tvh_deinterlace_method", "tvh_deinterlace_media3_hint",
        "settings_spotify_web_subtitle", "settings_spotify_audio_quality_subtitle",
        "settings_spotify_native_note", "spotify_auth_client_id_missing",
        "settings_spotify_client_missing_build", "settings_high_quality_assets", "settings_ui_language",
        "player_mpv_gpu_filters_direct_hint",
        "image_motion",
        "settings_audio_source_hint", "settings_import_draft_hint",
    )

    @Test fun revisedCopyExistsInEveryLanguageAndEnglishMatchesFallback() {
        val fallback = strings("values")
        for (locale in listOf("values-de", "values-en")) {
            val localized = strings(locale)
            for (key in revisedKeys) {
                assertFalse("$locale/$key", localized.getValue(key).isBlank())
                if (locale == "values-en") assertEquals(key, fallback.getValue(key), localized.getValue(key))
            }
        }
    }

    @Test fun ordinaryHintsDoNotContainDesignReferencesOrImplementationNames() {
        val artifacts = Regex("\\bkodi\\b|libplacebo|librespot|tscale=|display-resample|PKCE|Whitelist|Audio.Hub", RegexOption.IGNORE_CASE)
        for (locale in listOf("values", "values-en", "values-de")) {
            val localized = strings(locale)
            revisedKeys.forEach { key -> assertFalse("$locale/$key", artifacts.containsMatchIn(localized.getValue(key))) }
        }
        for (locale in listOf("values", "values-de")) {
            val vulkan = strings(locale, "vulkan.xml")
            assertEquals("Vulkan", vulkan.getValue("renderer_output_vulkan"))
            assertFalse(artifacts.containsMatchIn(vulkan.getValue("renderer_vlc_output_hint")))
        }
    }

    @Test fun guidanceNamesCurrentPlayerOptions() {
        for (locale in listOf("values", "values-en", "values-de")) {
            val localized = strings(locale)
            // Short, jargon-free hints: name the players to switch to, not their render backends.
            for (key in listOf("player_mpv_gpu_filters_direct_hint", "tvh_deinterlace_media3_hint")) {
                assertFalse("$locale/$key", localized.getValue(key).contains("OpenGL"))
                assertFalse("$locale/$key", localized.getValue(key).contains("Vulkan"))
            }
            assertTrue(localized.getValue("tvh_deinterlace_media3_hint").contains("MPV"))
            assertTrue(localized.getValue("tvh_deinterlace_media3_hint").contains("VLC"))
            assertTrue(localized.getValue("settings_spotify_client_missing_build").contains("Spotify ›"))
            assertTrue(localized.getValue("settings_spotify_client_missing_build")
                .contains(localized.getValue("settings_area_streaming")))
        }
    }

    @Test fun labelsUseReadableCaseAndLanguage() {
        assertEquals("Verfahren", strings("values-de").getValue("tvh_deinterlace_method"))
        assertEquals("Method", strings("values-en").getValue("tvh_deinterlace_method"))
        assertEquals("Hochauflösende Bilder", strings("values-de").getValue("settings_high_quality_assets"))
        assertEquals("Spektrum", strings("values-de").getValue("spotify_visual_spectrum"))
    }
}
