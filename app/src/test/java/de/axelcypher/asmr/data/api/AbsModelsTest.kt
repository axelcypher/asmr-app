package de.axelcypher.asmr.data.api

import de.axelcypher.asmr.ui.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsModelsTest {

    @Test
    fun `login response mit neuen Tokens`() {
        val json = """
            {"user":{"id":"u1","username":"tobias","type":"root",
             "accessToken":"acc","refreshToken":"ref","token":"legacy"},
             "userDefaultLibraryId":"lib1"}
        """.trimIndent()

        val user = AbsJson.decodeFromString<LoginResponse>(json).user

        assertEquals("acc", user.accessToken)
        assertEquals("ref", user.refreshToken)
    }

    @Test
    fun `login response alter Server nur mit token`() {
        val json = """{"user":{"id":"u1","username":"tobias","token":"legacy"}}"""

        val user = AbsJson.decodeFromString<LoginResponse>(json).user

        assertNull(user.accessToken)
        assertEquals("legacy", user.token)
    }

    @Test
    fun `buch item wird gemappt`() {
        val json = """
            {"results":[{"id":"li1","mediaType":"book","media":{
              "metadata":{"title":"Rain Tapping","authorName":"Gibi","genres":[]},
              "coverPath":"/metadata/items/li1/cover.jpg","tags":["Tapping","Rain"],
              "duration":1834.5,"numTracks":1}}],
             "total":1,"limit":60,"page":0}
        """.trimIndent()

        val item = AbsJson.decodeFromString<LibraryItemsResponse>(json).results.single().toAsmrItem()

        assertEquals("Rain Tapping", item.title)
        assertEquals("Gibi", item.creator)
        assertEquals(1834.5, item.durationSeconds!!, 0.0)
        assertEquals(listOf("Tapping", "Rain"), item.tags)
        assertTrue(item.hasCover)
    }

    @Test
    fun `podcast item ohne cover und dauer`() {
        val json = """
            {"results":[{"id":"li2","mediaType":"podcast","media":{
              "metadata":{"title":"Sleep Sounds","author":"ASMR Darling"},"numEpisodes":12}}],
             "total":1}
        """.trimIndent()

        val item = AbsJson.decodeFromString<LibraryItemsResponse>(json).results.single().toAsmrItem()

        assertEquals("ASMR Darling", item.creator)
        assertNull(item.durationSeconds)
        assertFalse(item.hasCover)
    }

    @Test
    fun `server url wird normalisiert`() {
        assertEquals("https://abs.example.de", normalizeServerUrl(" abs.example.de/ "))
        assertEquals("http://192.168.1.5:13378", normalizeServerUrl("http://192.168.1.5:13378/"))
    }

    @Test
    fun `dauer wird formatiert`() {
        assertEquals("0:59", formatDuration(59.9))
        assertEquals("12:34", formatDuration(754.0))
        assertEquals("1:06:40", formatDuration(4000.0))
    }
}
