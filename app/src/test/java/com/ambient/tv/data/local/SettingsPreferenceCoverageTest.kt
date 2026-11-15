package com.ambient.tv.data.local

import java.lang.reflect.Modifier
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comparing two exports cannot detect a setting missing from both. Every mutable preference must
 * instead have a backup field, an explicit nested mapping, or a documented local-only reason.
 * Round-trip tests separately prove that those fields are actually exported and applied.
 */
@OptIn(ExperimentalSerializationApi::class)
class SettingsPreferenceCoverageTest {
    private fun SerialDescriptor.names(): Set<String> = (0 until elementsCount).map(::getElementName).toSet()

    private val nested = mapOf(
        "weatherSettings" to "weather",
        "clockSettings" to "clock",
        "showContinueWatchingRow" to "showContinueWatchingHub",
        "showRecentHomeRows" to "showRecentHomeHubs",
        "showCollectionHomeRows" to "showCollectionHomeHubs",
        // These properties edit the selected account; backing up the profile list includes them.
        "host" to "servers", "port" to "servers", "plexUseHttps" to "servers",
        "macAddress" to "servers", "autoWakeEnabled" to "servers",
        "jellyfinHost" to "jellyfinServers", "jellyfinPort" to "jellyfinServers",
        "jellyfinUseHttps" to "jellyfinServers", "jellyfinBasePath" to "jellyfinServers",
        "jellyfinUsername" to "jellyfinServers", "jellyfinUserId" to "jellyfinServers",
        "jellyfinMacAddress" to "jellyfinServers", "jellyfinAutoWakeEnabled" to "jellyfinServers",
        "embyHost" to "embyServers", "embyPort" to "embyServers",
        "embyUseHttps" to "embyServers", "embyBasePath" to "embyServers",
        "embyUsername" to "embyServers", "embyUserId" to "embyServers",
        "embyMacAddress" to "embyServers", "embyAutoWakeEnabled" to "embyServers",
    )

    private val localOnly = mapOf(
        "youtubeTvLinkEnabled" to "This device's receiver permission and paired phones",
        "dlnaRendererEnabled" to "This device's receiver permission",
        "dlnaRendererAsk" to "This device's permission prompt policy",
        "dlnaRendererName" to "Receiver identity",
        "dlnaRendererTrusted" to "Sender trust on this device",
        "diagnosticLogEnabled" to "Device-local diagnostic collection",
        "hbbtvConsent" to "Device-local consent",
        "initialSetupCompleted" to "Setup state",
        "mpvZeroCopyFailed" to "Hardware probe result",
        "playbackBookmarksJson" to "Viewing history, not settings",
        "plexLoginVerified" to "Verification of this device's login",
        "tvheadendLoginState" to "Verification of this device's login",
        "tvheadendLoginVerified" to "Legacy login-state alias",
        "videoScopeChoices" to "The player's last editing target, not its saved picture settings",
    )

    @Test fun everyMutablePreferenceHasAnExplicitBackupDecision() {
        val methods = AppPreferences::class.java.declaredMethods
        val mutable = methods.filter { setter ->
            Modifier.isPublic(setter.modifiers) && !Modifier.isStatic(setter.modifiers) &&
                setter.name.startsWith("set") && setter.parameterCount == 1 &&
                methods.any { getter -> getter.name == "get${setter.name.removePrefix("set")}" &&
                    getter.parameterCount == 0 && getter.returnType == setter.parameterTypes.single() }
        }.map { it.name.removePrefix("set").substringBefore('$').replaceFirstChar(Char::lowercaseChar) }.toSet()
        val top = SettingsBackup.serializer().descriptor.names()
        val saved = top + PortableSettingsExtras.serializer().descriptor.names() +
            DeviceSettingsExtras.serializer().descriptor.names() + ConnectionSettingsExtras.serializer().descriptor.names()

        assertTrue("The inventory must inspect the full preferences class", mutable.size > 250)
        assertEquals("New preferences need backup/share coverage or a specific local-state reason",
            emptySet<String>(), mutable - saved - nested.keys - localOnly.keys)
        assertEquals("Remove obsolete aliases and exclusions", emptySet<String>(), (nested.keys + localOnly.keys) - mutable)
        assertTrue("Nested mappings must point to real backup fields", top.containsAll(nested.values))
        assertTrue("Every exclusion needs an explanation", localOnly.values.all(String::isNotBlank))
    }
}
