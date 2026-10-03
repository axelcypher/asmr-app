package de.axelcypher.asmr.server

import de.axelcypher.asmr.api.ItemSort
import de.axelcypher.asmr.server.db.Database
import de.axelcypher.asmr.server.db.ItemQuery
import de.axelcypher.asmr.server.db.ItemStore
import de.axelcypher.asmr.server.db.UserStore
import de.axelcypher.asmr.server.db.Viewer
import de.axelcypher.asmr.server.library.LibraryScanner
import de.axelcypher.asmr.server.library.MediaInfo
import de.axelcypher.asmr.server.library.MediaProbe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Liefert Metadaten nur für Dateien mit "tagged" im Namen; Vorschaubilder sind leere Dateien. */
object FakeProbe : MediaProbe {
    override fun probe(file: Path) = if ("tagged" in file.name) {
        MediaInfo(durationSeconds = 600.0, title = "Getaggter Titel", artist = "Tag Artist", comment = "soft spoken")
    } else {
        MediaInfo(durationSeconds = 120.0, title = null, artist = null, comment = null)
    }

    override fun thumbnail(file: Path, target: Path): Boolean {
        target.parent.createDirectories()
        target.writeText("jpg")
        return true
    }
}

class ScannerTest {

    private val root: Path = Files.createTempDirectory("asmr-scan")
    private val media = root.resolve("media").createDirectories()
    private val db = Database.open("jdbc:sqlite::memory:")
    private val items = ItemStore(db)
    private val scanner = LibraryScanner(items, media, root.resolve("covers"), FakeProbe, Mutex())

    @AfterTest
    fun cleanup() {
        db.close()
        root.toFile().deleteRecursively()
    }

    private fun file(relative: String) = media.resolve(relative).also {
        it.parent.createDirectories()
        it.writeText("x")
    }

    @Test
    fun `scan uebernimmt unterordner und entfernt verschwundene dateien`() = runBlocking {
        val userId = UserStore(db).create("t", null, isAdmin = false).id
        file("Gibi ASMR/Rain Tapping [abcDEF123].mp3")
        file("Gibi ASMR/Rain Tapping [abcDEF123].jpg")
        file("Deep/Nested/Folder/tagged.m4a")
        file("Deep/Nested/Folder/cover.jpg")
        file("video_without_cover.mp4")
        file(".hidden/ignored.mp3")
        file("notes.txt")

        assertEquals(3, scanner.scan().added)
        val all = items.list(Viewer(userId), ItemQuery(sort = ItemSort.TITLE)).items.associateBy { it.title }

        val rain = all.getValue("Rain Tapping")
        assertEquals("Gibi ASMR", rain.creator)
        assertEquals(listOf("Rain", "Tapping"), rain.tags)
        assertTrue(rain.hasCover)

        val tagged = all.getValue("Getaggter Titel")
        assertEquals("Tag Artist", tagged.creator)
        assertEquals(600.0, tagged.durationSeconds)
        assertEquals(listOf("Soft Spoken"), tagged.tags)
        assertTrue(tagged.hasCover)

        val video = all.getValue("video without cover")
        assertNull(video.creator)
        assertTrue(items.files(video.id, Viewer(userId))!!.coverPath!!.startsWith(LibraryScanner.DATA_COVER_PREFIX))

        // Zweiter Scan: nichts Neues.
        assertEquals(0, scanner.scan().added)

        media.resolve("video_without_cover.mp4").deleteExisting()
        assertEquals(1, scanner.scan().removed)
        assertEquals(2, items.audioPaths().size)
    }

    @Test
    fun `suche mit trigger, laenge und creator kombiniert`() = runBlocking {
        val userId = UserStore(db).create("t", null, isAdmin = false).id
        file("Gibi ASMR/Rain Tapping.mp3") // 120 s, Rain + Tapping
        file("Gibi ASMR/tagged long.mp3") // 600 s, Soft Spoken
        file("Other/Rain only.mp3") // 120 s, Rain
        scanner.scan()
        val viewer = Viewer(userId)
        fun titles(q: ItemQuery) = runBlocking { items.list(viewer, q).items.map { it.title }.toSet() }

        assertEquals(setOf("Rain Tapping", "Rain only"), titles(ItemQuery(tags = listOf("rain"))))
        assertEquals(setOf("Rain Tapping"), titles(ItemQuery(tags = listOf("Rain", "Tapping"))))
        assertEquals(setOf("Getaggter Titel"), titles(ItemQuery(minDuration = 300.0)))
        assertEquals(setOf("Rain Tapping", "Rain only"), titles(ItemQuery(maxDuration = 300.0)))
        assertEquals(setOf("Rain only"), titles(ItemQuery(search = "only", creator = "other")))
    }

    @Test
    fun `leerer medienordner loescht nichts`() = runBlocking {
        val f = file("a.mp3")
        scanner.scan()
        f.deleteExisting()
        // Sieht aus wie ein nicht gemountetes NAS: Eintrag bleibt.
        assertEquals(0, scanner.scan().removed)
        assertEquals(1, items.audioPaths().size)
    }

    @Test
    fun `titel aus dateinamen`() {
        assertEquals("Rain Tapping", LibraryScanner.cleanTitle("Rain Tapping [dQw4w9WgXcQ]"))
        assertEquals("ear cleaning rp", LibraryScanner.cleanTitle("ear_cleaning_rp"))
        assertEquals("Part [2]", LibraryScanner.cleanTitle("Part [2]"))
    }
}
