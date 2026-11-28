package com.ambient.tv.modules

import org.junit.Assert.*
import org.junit.Test

class ModuleDexOwnershipTest {
    @Test fun monogramOwnsImplementationsButNeverHostPorts() {
        val owner = ModuleDexOwnership.MONOGRAM
        assertTrue(owner.owns("com.ambient.tv.data.telegram.MonogramNativeBackend"))
        assertTrue(owner.owns("com.ambient.tv.data.telegram.MonogramNativeBackend\$Inner"))
        assertTrue(owner.owns("com.sun.jna.Native"))
        assertFalse(owner.owns("com.ambient.tv.data.telegram.MonogramNativeBackendOther"))
        assertFalse(owner.owns("com.ambient.tv.data.telegram.MonogramModuleFactory"))
        assertFalse(owner.owns("com.ambient.tv.data.telegram.TelegramMedia"))
        assertFalse(owner.owns("kotlin.Unit"))
    }

    @Test fun completeAdapterMustContainAllMandatoryImplementationsAndNoForeignTypes() {
        val owner = ModuleDexOwnership.MONOGRAM
        val complete = setOf(
            "com.ambient.tv.data.telegram.MonogramModuleAdapter",
            "com.ambient.tv.data.telegram.MonogramNativeBackend",
            "com.ambient.tv.data.telegram.AmbientMonogramJni",
            "org.monogram.mtproto.MtprotoNativeLoader",
            "com.sun.jna.Native",
            "org.rustls.platformverifier.Verifier",
            "uniffi.monogram_mtproto.UniffiLib",
        )
        assertTrue(owner.accepts(complete))
        assertFalse(owner.accepts(complete - "com.sun.jna.Native"))
        assertFalse(owner.accepts(complete + "com.ambient.tv.data.telegram.MonogramModuleFactory"))
    }
    @Test fun smartTubeOwnsUpstreamCodeButNotHostResourcesOrSharedDependencies() {
        val ownership = ModuleDexOwnership.SMARTTUBE
        val required = setOf(
            "com.ambient.tv.youtube.SmartTubeModuleAdapter",
            "com.liskovsoft.youtubeapi.service.YouTubeServiceManager",
            "com.liskovsoft.sharedutils.prefs.GlobalPreferences",
            "com.liskovsoft.youtubeapi.lounge.LoungeService",
            "com.eclipsesource.v8.V8",
            "com.jayway.jsonpath.JsonPath",
            "net.minidev.json.JSONValue",
            "com.grack.nanojson.JsonParser",
            "com.florianingerl.util.regex.Pattern",
            "com.jakewharton.disklrucache.DiskLruCache",
        )
        assertTrue(ownership.accepts(required))
        assertTrue(ownership.owns("com.liskovsoft.googlecommon.service.oauth.YouTubeAccount"))
        assertFalse(ownership.owns("com.liskovsoft.youtubeapi.R"))
        assertFalse(ownership.owns("com.liskovsoft.youtubeapi.R\$raw"))
        assertFalse(ownership.owns("okhttp3.OkHttpClient"))
        assertFalse(ownership.owns("org.xbill.DNS.Name"))
        assertFalse(ownership.owns("io.reactivex.Observable"))
        assertTrue(ownership.owns("com.jayway.jsonpath.JsonPath"))
        assertFalse(ownership.accepts(required - "com.grack.nanojson.JsonParser"))
        assertFalse(ownership.owns("com.ambient.tv.youtube.SmartTubeEngine"))
        assertFalse(ownership.accepts(required + "com.liskovsoft.youtubeapi.R"))
        assertFalse(ownership.accepts(required - "com.eclipsesource.v8.V8"))
    }
}
