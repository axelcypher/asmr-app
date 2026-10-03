package de.axelcypher.asmr

import de.axelcypher.asmr.data.api.Pkce
import de.axelcypher.asmr.data.api.normalizeServerUrl
import de.axelcypher.asmr.ui.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AppUnitTest {

    @Test
    fun `server url wird normalisiert`() {
        assertEquals("https://asmr.example.de", normalizeServerUrl(" asmr.example.de/ "))
        assertEquals("http://192.168.1.5:8080", normalizeServerUrl("http://192.168.1.5:8080/"))
    }

    @Test
    fun `dauer wird formatiert`() {
        assertEquals("0:59", formatDuration(59.9))
        assertEquals("12:34", formatDuration(754.0))
        assertEquals("1:06:40", formatDuration(4000.0))
    }

    @Test
    fun `pkce challenge nach rfc 7636`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
        val verifier = Pkce.newVerifier()
        assertEquals(64, verifier.length)
        assertNotEquals(verifier, Pkce.newVerifier())
    }
}
