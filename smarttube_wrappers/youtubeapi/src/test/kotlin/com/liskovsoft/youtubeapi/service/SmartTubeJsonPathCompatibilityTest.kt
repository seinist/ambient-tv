package com.liskovsoft.youtubeapi.service

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.spi.json.GsonJsonProvider
import com.jayway.jsonpath.spi.mapper.GsonMappingProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartTubeJsonPathCompatibilityTest {
    @Test fun gsonProviderReadsNestedYouTubeFields() {
        val config = Configuration.builder()
            .jsonProvider(GsonJsonProvider())
            .mappingProvider(GsonMappingProvider())
            .build()
        val document = JsonPath.using(config).parse(
            """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"fixture"},"streamingData":{"formats":[{"itag":18}]}}"""
        )
        assertEquals("OK", document.read("$.playabilityStatus.status", String::class.java))
        assertEquals("fixture", document.read("$.videoDetails.videoId", String::class.java))
        assertEquals(18, document.read("$.streamingData.formats[0].itag", Int::class.javaObjectType))
    }
}
