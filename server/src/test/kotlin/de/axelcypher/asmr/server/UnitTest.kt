package de.axelcypher.asmr.server

import de.axelcypher.asmr.server.auth.Passwords
import de.axelcypher.asmr.server.imports.ImportWorker
import de.axelcypher.asmr.server.imports.TriggerTags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnitTest {

    @Test
    fun `trigger tags aus titel und beschreibung`() {
        val tags = TriggerTags.detect(
            title = "ASMR Ear Cleaning RP | Soft-Spoken",
            description = "Layered sounds with crinkles and keyboard typing",
            sourceTags = listOf("no talking"),
        )
        assertEquals(
            listOf("Soft Spoken", "No Talking", "Ear Cleaning", "Roleplay", "Crinkles", "Typing", "Layered"),
            tags,
        )
    }

    @Test
    fun `trigger tags ohne falsche treffer`() {
        // "brain", "terrain", "strap", "therapy" dürfen nicht Rain/Tapping/Roleplay auslösen.
        assertEquals(emptyList(), TriggerTags.detect("Brain melting terrain therapy", "strap", emptyList()))
    }

    @Test
    fun `passwort hash`() {
        val hash = Passwords.hash("korrekt-pferd")
        assertTrue(Passwords.verify("korrekt-pferd", hash))
        assertFalse(Passwords.verify("falsch", hash))
        assertFalse(Passwords.verify("korrekt-pferd", "kaputt"))
    }

    @Test
    fun `dateinamen werden bereinigt`() {
        assertEquals("a_b_c", ImportWorker.safeName("a/b:c"))
        assertEquals("_", ImportWorker.safeName("..."))
    }
}
