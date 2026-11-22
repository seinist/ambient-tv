package com.ambient.tv.network

import android.app.Application
import android.content.Context
import android.os.Looper
import com.ambient.tv.data.browse.BrowseScopes
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.domain.model.EmbyServerProfile
import com.ambient.tv.domain.model.JellyfinServerProfile
import com.ambient.tv.domain.model.ServerProfile
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class MediaArtworkIdentityIntegrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var preferences: AppPreferences
    private lateinit var tokens: TokenStore
    private lateinit var scopes: BrowseScopes
    private lateinit var refresh: MediaArtworkRefresh
    private lateinit var credentials: android.content.SharedPreferences

    @Before fun before() {
        val regular = context.getSharedPreferences("artwork-regular", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        credentials = context.getSharedPreferences("artwork-credentials", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        preferences = AppPreferences(regular, regular)
        tokens = TokenStore(credentials, preferences)
        scopes = BrowseScopes(preferences, tokens)
        preferences.serverProfiles = listOf(ServerProfile("p", "Plex", "proxy.test", 443, true))
        preferences.activeServerId = "p"; preferences.host = "proxy.test"; preferences.port = 443; preferences.plexUseHttps = true
        preferences.jellyfinServerProfiles = listOf(JellyfinServerProfile("j", "JF", "proxy.test", 443, true, "/jf", userId = "j-a", authenticated = true))
        preferences.activeJellyfinServerId = "j"; preferences.jellyfinHost = "proxy.test"; preferences.jellyfinPort = 443
        preferences.jellyfinUseHttps = true; preferences.jellyfinBasePath = "/jf"; preferences.jellyfinUserId = "j-a"; preferences.jellyfinEnabled = true
        preferences.embyServerProfiles = listOf(EmbyServerProfile("e", "Emby", "proxy.test", 443, true, "/emby", userId = "e-a", authenticated = true))
        preferences.activeEmbyServerId = "e"; preferences.embyHost = "proxy.test"; preferences.embyPort = 443
        preferences.embyUseHttps = true; preferences.embyBasePath = "/emby"; preferences.embyUserId = "e-a"; preferences.embyEnabled = true
        tokens.setTokenForServer("p", "plex-secret")
        tokens.setJellyfinTokenForServer("j", "jf-secret-a")
        tokens.setEmbyTokenForServer("e", "emby-secret")
        refresh = MediaArtworkRefresh(context, preferences, scopes, regular, credentials)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun resizeVariantsShareRevisionIdentityWhileBinaryKeysAndAccountsRemainDistinct() {
        val url = "https://proxy.test/jf/Items/42/Images/Primary?maxWidth=960&ApiKey=jf-secret-a"
        val first = requireNotNull(refresh.address(url))
        val variant = requireNotNull(refresh.address(url.replace("960", "1280")))
        assertEquals(first.imageKey, variant.imageKey)
        assertNotEquals(first.baseKey, variant.baseKey)
        assertEquals(scopes.forProvider("jellyfin"), first.providerScope)
        assertFalse(first.baseKey.contains("secret"))
        assertSame(first, refresh.address(url))
        val sourceVersion = refresh.sources.value
        tokens.setJellyfinTokenForServer("j", "jf-secret-b")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(refresh.sources.value > sourceVersion)
        val changed = requireNotNull(refresh.address(url))
        assertNotEquals(first.imageKey, changed.imageKey)
        assertNotEquals(first.baseKey, changed.baseKey)
    }

    @Test fun explicitHeaderImagesAndSignedUrlsKeepTheirOwnCredentialsAndQuerySemantics() {
        val url = "https://external.test/image?ticket=one"
        assertNull(refresh.address(url))
        val first = requireNotNull(refresh.address(url, mapOf("Authorization" to "Basic secret-a")))
        assertNotEquals(first.baseKey, refresh.address(url, mapOf("Authorization" to "Basic secret-b"))?.baseKey)
        assertNotEquals(first.baseKey, refresh.address(url.replace("one", "two"), mapOf("Authorization" to "Basic secret-a"))?.baseKey)
        assertFalse(first.baseKey.contains("secret"))
        assertFalse(first.baseKey.contains("ticket"))
        assertNull(first.providerScope)
        val providerUrl = "https://proxy.test/jf/Items/42/Images/Primary?ApiKey=explicit-query-one"
        val explicit = requireNotNull(refresh.address(providerUrl, mapOf("Authorization" to "Basic explicit-account")))
        assertNull(explicit.providerScope)
        assertNotEquals(explicit.baseKey, refresh.address(providerUrl.replace("one", "two"), mapOf("Authorization" to "Basic explicit-account"))?.baseKey)
        tokens.setJellyfinTokenForServer("j", "another-automatic-account")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(explicit, refresh.address(providerUrl, mapOf("Authorization" to "Basic explicit-account")))
    }

    @Test fun headerInjectionAndCacheIdentityUseTheSameReverseProxyProvider() {
        val captured = mutableListOf<Request>()
        val client = client(captured)
        for ((path, expected) in listOf("/jf/Items/42/Images/Primary" to "jf-secret-a", "/emby/Items/42/Images/Primary" to "emby-secret")) {
            val url = "https://proxy.test$path"
            val identity = requireNotNull(refresh.address(url))
            client.newCall(Request.Builder().url(url).header(MEDIA_ARTWORK_SCOPE_HEADER, identity.providerScope!!).build()).execute().close()
            val actual = captured.last()
            assertEquals(expected, actual.header("X-Emby-Token"))
            assertNull(actual.header("X-Plex-Token"))
            assertNull(actual.header(MEDIA_ARTWORK_SCOPE_HEADER))
        }
    }

    @Test fun accountChangeAfterCoilIdentityCannotSendAnotherAccountsBytesUnderTheOldKey() {
        val url = "https://proxy.test/jf/Items/42/Images/Primary?ApiKey=jf-secret-a"
        val old = requireNotNull(refresh.address(url))
        tokens.setJellyfinTokenForServer("j", "jf-secret-b")
        shadowOf(Looper.getMainLooper()).idle()
        val captured = mutableListOf<Request>()
        val client = client(captured)
        try {
            client.newCall(Request.Builder().url(url).header(MEDIA_ARTWORK_SCOPE_HEADER, old.providerScope!!).build()).execute().close()
            fail("An obsolete account fence must fail before network dispatch")
        } catch (_: IOException) { /* expected */ }
        assertTrue(captured.isEmpty())
        val current = requireNotNull(refresh.address(url))
        client.newCall(Request.Builder().url(url).header(MEDIA_ARTWORK_SCOPE_HEADER, current.providerScope!!).build()).execute().close()
        assertEquals("jf-secret-b", captured.single().header("X-Emby-Token"))
        assertEquals("jf-secret-b", captured.single().url.queryParameter("ApiKey"))
    }

    @Test fun explicitAuthorizationStillWinsOverAutomaticProviderTokens() {
        val captured = mutableListOf<Request>()
        client(captured).newCall(Request.Builder().url("https://proxy.test/jf/Items/42/Images/Primary")
            .header("Authorization", "Basic explicitly-selected").build()).execute().close()
        assertEquals("Basic explicitly-selected", captured.single().header("Authorization"))
        assertNull(captured.single().header("X-Emby-Token"))
        assertNull(captured.single().header("X-Plex-Token"))
    }

    @Test fun credentialReadRaceCannotValidateTokenAUsingASecondReadOfTokenB() {
        tokens.setJellyfinTokenForServer("j", "jf-secret-b")
        val expectedB = scopes.forProvider("jellyfin")
        var reads = 0
        val changingCredentials = object : android.content.SharedPreferences by credentials {
            override fun getString(key: String?, fallback: String?): String? {
                if (key == "jellyfin_token_j" && ++reads == 1) return "jf-secret-a"
                return credentials.getString(key, fallback)
            }
        }
        val changingTokens = TokenStore(changingCredentials, preferences)
        val changingScopes = BrowseScopes(preferences, changingTokens)
        val captured = mutableListOf<Request>()
        val client = client(captured, MediaImageAuthInterceptor(preferences, changingTokens, changingScopes))
        try {
            client.newCall(Request.Builder().url("https://proxy.test/jf/Items/42/Images/Primary")
                .header(MEDIA_ARTWORK_SCOPE_HEADER, expectedB).build()).execute().close()
            fail("The credential being injected must itself match the expected scope")
        } catch (_: IOException) { /* expected */ }
        assertTrue(captured.isEmpty())
        assertEquals(1, reads)
    }

    private fun client(captured: MutableList<Request>, auth: MediaImageAuthInterceptor = MediaImageAuthInterceptor(preferences, tokens, scopes)): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(auth)
        .addInterceptor { chain ->
            captured += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(byteArrayOf(1).toResponseBody()).build()
        }.build()
}
