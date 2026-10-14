package com.ambient.tv.data.files

import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramSocket
import java.net.DatagramPacket
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch

class FileSourcesTest {
    @Test fun fileKeysRoundTripOpaqueDocumentIdsAndUnicode() {
        for (path in listOf("primary:Videos/A & B + #/%雪.mkv", "folder/file.mp4", "")) {
            val key = FileKeys.key("source-1", path)
            assertTrue(FileKeys.owns(key)); assertEquals("source-1" to path, FileKeys.parse(key))
            assertFalse(key.contains('/')); assertFalse(key.contains('?'))
        }
    }
    @Test fun pathsCannotEscapeTheConfiguredRoot() {
        for (path in listOf("../secret", "a/../secret", "a/./b", "C:/secret", "a\\..\\b")) {
            assertThrows(IllegalArgumentException::class.java) { FilePaths.relative(path) }
        }
        assertEquals("movies/Ünicode file.mkv", FilePaths.relative("/movies\\Ünicode file.mkv/"))
    }
    @Test fun childNamesCannotInjectPaths() {
        for (name in listOf("..", "a/b", "a\\b")) assertThrows(IllegalArgumentException::class.java) { FilePaths.child("movies", name) }
    }
    @Test fun allThreeSourceTypesValidateAndSecretsAreNotInModel() {
        FileSource(name = "USB", kind = "local", treeUri = "content://documents/tree/usb").validated()
        FileSource(name = "NAS", kind = "smb", host = "nas.local", share = "media").validated()
        FileSource(name = "NFS", kind = "nfs", host = "192.168.1.5", port = 2049, share = "/volume1/media").validated()
        assertThrows(IllegalArgumentException::class.java) { FileSource(name = "NAS", kind = "smb", host = "user:secret@nas", share = "media").validated() }
        assertThrows(IllegalArgumentException::class.java) { FileSource(name = "NFS", kind = "nfs", host = "nas", share = "relative").validated() }
        FileSource(name = "Old NAS", kind = "smb", host = "nas", share = "media", smbVersion = 1).validated()
        FileSource(name = "NFS4", kind = "nfs", host = "nas", port = 2049, share = "/media", nfsVersion = 4).validated()
        assertThrows(IllegalArgumentException::class.java) { FileSource(name = "Old NAS", kind = "smb", host = "nas", share = "media", smbVersion = 1, encrypt = true).validated() }
        assertThrows(IllegalArgumentException::class.java) { FileSource(name = "NFS", kind = "nfs", host = "nas", share = "/media", nfsVersion = 5).validated() }
    }
    @Test fun mediaClassificationHandlesCaseAndUnknownDocuments() {
        assertTrue(FileEntry("id", "path", "SONG.FLAC", false).audio)
        assertTrue(FileEntry("id", "path", "movie.MKV", false).playable)
        assertFalse(FileEntry("id", "path", "document.pdf", false).playable)
        assertFalse(FileEntry("id", "path", "movie.mkv", true).playable)
    }
    @Test fun wakePacketHasSyncPrefixAndSixteenMacCopies() {
        val bytes = WakeOnLan.packet("02:11:22:33:44:55")
        assertEquals(102, bytes.size)
        assertTrue(bytes.take(6).all { it == 0xff.toByte() })
        repeat(16) { assertArrayEquals(byteArrayOf(2, 17, 34, 51, 68, 85), bytes.copyOfRange(6 + 6 * it, 12 + 6 * it)) }
        assertArrayEquals(bytes, WakeOnLan.packet("02-11-22-33-44-55"))
    }
    @Test fun malformedAndMulticastWakeAddressesAreRejected() {
        for (mac in listOf("", "123", "gg:11:22:33:44:55", "01:11:22:33:44:55", "00:00:00:00:00:00")) assertThrows(IllegalArgumentException::class.java) { WakeOnLan.packet(mac) }
    }
    @Test fun wakeActuallySendsUdpPacketsToConfiguredAddressAndPort() = runBlocking {
        DatagramSocket(0).use { socket ->
            socket.soTimeout = 3000
            WakeOnLan().send("02:11:22:33:44:55", "127.0.0.1", socket.localPort)
            repeat(3) {
                val packet = DatagramPacket(ByteArray(128), 128); socket.receive(packet)
                assertArrayEquals(WakeOnLan.packet("02:11:22:33:44:55"), packet.data.copyOf(packet.length))
            }
        }
    }

    @Test fun malformedTreeUrisAreRejectedBeforeNavigation() {
        for (uri in listOf("content://", "content://documents/document/usb", "content://documents/tree/", "content://documents/tree//", "file:///tree/usb")) {
            assertThrows(IllegalArgumentException::class.java) { FileSource(name = "bad", kind = "local", treeUri = uri).validated() }
        }
        FileSource(name = "valid", kind = "local", treeUri = "content://documents/tree/primary%3AVideos").validated()
    }
    @Test fun editedEndpointDoesNotReusePreviousWakeReachability() = runBlocking {
        java.net.ServerSocket(0).use { online ->
            DatagramSocket(0).use { packets ->
                packets.soTimeout = 3000
                val offlinePort = java.net.ServerSocket(0).use { it.localPort }
                val wake = WakeOnLan()
                val original = FileSource(id = "same-id", name = "NAS", kind = "smb", host = "127.0.0.1", port = online.localPort,
                    share = "media", wolMac = "02:11:22:33:44:55", wolBroadcast = "127.0.0.1", wolPort = packets.localPort, wolAuto = true)
                wake.ensureAwake(original)
                val attempt = launch(kotlinx.coroutines.Dispatchers.Default) { wake.ensureAwake(original.copy(port = offlinePort)) }
                try {
                    val packet = DatagramPacket(ByteArray(128), 128)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { packets.receive(packet) }
                    assertArrayEquals(WakeOnLan.packet(original.wolMac), packet.data.copyOf(packet.length))
                } finally { attempt.cancel(); attempt.join() }
            }
        }
    }
}
