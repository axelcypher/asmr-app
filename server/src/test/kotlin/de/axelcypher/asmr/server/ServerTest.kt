package de.axelcypher.asmr.server

import de.axelcypher.asmr.api.APP_SSO_REDIRECT
import de.axelcypher.asmr.api.ApiJson
import de.axelcypher.asmr.api.ImportJobDto
import de.axelcypher.asmr.api.ImportRequest
import de.axelcypher.asmr.api.ImportStatus
import de.axelcypher.asmr.api.ItemPage
import de.axelcypher.asmr.api.LoginRequest
import de.axelcypher.asmr.api.LoginResponse
import de.axelcypher.asmr.api.SsoExchangeRequest
import de.axelcypher.asmr.api.UserDto
import de.axelcypher.asmr.server.auth.OidcClient
import de.axelcypher.asmr.server.auth.SsoService
import de.axelcypher.asmr.server.auth.pkceChallenge
import de.axelcypher.asmr.server.auth.randomToken
import de.axelcypher.asmr.server.db.Database
import de.axelcypher.asmr.server.db.ImportStore
import de.axelcypher.asmr.server.db.ItemStore
import de.axelcypher.asmr.server.db.UserStore
import de.axelcypher.asmr.server.imports.Download
import de.axelcypher.asmr.server.imports.Downloader
import de.axelcypher.asmr.server.imports.ImportWorker
import de.axelcypher.asmr.server.imports.SourceInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

class ServerTest {

    private val root: Path = Files.createTempDirectory("asmr-test")
    private val mediaDir = root.resolve("media").createDirectories()
    private val db = Database.open("jdbc:sqlite::memory:")
    private val users = UserStore(db)
    private val items = ItemStore(db)
    private val imports = ImportStore(db)

    private var downloads = 0
    private val fakeDownloader = Downloader { url, dir ->
        downloads++
        val audio = dir.resolve("media.opus").also { it.writeBytes(AUDIO) }
        val cover = dir.resolve("media.jpg").also { it.writeText("jpg") }
        Download(
            audio, cover,
            SourceInfo(
                id = "vid${url.substringAfterLast('=')}",
                extractor = "youtube",
                title = "Gentle Rain Tapping: ${url.substringAfterLast('=')}",
                uploader = "Gibi ASMR",
                durationSeconds = 1834.0,
                description = "Whispered personal attention",
                tags = listOf("asmr"),
                webpageUrl = url,
            ),
        )
    }
    private val worker = ImportWorker(imports, items, fakeDownloader, root.resolve("tmp"), mediaDir)

    @AfterTest
    fun cleanup() {
        db.close()
        root.toFile().deleteRecursively()
    }

    private fun serverTest(sso: SsoService? = null, block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) {
        val services = Services(users, items, imports, worker, sso, mediaDir)
        testApplication {
            application { asmrModule(services) }
            val client = createClient {
                install(ClientContentNegotiation) { json(ApiJson) }
                followRedirects = false
            }
            block(client)
        }
    }

    private suspend fun HttpClient.login(username: String, password: String): String =
        post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username, password))
        }.body<LoginResponse>().token

    @Test
    fun `login und geschuetzte endpunkte`() = serverTest { client ->
        users.create("tobias", "geheimes-passwort", isAdmin = true)

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me").status)
        val wrong = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("tobias", "falsch"))
        }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)

        val token = client.login("TOBIAS", "geheimes-passwort")
        val me = client.get("/api/me") { bearerAuth(token) }.body<UserDto>()
        assertEquals("tobias", me.username)
        assertTrue(me.isAdmin)

        client.post("/api/auth/logout") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me") { bearerAuth(token) }.status)
    }

    @Test
    fun `import, bibliothek, streaming und favoriten`() = serverTest { client ->
        users.create("tobias", "geheimes-passwort", isAdmin = false)
        val token = client.login("tobias", "geheimes-passwort")

        val job = client.post("/api/imports") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(ImportRequest("https://www.youtube.com/watch?v=1"))
        }.body<ImportJobDto>()
        worker.process(job.id)
        // Zweiter Import derselben Quelle darf kein Duplikat erzeugen.
        val again = imports.create("https://www.youtube.com/watch?v=1", 1)
        worker.process(again.id)

        val done = client.get("/api/imports/${job.id}") { bearerAuth(token) }.body<ImportJobDto>()
        assertEquals(ImportStatus.DONE, done.status)
        assertEquals(done.itemId, imports.get(again.id)!!.itemId)

        val page = client.get("/api/items") { bearerAuth(token) }.body<ItemPage>()
        assertEquals(1, page.total)
        val item = page.items.single()
        assertEquals("Gibi ASMR", item.creator)
        assertEquals(listOf("Personal Attention", "Rain", "Tapping", "Whispering"), item.tags)
        assertTrue(item.hasCover)
        assertTrue(mediaDir.resolve("Gibi ASMR/Gentle Rain Tapping_ 1 [vid1].opus").exists())

        val range = client.get("/api/items/${item.id}/audio") {
            bearerAuth(token)
            header(HttpHeaders.Range, "bytes=2-5")
        }
        assertEquals(HttpStatusCode.PartialContent, range.status)
        assertContentEquals(AUDIO.copyOfRange(2, 6), range.bodyAsBytes())

        client.put("/api/items/${item.id}/favorite") { bearerAuth(token) }
        val favorites = client.get("/api/items?favorites=true") { bearerAuth(token) }.body<ItemPage>()
        assertTrue(favorites.items.single().isFavorite)

        val filtered = client.get("/api/items?tag=rain&tag=Brushing") { bearerAuth(token) }.body<ItemPage>()
        assertEquals(0, filtered.total)
        val search = client.get("/api/items?q=gentle&tag=RAIN") { bearerAuth(token) }.body<ItemPage>()
        assertEquals(1, search.total)

        // Löschen nur für Admins.
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/items/${item.id}") { bearerAuth(token) }.status)
    }

    @Test
    fun `import akzeptiert nur http urls`() = serverTest { client ->
        users.create("tobias", "geheimes-passwort", isAdmin = false)
        val token = client.login("tobias", "geheimes-passwort")
        val response = client.post("/api/imports") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(ImportRequest("file:///etc/passwd"))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, downloads)
    }

    @Test
    fun `sso flow mit auto provisioning und admin gruppe`() {
        val idp = MockEngine { request ->
            val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            when (request.url.encodedPath) {
                "/.well-known/openid-configuration" -> respond(
                    """{"authorization_endpoint":"https://idp.test/authorize",
                        "token_endpoint":"https://idp.test/token",
                        "userinfo_endpoint":"https://idp.test/userinfo"}""",
                    headers = json,
                )
                "/token" -> {
                    val form = (request.body as FormDataContent).formData
                    check(form["code"] == "idp-code" && form["code_verifier"] != null)
                    respond("""{"access_token":"at","token_type":"Bearer"}""", headers = json)
                }
                "/userinfo" -> respond(
                    """{"sub":"abc-123","preferred_username":"tobias","name":"Tobias",
                        "email":"t@example.de","email_verified":true,"groups":["admin","users"]}""",
                    headers = json,
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val oidcConfig = OidcConfig(
            providerName = "Authentik",
            discoveryUrl = "https://idp.test/.well-known/openid-configuration",
            clientId = "asmr",
            clientSecret = null,
            scopes = "openid email profile",
            usernameClaim = "preferred_username",
            adminGroup = "admin",
            accountMatching = AccountMatching.AUTO_PROVISION,
        )
        val http = HttpClient(idp) { install(ClientContentNegotiation) { json(ApiJson) } }
        val sso = SsoService(oidcConfig, OidcClient(oidcConfig, http), users, "https://asmr.test/api/auth/sso/callback")

        serverTest(sso) { client ->
            val verifier = randomToken(48)
            val start = client.get("/api/auth/sso/start?code_challenge=${pkceChallenge(verifier)}")
            assertEquals(HttpStatusCode.Found, start.status)
            val authorize = Url(start.headers[HttpHeaders.Location]!!)
            assertEquals("S256", authorize.parameters["code_challenge_method"])
            val state = assertNotNull(authorize.parameters["state"])

            val callback = client.get("/api/auth/sso/callback?state=$state&code=idp-code")
            val appRedirect = Url(callback.headers[HttpHeaders.Location]!!)
            assertTrue(appRedirect.toString().startsWith(APP_SSO_REDIRECT))
            val code = assertNotNull(appRedirect.parameters["code"])

            // Falscher Verifier: abgelehnt, und der Code ist danach verbraucht.
            val stolen = client.post("/api/auth/sso/exchange") {
                contentType(ContentType.Application.Json)
                setBody(SsoExchangeRequest(code, randomToken(48)))
            }
            assertEquals(HttpStatusCode.Unauthorized, stolen.status)

            // Neuer Durchlauf mit korrektem Verifier.
            val start2 = client.get("/api/auth/sso/start?code_challenge=${pkceChallenge(verifier)}")
            val state2 = Url(start2.headers[HttpHeaders.Location]!!).parameters["state"]
            val code2 = Url(
                client.get("/api/auth/sso/callback?state=$state2&code=idp-code").headers[HttpHeaders.Location]!!,
            ).parameters["code"]!!
            val login = client.post("/api/auth/sso/exchange") {
                contentType(ContentType.Application.Json)
                setBody(SsoExchangeRequest(code2, verifier))
            }.body<LoginResponse>()
            assertEquals("tobias", login.user.username)
            assertTrue(login.user.isAdmin)
            assertFalse(login.user.hasPassword)

            val replay = client.get("/api/auth/sso/callback?state=$state2&code=idp-code")
            assertTrue(Url(replay.headers[HttpHeaders.Location]!!).parameters.contains("error"))
            assertEquals(1, users.count())
        }
    }

    private companion object {
        val AUDIO = ByteArray(16) { it.toByte() }
    }
}
