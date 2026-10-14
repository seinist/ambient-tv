package com.ambient.tv.tvheadend.data

import okhttp3.Credentials
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test

class TvheadendAuthTest {
    private fun challenge(value: String): Response = Response.Builder()
        .request(Request.Builder().url("http://192.168.1.2:9981/api/serverinfo").build())
        .protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized")
        .header("WWW-Authenticate", value).build()

    @Test fun usernameWithEmptyPasswordCanAnswerBasicChallenge() {
        val result = TvheadendAuthenticator { "tv-user" to null }
            .authenticate(null, challenge("Basic realm=\"TVHeadend\""))
        assertEquals(Credentials.basic("tv-user", ""), result?.header("Authorization"))
    }

    @Test fun unsupportedDigestAlgorithmDoesNotPretendToBeMd5() {
        val result = TvheadendAuthenticator { "user" to "password" }
            .authenticate(null, challenge("Digest realm=\"tv\", nonce=\"123\", algorithm=UNKNOWN, qop=\"auth\""))
        assertNull(result)
    }

    @Test fun authIntOnlyChallengeIsRejectedInsteadOfSendingAnInvalidHash() {
        val result = TvheadendAuthenticator { "user" to "password" }
            .authenticate(null, challenge("Digest realm=\"tv\", nonce=\"123\", qop=\"auth-int\""))
        assertNull(result)
    }

    @Test fun basicIsOnlyRememberedAfterABasicChallenge() {
        val auth = TvheadendAuthenticator { "user" to "password" }
        assertFalse(auth.basicAccepted)
        auth.authenticate(null, challenge("Digest realm=\"tv\", nonce=\"123\", qop=\"auth\""))
        assertFalse(auth.basicAccepted)
        auth.authenticate(null, challenge("Basic realm=\"tv\""))
        assertTrue(auth.basicAccepted)
        auth.authenticate(null, challenge("Digest realm=\"tv\", nonce=\"456\", qop=\"auth\""))
        assertFalse(auth.basicAccepted)
    }
}
