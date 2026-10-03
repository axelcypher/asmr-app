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
import de.axelcypher.asmr.server.db.AmbientFolderStore
import de.axelcypher.asmr.server.db.CategoryStore
import de.axelcypher.asmr.server.db.CreatorStore
import de.axelcypher.asmr.server.db.PlaylistStore
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
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
    private val categories = CategoryStore(db)
    private val ambientFolders = AmbientFolderStore(db)
    private val playlists = PlaylistStore(db)
    private val creators = CreatorStore(db)
    private val lock = Mutex()
    private val scanner = LibraryScanner(
        items, media, root.resolve("covers"), FakeProbe, lock,
        folderOwners = listOf(folderAccess, categories, ambientFolders),
    )

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
            categories = categories,
            ambientFolders = ambientFolders,
            playlists = playlists,
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
    fun `ordner auf dem nas umbenannt - sofort sichtbar, ids, favoriten und sperre bleiben`() = api { client ->
        file("Spicy/Alt/a.mp3")
        file("Spicy/Alt/Sub/b.mp3")
        users.create("admin", "geheimes-passwort", isAdmin = true)
        users.create("alice", "geheimes-passwort", isAdmin = false)
        scanner.scan()
        val idA = items.idByAudioPath("Spicy/Alt/a.mp3")!!
        val idB = items.idByAudioPath("Spicy/Alt/Sub/b.mp3")!!
        val admin = client.login("admin")
        client.put("/api/items/$idA/favorite") { bearerAuth(admin) }
        client.put("/api/admin/access") {
            bearerAuth(admin)
            contentType(ContentType.Application.Json)
            setBody(FolderAccessDto("Spicy/Alt/Sub"))
        }

        Files.move(media.resolve("Spicy/Alt"), media.resolve("Spicy/Neu"))

        // Ohne expliziten Scan: die Ordneransicht merkt die Abweichung selbst.
        val listing = client.folder(admin, "Spicy")
        assertEquals(listOf("Neu"), listing.folders.map { it.name })
        assertEquals(idA, items.idByAudioPath("Spicy/Neu/a.mp3"))
        assertEquals(idB, items.idByAudioPath("Spicy/Neu/Sub/b.mp3"))
        assertTrue(client.get("/api/items/$idA") { bearerAuth(admin) }.body<ItemDto>().isFavorite)

        // Die Sperre ist mitgewandert: Alice sieht b weiterhin nicht.
        assertEquals(listOf("Spicy/Neu/Sub"), runBlocking { folderAccess.rules() }.map { it.path })
        val aliceToken = client.login("alice")
        assertEquals(HttpStatusCode.NotFound, client.get("/api/items/$idB") { bearerAuth(aliceToken) }.status)
    }

    @Test
    fun `startseite mit kategorien, ambiente und playlists`() = api { client ->
        file("Natur/rain.mp3")
        file("Natur/Wald/birds.mp3")
        file("Gibi ASMR/tapping.mp3")
        file("Spicy/late.mp3")
        users.create("admin", "geheimes-passwort", isAdmin = true)
        users.create("alice", "geheimes-passwort", isAdmin = false)
        scanner.scan()
        val admin = client.login("admin")
        val alice = client.login("alice")
        val tapping = items.idByAudioPath("Gibi ASMR/tapping.mp3")!!
        val late = items.idByAudioPath("Spicy/late.mp3")!!
        client.put("/api/admin/access") {
            bearerAuth(admin)
            contentType(ContentType.Application.Json)
            setBody(FolderAccessDto("Spicy"))
        }

        // Kategorie mit einem Ordner (rekursiv) und einem einzelnen Track; nur Admins legen an.
        val forbidden = client.post("/api/categories") {
            bearerAuth(alice)
            contentType(ContentType.Application.Json)
            setBody(de.axelcypher.asmr.api.CategoryRequest("Natur", "leaf", "#3B5A4C"))
        }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        val natur = client.post("/api/categories") {
            bearerAuth(admin)
            contentType(ContentType.Application.Json)
            setBody(de.axelcypher.asmr.api.CategoryRequest("Natur", "leaf", "#3B5A4C"))
        }.body<de.axelcypher.asmr.api.CategoryDto>()
        client.put("/api/categories/${natur.id}/folders?path=Natur") { bearerAuth(admin) }
        client.put("/api/categories/${natur.id}/items/$tapping") { bearerAuth(admin) }
        client.put("/api/categories/${natur.id}/items/$late") { bearerAuth(admin) }

        // Ambiente: ein ganzer Ordner und ein einzelner Track (der in die Metadaten-Datei wandert).
        client.put("/api/folders/ambient?path=Natur/Wald") { bearerAuth(admin) }
        client.put("/api/items/$tapping/ambient") { bearerAuth(admin) }
        assertTrue("\"ambient\": true" in Sidecars.pathFor(media.resolve("Gibi ASMR/tapping.mp3")).readText())

        // Playlists: privat, dann geteilt; ändern darf nur der Besitzer.
        val playlist = client.post("/api/playlists") {
            bearerAuth(admin)
            contentType(ContentType.Application.Json)
            setBody(de.axelcypher.asmr.api.PlaylistRequest(name = "Einschlafen"))
        }.body<de.axelcypher.asmr.api.PlaylistDto>()
        client.put("/api/playlists/${playlist.id}/items/$tapping") { bearerAuth(admin) }
        client.put("/api/playlists/${playlist.id}/items/$late") { bearerAuth(admin) }
        assertEquals(HttpStatusCode.NotFound, client.get("/api/playlists/${playlist.id}") { bearerAuth(alice) }.status)
        client.patch("/api/playlists/${playlist.id}") {
            bearerAuth(admin)
            contentType(ContentType.Application.Json)
            setBody(de.axelcypher.asmr.api.PlaylistRequest(shared = true))
        }
        val forAlice = client.get("/api/playlists/${playlist.id}") { bearerAuth(alice) }.body<de.axelcypher.asmr.api.PlaylistDetailDto>()
        assertEquals(listOf(tapping), forAlice.items.map { it.id }, "gesperrter Track bleibt unsichtbar")
        assertFalse(forAlice.playlist.isMine)
        val aliceEdit = client.put("/api/playlists/${playlist.id}/items/$tapping") { bearerAuth(alice) }
        assertEquals(HttpStatusCode.Forbidden, aliceEdit.status)

        val adminHome = client.get("/api/home") { bearerAuth(admin) }.body<de.axelcypher.asmr.api.HomeDto>()
        assertEquals(4, adminHome.categories.single().itemCount)
        assertEquals(setOf("birds", "tapping"), adminHome.ambient.map { it.title }.toSet())
        assertTrue(adminHome.ambient.all { it.isAmbient })
        assertEquals(2, adminHome.playlists.single().itemCount)

        val aliceHome = client.get("/api/home") { bearerAuth(alice) }.body<de.axelcypher.asmr.api.HomeDto>()
        assertEquals(3, aliceHome.categories.single().itemCount, "Spicy zählt für Alice nicht mit")
        assertEquals(setOf("Gibi ASMR", "Natur"), aliceHome.creators.map { it.name }.toSet())
        assertEquals(1, aliceHome.playlists.single().itemCount)

        val inCategory = client.get("/api/items?category=${natur.id}") { bearerAuth(alice) }.body<ItemPage>()
        assertEquals(setOf("rain", "birds", "tapping"), inCategory.items.map { it.title }.toSet())

        // Ordner auf dem NAS umbenannt: Kategorie- und Ambient-Zuordnung wandern mit.
        Files.move(media.resolve("Natur"), media.resolve("Natur & Wetter"))
        scanner.scan()
        val after = client.get("/api/home") { bearerAuth(admin) }.body<de.axelcypher.asmr.api.HomeDto>()
        assertEquals(4, after.categories.single().itemCount)
        assertEquals(listOf("Natur & Wetter/Wald"), runBlocking { ambientFolders.list() })
    }

    @Test
    fun `weboberflaeche, cookie nur fuer lesen, benutzer und reihenfolge`() = api { client ->
        file("a.mp3")
        users.create("admin", "geheimes-passwort", isAdmin = true)
        val bob = users.create("bob", "geheimes-passwort", isAdmin = false)
        scanner.scan()
        val id = items.idByAudioPath("a.mp3")!!

        val page = client.get("/admin/")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue("ASMR Admin" in String(page.bodyAsBytes()))

        // Login aus der Weboberfläche setzt ein HttpOnly-Cookie ...
        val login = client.post("/api/auth/login") {
            header("X-Asmr-Web", "1")
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("admin", "geheimes-passwort"))
        }
        val setCookie = login.headers.getAll(HttpHeaders.SetCookie).orEmpty().single { it.startsWith("$SESSION_COOKIE=") }
        assertTrue("HttpOnly" in setCookie)
        val cookie = setCookie.substringBefore(';')

        // ... das zum Lesen (Audio im <audio>-Tag) reicht, zum Ändern aber nicht (CSRF).
        assertEquals(HttpStatusCode.OK, client.get("/api/items/$id/audio") { header(HttpHeaders.Cookie, cookie) }.status)
        val forged = client.patch("/api/items/$id") {
            header(HttpHeaders.Cookie, cookie)
            contentType(ContentType.Application.Json)
            setBody(UpdateItemRequest(title = "gekapert"))
        }
        assertEquals(HttpStatusCode.Unauthorized, forged.status)

        val token = login.body<LoginResponse>().token
        val promoted = client.patch("/api/users/${bob.id}") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(de.axelcypher.asmr.api.UpdateUserRequest(isAdmin = true, password = "neues-passwort-1"))
        }.body<de.axelcypher.asmr.api.UserDto>()
        assertTrue(promoted.isAdmin)
        assertEquals(HttpStatusCode.OK, client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("bob", "neues-passwort-1"))
        }.status)

        val first = categories.create(de.axelcypher.asmr.api.CategoryRequest("Eins", "leaf", "#3B5A4C"))
        val second = categories.create(de.axelcypher.asmr.api.CategoryRequest("Zwei", "leaf", "#3B5A4C"))
        client.put("/api/categories/order") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(de.axelcypher.asmr.api.OrderRequest(listOf(second.id, first.id)))
        }
        assertEquals(listOf("Zwei", "Eins"), runBlocking { categories.list() }.map { it.name })
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

        // Upload aus der Galerie (rohe Bytes), danach Abruf vom YouTube-Kanal.
        val uploaded = client.put("/api/creators/Gibi ASMR/avatar") {
            bearerAuth(admin)
            contentType(ContentType.Application.OctetStream)
            setBody(PNG)
        }
        assertEquals(HttpStatusCode.OK, uploaded.status)
        assertTrue(uploaded.body<CreatorDto>().hasAvatar)
        val fetched = client.post("/api/creators/Gibi ASMR/avatar/fetch") { bearerAuth(admin) }.body<CreatorDto>()
        assertTrue(fetched.hasAvatar)

        val item = client.get("/api/items/$id") { bearerAuth(admin) }.body<ItemDto>()
        assertTrue(item.hasCover)
        assertEquals(HttpStatusCode.OK, client.get("/api/items/$id/cover") { bearerAuth(admin) }.status)
        // Der gleichnamige Ordner bekommt das Profilbild ebenfalls als Kachel.
        assertTrue(client.folder(admin).folders.single().hasCover)
    }
}

class RenameTest {
    @Test
    fun `gemeinsames ende wird abgeschnitten`() {
        assertEquals("A/Alt" to "A/Neu", de.axelcypher.asmr.server.library.renamedPrefix("A/Alt/x/t.mp3", "A/Neu/x/t.mp3"))
        assertEquals("" to "B", de.axelcypher.asmr.server.library.renamedPrefix("t.mp3", "B/t.mp3"))
    }
}
