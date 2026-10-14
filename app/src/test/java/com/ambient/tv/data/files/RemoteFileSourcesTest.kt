package com.ambient.tv.data.files

import org.junit.Assert.*
import org.junit.Test

class RemoteFileSourcesTest {
    private val dav = FileSource(id = "dav-1", name = "Cloud", kind = "webdav", host = "cloud.lan", port = 443,
        share = "/remote.php/dav/files/me", username = "me")

    @Test fun webDavAndSftpValidate() {
        assertEquals("/remote.php/dav/files/me", dav.validated().share)
        assertEquals("/media", dav.copy(share = "/media/").validated().share)
        FileSource(id = "s", name = "Box", kind = "sftp", host = "box", port = 22, username = "me").validated()
        assertThrows(IllegalArgumentException::class.java) { FileSource(id = "s", name = "Box", kind = "sftp", host = "box", port = 22).validated() }
        assertThrows(IllegalArgumentException::class.java) { dav.copy(share = "relative/path").validated() }
        assertThrows(IllegalArgumentException::class.java) { dav.copy(share = "/a/../b").validated() }
        assertThrows(IllegalArgumentException::class.java) { dav.copy(serverKey = "MD5:aa").validated() }
        dav.copy(serverKey = "SHA256:" + "A".repeat(43)).validated()
        assertTrue(dav.network && dav.usesPassword)
        assertFalse(FileSource(id = "n", name = "n", kind = "nfs", host = "h", port = 2049, share = "/x").usesPassword)
    }

    @Test fun urlsEncodeEverySegmentAndMarkFolders() {
        assertEquals("https://cloud.lan/remote.php/dav/files/me/Filme%20&%20Serien/", webDavUrl(dav, "Filme & Serien", directory = true).toString())
        assertEquals("http://nas:8080/a%23b.mkv", webDavUrl(dav.copy(secure = false, host = "nas", port = 8080, share = ""), "a#b.mkv", directory = false).toString())
    }

    @Test fun childNamesComeOnlyFromDirectChildren() {
        assertEquals("Film 1.mkv", webDavChildName(dav, "Videos", "/remote.php/dav/files/me/Videos/Film%201.mkv"))
        assertEquals("Sub", webDavChildName(dav, "Videos", "https://cloud.lan/remote.php/dav/files/me/Videos/Sub/"))
        assertNull(webDavChildName(dav, "Videos", "/remote.php/dav/files/me/Videos/"))
        assertNull(webDavChildName(dav, "Videos", "/remote.php/dav/files/me/Other/x.mkv"))
        assertNull(webDavChildName(dav, "Videos", "/remote.php/dav/files/me/Videos/a/b.mkv"))
    }

    @Test fun multistatusKeepsOnlySuccessfulProperties() {
        val xml = """<?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/dav/Videos/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response><d:href>/dav/Videos/Film.mkv</d:href>
                <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>1234</d:getcontentlength>
                  <d:getlastmodified>Wed, 14 Oct 2026 06:00:00 GMT</d:getlastmodified><d:getcontenttype>video/x-matroska; x=1</d:getcontenttype></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                <d:propstat><d:prop><d:getcontentlength>99</d:getcontentlength></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
              </d:response>
            </d:multistatus>"""
        val items = parseMultistatus(xml)
        assertEquals(2, items.size)
        assertTrue(items[0].directory)
        assertEquals(WebDavItem("/dav/Videos/Film.mkv", false, 1234, 1791957600000, "video/x-matroska"), items[1])
    }

    @Test fun doctypeIsRefused() {
        assertThrows(java.io.IOException::class.java) {
            parseMultistatus("""<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><d:multistatus xmlns:d="DAV:"/>""")
        }
    }

    @Test fun keyFilesAndKeyText() {
        fun entry(name: String, size: Long = 400) = FileEntry("device", "/x/$name", name, false, size)
        assertTrue(isSshKeyFile(entry("id_ed25519")))
        assertTrue(isSshKeyFile(entry("server.ppk")))
        assertFalse(isSshKeyFile(entry("id_ed25519.pub")))
        assertFalse(isSshKeyFile(entry("movie.mkv")))
        assertFalse(isSshKeyFile(entry("id_rsa", size = 1_000_000)))
        assertTrue(looksLikePrivateKey("-----BEGIN OPENSSH PRIVATE KEY-----\n" + "A".repeat(70) + "\n-----END OPENSSH PRIVATE KEY-----"))
        assertTrue(looksLikePrivateKey("PuTTY-User-Key-File-3: ssh-ed25519\n" + "A".repeat(70)))
        assertFalse(looksLikePrivateKey("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl me@host"))
    }

    @Test fun digestIsPreferredAndMatchesTheRfcExample() {
        val auth = chooseAuthChallenge(listOf(
            "Basic realm=\"x\"",
            "Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"",
        ))
        assertTrue(auth is WebDavAuth.Digest)
        val header = digestAuthorization(auth as WebDavAuth.Digest, "Mufasa", "Circle Of Life", "GET", "/dir/index.html", cnonce = "0a4f113b", nc = 1)
        // RFC 2617 section 3.5.
        assertTrue(header, header.contains("response=\"6629fae49393a05397450978507c4ef1\""))
        assertTrue(header.contains("nc=00000001") && header.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""))
    }

    @Test fun basicOnlyWhenNothingBetterAndUnknownSchemesAreRefused() {
        assertSame(WebDavAuth.Basic, chooseAuthChallenge(listOf("Basic realm=\"nas\", charset=\"UTF-8\"")))
        assertNull(chooseAuthChallenge(listOf("Negotiate", "NTLM")))
        assertTrue(chooseAuthChallenge(listOf("Digest realm=\"a\", nonce=\"n\", algorithm=SHA-256, qop=\"auth\", Digest realm=\"a\", nonce=\"m\"")).let {
            it is WebDavAuth.Digest && it.algorithm == "SHA-256"
        })
    }

    @Test fun proxyRewrittenHrefsStillListChildren() {
        val hrefs = listOf("/proxy/dav/Videos/", "/proxy/dav/Videos/A%20B.mkv", "/proxy/dav/Videos/Sub/")
        assertEquals(listOf(null, "A B.mkv", "Sub"), webDavChildNames(dav, "Videos", hrefs))
        // The normal case keeps using the configured base path.
        assertEquals(listOf(null, "x.mkv"), webDavChildNames(dav, "", listOf("/remote.php/dav/files/me/", "/remote.php/dav/files/me/x.mkv")))
    }
}
