package app.fafnirx.android

import app.fafnirx.android.net.Endpoint
import app.fafnirx.android.sync.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathsTest {
    @Test
    fun acceptsOrdinaryPathsAndNormalizesToNfc() {
        assertEquals("Camera/IMG_1.jpg", Paths.clean("Camera/IMG_1.jpg"))
        assertEquals("café/a.txt", Paths.clean("café/a.txt"))
    }

    @Test
    fun refusesWhatTheMasterRefuses() {
        for (bad in listOf("", "/abs", "a\\b", "a//b", "a/./b", "a/../b", "a/\u0001b", "x".repeat(256), "a/")) {
            assertNull("[$bad]", Paths.clean(bad))
        }
        assertEquals("x".repeat(255), Paths.clean("x".repeat(255)))
    }

    @Test
    fun foldersWithTheSameNameGetASuffixOnce() {
        assertEquals("Camera", Paths.uniquePrefix("Camera", emptySet()))
        assertEquals("Camera-2", Paths.uniquePrefix("Camera", setOf("Camera")))
        assertEquals("Camera-3", Paths.uniquePrefix("Camera", setOf("Camera", "Camera-2")))
        assertEquals("a_b", Paths.folderName("a/b"))
        assertEquals("folder", Paths.folderName(".."))
    }

    @Test
    fun parsesServerAddresses() {
        assertEquals(Endpoint("192.168.1.10", 7443, false), Endpoint.parse("192.168.1.10"))
        assertEquals(Endpoint("nas.lan", 9000, false), Endpoint.parse("nas.lan:9000"))
        assertEquals(Endpoint("fafnirx.example.com", 443, true), Endpoint.parse("https://fafnirx.example.com"))
        assertEquals(Endpoint("nas.lan", 7443, false), Endpoint.parse("http://nas.lan:7443"))
        assertNull(Endpoint.parse(""))
        assertNull(Endpoint.parse("ftp://x"))
    }

    @Test
    fun warnsOnlyForPublicAddressesOverPlainHttp() {
        assertFalse(Endpoint.parse("192.168.1.10")!!.looksPublicOverHttp)
        assertFalse(Endpoint.parse("10.0.2.2:7443")!!.looksPublicOverHttp)
        assertFalse(Endpoint.parse("nas.lan")!!.looksPublicOverHttp)
        assertFalse(Endpoint.parse("nas")!!.looksPublicOverHttp)
        assertFalse(Endpoint.parse("https://fafnirx.example.com")!!.looksPublicOverHttp)
        assertTrue(Endpoint.parse("http://fafnirx.example.com")!!.looksPublicOverHttp)
        assertTrue(Endpoint.parse("8.8.8.8")!!.looksPublicOverHttp)
    }
}
