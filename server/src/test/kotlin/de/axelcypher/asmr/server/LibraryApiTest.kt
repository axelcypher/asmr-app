package de.axelcypher.asmr.server

import de.axelcypher.asmr.api.ApiJson
import de.axelcypher.asmr.api.CreateFolderRequest
import de.axelcypher.asmr.api.CreatorDto
import de.axelcypher.asmr.api.FolderAccessDto
import de.axelcypher.asmr.api.FolderListing
import de.axelcypher.asmr.api.ItemDto
import de.axelcypher.asmr.api.ItemPage
import de.axelcypher.asmr.api.LoginRequest
import de.axelcypher.asmr.api.LoginResponse
import de.axelcypher.asmr.api.UpdateCreatorRequest
import de.axelcypher.asmr.api.UpdateItemRequest
import de.axelcypher.asmr.server.db.CreatorStore
import de.axelcypher.asmr.server.db.Database
import de.axelcypher.asmr.server.db.FolderAccessStore
import de.axelcypher.asmr.server.db.ImportStore
import de.axelcypher.asmr.server.db.ItemStore
import de.axelcypher.asmr.server.db.UserStore
import de.axelcypher.asmr.server.imports.Downloader
import de.axelcypher.asmr.server.imports.ImportWorker
import de.axelcypher.asmr.server.library.LibraryScanner
import de.axelcypher.asmr.server.library.Sidecars
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

/** Ein kleines, gültiges PNG für Profilbild-Tests. */
val PNG: ByteArray = ByteArrayOutputStream().also {
    ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), "png", it)
}.toByteArray()

class LibraryApiTest {

    private val root: Path = Files.createTempDirectory("asmr-lib")
    private val media = root.resolve("media").createDirectories()
    private val db = Database.open("jdbc:sqlite::memory:")
    private val users = UserStore(db)
    private val items = ItemStore(db)
    private val imports = ImportStore(db)
    private val folderAccess = FolderAccessStore(db)
    private val creators = CreatorStore(db)
    private val lock = Mutex()
    private val scanner = LibraryScanner(items, media, root.resolve("covers"), FakeProbe, lock)

    @AfterTest
    fun cleanup() {
        db.close()
        root.toFile().deleteRecursively()
    }

    private fun file(relative: String, content: String = "x") = media.resolve(relative).also {
        it.parent.createDirectories()
        it.writeText(content)
    }

    private fun api(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) {
        val services = Services(
            users = users,
            items = items,
            imports = imports,
            folderAccess = folderAccess,
            creators = creators,
            worker = ImportWorker(imports, items, Downloader { _, _ -> error("kein Download") }, root.resolve("tmp"), media, lock),
            sso = null,
            scanner = scanner,
            avatarFetcher = { _, target -> target.writeBytes(PNG) },
            mediaDir = media,
            coverDir = root.resolve("covers"),
            avatarDir = root.resolve("avatars"),
        )
        testApplication {
            application { asmrModule(services) }
            block(createClient { install(ClientContentNegotiation) { json(ApiJson) } })
        }
    }

    private suspend fun HttpClient.login(name: String): String = post("/api/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(LoginRequest(name, "geheimes-passwort"))
    }.body<LoginResponse>().token

    private suspend fun HttpClient.folder(token: String, path: String = ""): FolderListing =
        get("/api/folders?path=$path") { bearerAuth(token) }.body()

    @Test
    fun `ordneransicht und zugriff ueber gruppen und benutzer`() = api { client ->
        file("Gibi ASMR/rain.mp3")
        file("Gibi ASMR/Roleplays/rp.mp3")
        file("Spicy/Creator X/late.mp3")
        file("loose.mp3")
        users.create("admin", "geheimes-passwort", isAdmin = true)
        val alice = users.create("alice", "geheimes-passwort", isAdmin = false)
        val bob = users.create("bob", "geheimes-passwort", isAdmin = false)
        users.updateFromSso(bob.id, null, null, null, groups = listOf("night-owls"))
        scanner.scan()

        val admin = client.login("admin")
        client.put("/api/admin/access") {
            bearerAuth(admin)
            contentType(ContentType.Application.Json)
            setBody(FolderAccessDto("Spicy", groups = listOf("Night-Owls")))
        }

        // Oberste Ebene: Ordner mit rekursiver Anzahl, Track direkt darin.
        val adminRoot = client.folder(admin)
        assertEquals(listOf("Gibi ASMR" to 2, "Spicy" to 1), adminRoot.folders.map { it.name to it.itemCount })
        assertTrue(adminRoot.folders.single { it.name == "Spicy" }.restricted)
        assertEquals(listOf("loose"), adminRoot.items.map { it.title })
        assertEquals(listOf("Roleplays"), client.folder(admin, "Gibi ASMR").folders.map { it.name })

        // Alice ist weder in der Gruppe noch freigegeben.
        val aliceToken = client.login("alice")
        assertEquals(listOf("Gibi ASMR"), client.folder(aliceToken).folders.map { it.name })
        assertEquals(3, client.get("/api/items") { bearerAuth(aliceToken) }.body<ItemPage>().total)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/folders?path=Spicy/Creator X") { bearerAuth(aliceToken) }.status)
        val hiddenId = items.idByAudioPath("Spicy/Creator X/late.mp3")!!
        assertEquals(HttpStatusCode.NotFound, client.get("/api/items/$hiddenId/audio") { bearerAuth(aliceToken) }.status)

        // Bob über die SSO-Gruppe (Groß/klein egal), Alice nach Freigabe als Benutzerin.
        assertEquals(4, client.get("/api/items") { bearerAuth(client.login("bob")) }.body<ItemPage>().total)
        client.put("/api/admin/access") {
            bearerAuth(admin)
            contentType(ContentType.Application.Json)
            setBody(FolderAccessDto("Spicy", groups = listOf("night-owls"), userIds = listOf(alice.id)))
        }
        assertEquals(4, client.get("/api/items") { bearerAuth(aliceToken) }.body<ItemPage>().total)
    }

    @Test
    fun `umbenennen schreibt metadaten-datei und ueberlebt neuen scan`() = api { client ->
        val audio = file("Gibi ASMR/raw_file_name.mp3")
        users.create("admin", "geheimes-passwort", isAdmin = true)
        users.create("alice", "geheimes-passwort", isAdmin = false)
        scanner.scan()
        val id = items.idByAudioPath("Gibi ASMR/raw_file_name.mp3")!!
        val admin = client.login("admin")

        val forbidden = client.patch("/api/items/$id") {
            bearerAuth(client.login("alice"))
            contentType(ContentType.Application.Json)
            setBody(UpdateItemRequest(title = "Nope"))
        }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)

        val updated = client.patch("/api/items/$id") {
            bearerAuth(admin)
            contentType(ContentType.Application.Json)
            setBody(UpdateItemRequest(title = "Gute Nacht", levels = mapOf("Kisses" to 8, "Talking" to 2, "Rain" to 0)))
        }.body<ItemDto>()
        assertEquals("Gute Nacht", updated.title)
        assertEquals(listOf("Kisses", "Talking"), updated.tags)
        assertEquals(mapOf("Kisses" to 8, "Talking" to 2), updated.levels)
        assertTrue(audio.exists(), "Datei bleibt unverändert")
        assertTrue("Gute Nacht" in Sidecars.pathFor(audio).readText())

        // Datenbank verloren: die Metadaten-Datei stellt Titel und Matrix wieder her.
        runBlocking { items.delete(id) }
        scanner.scan()
        val restored = items.get(items.idByAudioPath("Gibi ASMR/raw_file_name.mp3")!!, de.axelcypher.asmr.server.db.Viewer(1))!!
        assertEquals("Gute Nacht", restored.title)
        assertEquals(mapOf("Kisses" to 8, "Talking" to 2), restored.levels)
    }

    @Test
    fun `verschieben nimmt bild und metadaten mit, loeschen laesst ordner-cover stehen`() = api { client ->
        file("Inbox/track.mp3")
        file("Inbox/track.jpg", "own-cover")
        file("Inbox/cover.png", "folder-cover")
        users.create("admin", "geheimes-passwort", isAdmin = true)
        scanner.scan()
        val id = items.idByAudioPath("Inbox/track.mp3")!!
        val admin = client.login("admin")

        client.post("/api/folders") {
            bearerAuth(admin)
            contentType(ContentType.Application.Json)
            setBody(CreateFolderRequest("Gibi ASMR/Favoriten"))
        }
        assertEquals(listOf("Favoriten"), client.folder(admin, "Gibi ASMR").folders.map { it.name })

        val moved = client.patch("/api/items/$id") {
            bearerAuth(admin)
            contentType(ContentType.Application.Json)
            setBody(UpdateItemRequest(title = "Umbenannt", folder = "Gibi ASMR/Favoriten"))
        }.body<ItemDto>()
        assertEquals("Gibi ASMR/Favoriten", moved.folder)
        assertTrue(media.resolve("Gibi ASMR/Favoriten/track.jpg").exists())
        assertTrue(Sidecars.pathFor(media.resolve("Gibi ASMR/Favoriten/track.mp3")).exists())
        assertFalse(media.resolve("Inbox/track.mp3").exists())
        assertContentEquals("own-cover".toByteArray(), client.get("/api/items/$id/cover") { bearerAuth(admin) }.bodyAsBytes())

        // Ein zweiter Track im Inbox-Ordner nutzt das Ordner-Cover; Löschen darf es nicht mitnehmen.
        file("Inbox/other.mp3")
        scanner.scan()
        val other = items.idByAudioPath("Inbox/other.mp3")!!
        assertContentEquals("folder-cover".toByteArray(), client.get("/api/items/$other/cover") { bearerAuth(admin) }.bodyAsBytes())
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/items/$other") { bearerAuth(admin) }.status)
        assertFalse(media.resolve("Inbox/other.mp3").exists())
        assertTrue(media.resolve("Inbox/cover.png").exists())
    }

    @Test
    fun `creator links und profilbild als cover-fallback`() = api { client ->
        file("Gibi ASMR/no_cover.mp3")
        users.create("admin", "geheimes-passwort", isAdmin = true)
        scanner.scan()
        val id = items.idByAudioPath("Gibi ASMR/no_cover.mp3")!!
        val admin = client.login("admin")
        assertFalse(client.get("/api/items/$id") { bearerAuth(admin) }.body<ItemDto>().hasCover)

        val creator = client.put("/api/creators/Gibi ASMR") {
            bearerAuth(admin)
            contentType(ContentType.Application.Json)
            setBody(UpdateCreatorRequest(listOf("https://www.youtube.com/@GibiASMR", "https://patreon.com/gibi", "https://example.org/x")))
        }.body<CreatorDto>()
        assertEquals(listOf("YouTube", "Patreon", "example.org"), creator.links.map { it.label })

        val fetched = client.post("/api/creators/Gibi ASMR/avatar/fetch") { bearerAuth(admin) }.body<CreatorDto>()
        assertTrue(fetched.hasAvatar)

        val item = client.get("/api/items/$id") { bearerAuth(admin) }.body<ItemDto>()
        assertTrue(item.hasCover)
        assertEquals(HttpStatusCode.OK, client.get("/api/items/$id/cover") { bearerAuth(admin) }.status)
        // Der gleichnamige Ordner bekommt das Profilbild ebenfalls als Kachel.
        assertTrue(client.folder(admin).folders.single().hasCover)
    }
}
