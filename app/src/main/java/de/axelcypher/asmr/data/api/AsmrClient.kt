package de.axelcypher.asmr.data.api

import de.axelcypher.asmr.api.ApiJson
import de.axelcypher.asmr.api.AccessOverviewDto
import de.axelcypher.asmr.api.AuthConfigDto
import de.axelcypher.asmr.api.ChangePasswordRequest
import de.axelcypher.asmr.api.CreateFolderRequest
import de.axelcypher.asmr.api.CreateUserRequest
import de.axelcypher.asmr.api.CreatorDto
import de.axelcypher.asmr.api.FolderAccessDto
import de.axelcypher.asmr.api.FolderListing
import de.axelcypher.asmr.api.ImportJobDto
import de.axelcypher.asmr.api.ImportRequest
import de.axelcypher.asmr.api.ItemDto
import de.axelcypher.asmr.api.ItemPage
import de.axelcypher.asmr.api.LoginRequest
import de.axelcypher.asmr.api.LoginResponse
import de.axelcypher.asmr.api.SsoExchangeRequest
import de.axelcypher.asmr.api.UpdateCreatorRequest
import de.axelcypher.asmr.api.UpdateItemRequest
import de.axelcypher.asmr.api.UserDto
import de.axelcypher.asmr.data.settings.Session
import de.axelcypher.asmr.data.settings.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.authProviders
import io.ktor.client.plugins.auth.providers.BearerAuthProvider
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.encodeURLPathPart
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException

/**
 * Zugriff auf den ASMR-Server. Das Session-Token kommt aus dem [SessionStore]; antwortet der Server
 * mit 401, ist die Session abgelaufen oder widerrufen und die App springt zurück zum Login.
 */
class AsmrClient(engine: HttpClientEngine, private val sessionStore: SessionStore) {

    val httpClient = HttpClient(engine) {
        expectSuccess = true
        install(ContentNegotiation) { json(ApiJson) }
        install(Auth) {
            bearer {
                loadTokens { sessionStore.current()?.let { BearerTokens(it.token, null) } }
                refreshTokens {
                    sessionStore.clear()
                    null
                }
                sendWithoutRequest { true }
            }
        }
    }

    suspend fun authConfig(serverUrl: String): AuthConfigDto =
        httpClient.get("${normalizeServerUrl(serverUrl)}/api/auth/config").body()

    suspend fun login(serverUrl: String, username: String, password: String): Session {
        val baseUrl = normalizeServerUrl(serverUrl)
        val response = httpClient.post("$baseUrl/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username.trim(), password))
        }.body<LoginResponse>()
        return response.toSession(baseUrl)
    }

    fun ssoStartUrl(serverUrl: String, codeChallenge: String): String =
        URLBuilder("${normalizeServerUrl(serverUrl)}/api/auth/sso/start").apply {
            parameters.append("code_challenge", codeChallenge)
        }.buildString()

    suspend fun ssoExchange(serverUrl: String, code: String, codeVerifier: String): Session {
        val baseUrl = normalizeServerUrl(serverUrl)
        val response = httpClient.post("$baseUrl/api/auth/sso/exchange") {
            contentType(ContentType.Application.Json)
            setBody(SsoExchangeRequest(code, codeVerifier))
        }.body<LoginResponse>()
        return response.toSession(baseUrl)
    }

    suspend fun logout() {
        val session = sessionStore.current()
        if (session != null) {
            try {
                httpClient.post("${session.serverUrl}/api/auth/logout")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Abmelden klappt lokal auch ohne Server.
            }
        }
        sessionStore.clear()
        clearCachedTokens()
    }

    suspend fun items(page: Int, pageSize: Int = PAGE_SIZE): ItemPage =
        httpClient.get("${requireSession().serverUrl}/api/items") {
            parameter("page", page)
            parameter("pageSize", pageSize)
        }.body()

    suspend fun startImport(url: String): ImportJobDto =
        httpClient.post("${requireSession().serverUrl}/api/imports") {
            contentType(ContentType.Application.Json)
            setBody(ImportRequest(url.trim()))
        }.body()

    suspend fun imports(): List<ImportJobDto> =
        httpClient.get("${requireSession().serverUrl}/api/imports").body()

    // --- Profil und Verwaltung ---

    suspend fun me(): UserDto = httpClient.get("${requireSession().serverUrl}/api/me").body()

    suspend fun changePassword(currentPassword: String?, newPassword: String) {
        httpClient.put("${requireSession().serverUrl}/api/me/password") {
            contentType(ContentType.Application.Json)
            setBody(ChangePasswordRequest(currentPassword, newPassword))
        }
    }

    suspend fun users(): List<UserDto> = httpClient.get("${requireSession().serverUrl}/api/users").body()

    suspend fun createUser(username: String, password: String, isAdmin: Boolean): UserDto =
        httpClient.post("${requireSession().serverUrl}/api/users") {
            contentType(ContentType.Application.Json)
            setBody(CreateUserRequest(username.trim(), password, isAdmin))
        }.body()

    suspend fun deleteUser(id: Long) {
        httpClient.delete("${requireSession().serverUrl}/api/users/$id")
    }

    suspend fun scanLibrary() {
        httpClient.post("${requireSession().serverUrl}/api/library/scan")
    }

    // --- Ordner und Bearbeiten ---

    suspend fun folder(path: String): FolderListing =
        httpClient.get("${base()}/api/folders") { parameter("path", path) }.body()

    suspend fun allFolders(): List<String> = httpClient.get("${base()}/api/folders/all").body()

    suspend fun createFolder(path: String) {
        httpClient.post("${base()}/api/folders") {
            contentType(ContentType.Application.Json)
            setBody(CreateFolderRequest(path))
        }
    }

    suspend fun updateItem(id: Long, request: UpdateItemRequest): ItemDto =
        httpClient.patch("${base()}/api/items/$id") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun deleteItem(id: Long) {
        httpClient.delete("${base()}/api/items/$id")
    }

    // --- Zugriff ---

    suspend fun accessOverview(): AccessOverviewDto = httpClient.get("${base()}/api/admin/access").body()

    suspend fun setAccess(rule: FolderAccessDto) {
        httpClient.put("${base()}/api/admin/access") {
            contentType(ContentType.Application.Json)
            setBody(rule)
        }
    }

    suspend fun removeAccess(path: String) {
        httpClient.delete("${base()}/api/admin/access") { parameter("path", path) }
    }

    // --- Creator ---

    suspend fun creator(name: String): CreatorDto = httpClient.get(creatorUrl(base(), name)).body()

    suspend fun setCreatorLinks(name: String, links: List<String>): CreatorDto =
        httpClient.put(creatorUrl(base(), name)) {
            contentType(ContentType.Application.Json)
            setBody(UpdateCreatorRequest(links))
        }.body()

    suspend fun uploadAvatar(name: String, image: ByteArray): CreatorDto =
        httpClient.put("${creatorUrl(base(), name)}/avatar") {
            contentType(ContentType.Application.OctetStream)
            setBody(image)
        }.body()

    suspend fun fetchAvatar(name: String): CreatorDto =
        httpClient.post("${creatorUrl(base(), name)}/avatar/fetch").body()

    private suspend fun base() = requireSession().serverUrl

    private fun LoginResponse.toSession(baseUrl: String): Session {
        clearCachedTokens()
        return Session(baseUrl, user.username, token)
    }

    private suspend fun requireSession(): Session =
        sessionStore.current() ?: throw IllegalStateException("Nicht angemeldet")

    private fun clearCachedTokens() {
        httpClient.authProviders.filterIsInstance<BearerAuthProvider>().forEach { it.clearToken() }
    }

    companion object {
        const val PAGE_SIZE = 60

        fun coverUrl(serverUrl: String, itemId: Long) = "$serverUrl/api/items/$itemId/cover"

        fun audioUrl(serverUrl: String, itemId: Long) = "$serverUrl/api/items/$itemId/audio"

        fun folderCoverUrl(serverUrl: String, path: String) =
            URLBuilder("$serverUrl/api/folders/cover").apply { parameters.append("path", path) }.buildString()

        fun creatorUrl(serverUrl: String, name: String) = "$serverUrl/api/creators/${name.encodeURLPathPart()}"

        fun avatarUrl(serverUrl: String, name: String) = "${creatorUrl(serverUrl, name)}/avatar"
    }
}

/** Ergänzt ein fehlendes Schema (https) und entfernt abschließende Schrägstriche. */
fun normalizeServerUrl(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    return if (trimmed.contains("://")) trimmed else "https://$trimmed"
}
