package com.ambient.tv.companion

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class CompanionProtocolTest {
    private fun rejects(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }

    @Test fun invitationRequiresFullPinAndPrivateLiteralAddress() {
        val invite = CompanionInvitation("192.168.1.40", 43210, "ab".repeat(32), "A".repeat(43))
        assertEquals(invite, CompanionInvitation.parse(invite.link()))
        listOf("8.8.8.8", "127.0.0.1", "169.254.1.1", "224.0.0.1", "10.999.0.1", "localhost").forEach { host ->
            rejects { CompanionInvitation.parse(invite.copy(host = host).link()) }
        }
        rejects { CompanionInvitation.parse(invite.copy(fingerprint = "abc123").link()) }
        rejects { CompanionInvitation.parse(invite.copy(token = "123456").link()) }
        rejects { CompanionInvitation.parse(invite.copy(port = 0).link()) }
        rejects { CompanionInvitation.parse(invite.link().replace("ambienttv:", "https:")) }
    }

    @Test fun rejectsReplaySkippedSequenceAndPreviousSession() {
        val gate = CompanionSequence("fresh-session")
        val first = CompanionFrame(type = "start", nonce = "fresh-session", sequence = 1)
        gate.accept(first)
        rejects { gate.accept(first) }
        rejects { gate.accept(first.copy(sequence = 3)) }
        rejects { gate.accept(first.copy(nonce = "previous-session", sequence = 2)) }
        gate.accept(first.copy(sequence = 2))
    }

    @Test fun handshakeLimitIsPerLanPeerAndExpires() {
        val limiter = CompanionHandshakeLimiter(limit = 2, windowMs = 1_000, maxPeers = 2)
        assertTrue(limiter.admit("192.168.1.10", 100))
        assertTrue(limiter.admit("192.168.1.10", 200))
        assertFalse(limiter.admit("192.168.1.10", 300))
        assertTrue(limiter.admit("192.168.1.11", 300))
        assertTrue(limiter.admit("192.168.1.10", 1_101))
    }

    @Test fun oneLanPeerCannotTakeEveryUnauthenticatedSlot() {
        val admission = CompanionAdmission(pending = 3, perHost = 2, sessions = 2)
        val first = admission.admit("192.168.1.66")!!
        assertNotNull(admission.admit("192.168.1.66"))
        assertNull(admission.admit("192.168.1.66"))
        // A paired phone still gets in while the noisy peer holds its share.
        val phone = admission.admit("192.168.1.20")!!
        assertNull(admission.admit("192.168.1.21"))
        first.release()
        first.release()
        assertNotNull(admission.admit("192.168.1.21"))
        assertTrue(phone.promote())
        assertTrue(phone.promote())
        assertNotNull(admission.admit("192.168.1.22"))
    }

    @Test fun authenticatedSessionsHaveTheirOwnLimit() {
        val admission = CompanionAdmission(pending = 4, perHost = 4, sessions = 1)
        val a = admission.admit("192.168.1.20")!!
        val b = admission.admit("192.168.1.21")!!
        assertTrue(a.promote())
        assertFalse(b.promote())
        b.release()
        a.release()
        assertTrue(admission.admit("192.168.1.22")!!.promote())
    }

    @Test fun authenticationAndComparisonBindBothIdentitiesAndSession() {
        val original = authTranscript("nonce", "tv", "phone")
        assertFalse(original.contentEquals(authTranscript("other", "tv", "phone")))
        assertFalse(original.contentEquals(authTranscript("nonce", "other", "phone")))
        assertFalse(original.contentEquals(authTranscript("nonce", "tv", "other")))
        assertNotEquals(comparisonCode("nonce", "tv", "phone"), comparisonCode("other", "tv", "phone"))
    }

    @Test fun framesAreBoundedAndVersioned() {
        val frame = CompanionFrame(type = "start", media = CompanionMedia("youtube", "dQw4w9WgXcQ"))
        val bytes = ByteArrayOutputStream().also { DataOutputStream(it).writeFrame(frame) }.toByteArray()
        assertEquals(frame, DataInputStream(ByteArrayInputStream(bytes)).readFrame())
        for (size in listOf(-1, 0, 16_385, Int.MAX_VALUE)) {
            val bad = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(size) }.toByteArray()
            rejects { DataInputStream(ByteArrayInputStream(bad)).readFrame() }
        }
        val future = ByteArrayOutputStream().also { DataOutputStream(it).writeFrame(frame.copy(version = 2)) }.toByteArray()
        rejects { DataInputStream(ByteArrayInputStream(future)).readFrame() }
        rejects { DataInputStream(ByteArrayInputStream(bytes.dropLast(1).toByteArray())).readFrame() }
    }

    @Test fun sharedLinksCannotSmuggleArbitraryUrlsOrCredentials() {
        assertEquals("dQw4w9WgXcQ", sharedYouTubeId("Watch this: https://youtu.be/dQw4w9WgXcQ?t=2"))
        assertEquals("dQw4w9WgXcQ", sharedYouTubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&feature=shared"))
        assertEquals("dQw4w9WgXcQ", sharedYouTubeId("https://youtube.com/shorts/dQw4w9WgXcQ"))
        listOf("https://youtube.com.evil.test/watch?v=dQw4w9WgXcQ", "https://user:secret@youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com:444/watch?v=dQw4w9WgXcQ", "file:///sdcard/video.mp4", "http://192.168.1.1/admin").forEach { assertNull(sharedYouTubeId(it)) }
    }

    @Test fun deviceNamesCannotInjectBidiOrControlCharacters() {
        assertEquals("PhoneAdmin", safeDeviceName("Phone\n\u202eAdmin"))
        assertEquals(40, safeDeviceName("A".repeat(200)).length)
    }
}
