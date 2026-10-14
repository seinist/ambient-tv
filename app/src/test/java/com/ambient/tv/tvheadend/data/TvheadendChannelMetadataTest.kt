package com.ambient.tv.tvheadend.data

import com.ambient.tv.livetv.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TvheadendChannelMetadataTest {
    private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject
    private val channel = obj("""{"tags":["tag"],"services":["a","b"]}""")
    @Test fun resolvesTagIdsAndRadioFallbackWithoutAdminAccess() {
        val tags = tvhTagNames(listOf(obj("""{"key":"tag","val":"Radio"}""")))
        val metadata = tvhChannelMetadata(channel, tags, emptyMap())
        assertEquals(listOf("Radio"), metadata.tags)
        assertEquals(LiveTvChannelKind.RADIO, metadata.kind)
        assertEquals(LiveTvChannelAccess.UNKNOWN, metadata.access)
    }
    @Test fun missingServiceIsNotProofOfEncryption() {
        val metadata = tvhChannelMetadata(channel, emptyMap(), mapOf("a" to obj("""{"encrypted":true,"dvb_servicetype":1}""")))
        assertEquals(LiveTvChannelAccess.UNKNOWN, metadata.access)
    }
    @Test fun anyFreeServiceMakesChannelFreeToAir() {
        val services = mapOf("a" to obj("""{"encrypted":true,"dvb_servicetype":2}"""), "b" to obj("""{"encrypted":false,"dvb_servicetype":2}"""))
        val metadata = tvhChannelMetadata(channel, emptyMap(), services)
        assertEquals(LiveTvChannelAccess.FTA, metadata.access)
        assertEquals(LiveTvChannelKind.RADIO, metadata.kind)
    }
    @Test fun allEncryptedServicesAndTvTypeAreRecognized() {
        val service = obj("""{"encrypted":true,"dvb_servicetype":31}""")
        val metadata = tvhChannelMetadata(channel, emptyMap(), mapOf("a" to service, "b" to service))
        assertEquals(LiveTvChannelAccess.ENCRYPTED, metadata.access)
        assertEquals(LiveTvChannelKind.TV, metadata.kind)
    }
    @Test fun missingOrConflictingMetadataStaysUnknown() {
        val metadata = tvhChannelMetadata(obj("""{"tags":["tv","radio"],"services":[]}"""), mapOf("tv" to "TV", "radio" to "Radio"), emptyMap())
        assertEquals(LiveTvChannelKind.UNKNOWN, metadata.kind)
        assertEquals(LiveTvChannelAccess.UNKNOWN, metadata.access)
    }
    @Test fun unresolvedTagsAreNotShownAsUuidGroups() {
        assertTrue(tvhChannelMetadata(channel, emptyMap(), emptyMap()).tags.isEmpty())
    }
}
