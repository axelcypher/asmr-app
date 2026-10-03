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

    suspend fun setCreatorMarked(name: String, marked: Boolean): CreatorDto {
        val url = "${creatorUrl(base(), name)}/marked"
        return (if (marked) httpClient.put(url) else httpClient.delete(url)).body()
    }

    suspend fun fetchAvatar(name: String): CreatorDto =
        httpClient.post("${creatorUrl(base(), name)}/avatar/fetch").body()

    // --- Übersicht ---

    suspend fun home(): de.axelcypher.asmr.api.HomeDto = httpClient.get("${base()}/api/home").body()

    suspend fun catalog(): de.axelcypher.asmr.api.CatalogDto = httpClient.get("${base()}/api/catalog").body()

    /** Gefilterte Trackliste, z.B. `favorites=true`, `ambient=true`, `creator=…`, `category=…`. */
    suspend fun itemsWhere(vararg filters: Pair<String, String>): List<ItemDto> =
        httpClient.get("${base()}/api/items") {
            filters.forEach { (key, value) -> parameter(key, value) }
            parameter("pageSize", 200)
        }.body<ItemPage>().items

    /** Trigger mit Anzahl (nur sichtbare Tracks), für die Filterauswahl. */
    suspend fun tags(): List<de.axelcypher.asmr.api.TagDto> = httpClient.get("${base()}/api/tags").body()

    /** Freie Suche mit Filtern; [tags] müssen alle vorkommen. */
    suspend fun search(
        query: String,
        tags: Collection<String>,
        creator: String?,
        favoritesOnly: Boolean,
        minDuration: Int?,
        maxDuration: Int?,
        sort: de.axelcypher.asmr.api.ItemSort,
    ): List<ItemDto> = httpClient.get("${base()}/api/items") {
        if (query.isNotBlank()) parameter("q", query.trim())
        tags.forEach { parameter("tag", it) }
        creator?.let { parameter("creator", it) }
        if (favoritesOnly) parameter("favorites", "true")
        minDuration?.let { parameter("minDuration", it) }
        maxDuration?.let { parameter("maxDuration", it) }
        parameter("sort", sort.name)
        parameter("pageSize", 200)
    }.body<ItemPage>().items

    suspend fun setFavorite(itemId: Long, favorite: Boolean) {
        val url = "${base()}/api/items/$itemId/favorite"
        if (favorite) httpClient.put(url) else httpClient.delete(url)
    }

    suspend fun setItemAmbient(itemId: Long, ambient: Boolean) {
        val url = "${base()}/api/items/$itemId/ambient"
        if (ambient) httpClient.put(url) else httpClient.delete(url)
    }

    suspend fun setFolderAmbient(path: String, ambient: Boolean) {
        val url = "${base()}/api/folders/ambient"
        if (ambient) httpClient.put(url) { parameter("path", path) } else httpClient.delete(url) { parameter("path", path) }
    }

    // --- Kategorien ---

    suspend fun categories(): List<de.axelcypher.asmr.api.CategoryDto> = httpClient.get("${base()}/api/categories").body()

    suspend fun categoryDetail(id: Long): de.axelcypher.asmr.api.CategoryDetailDto =
        httpClient.get("${base()}/api/categories/$id").body()

    /** Legt an oder ändert; liefert die Id (für das Bild direkt nach dem Anlegen). */
    suspend fun saveCategory(id: Long?, request: de.axelcypher.asmr.api.CategoryRequest): Long {
        if (id == null) {
            return httpClient.post("${base()}/api/categories") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<de.axelcypher.asmr.api.CategoryDto>().id
        }
        httpClient.patch("${base()}/api/categories/$id") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        return id
    }

    suspend fun uploadCategoryImage(id: Long, image: ByteArray) {
        httpClient.put("${base()}/api/categories/$id/image") {
            contentType(ContentType.Application.OctetStream)
            setBody(image)
        }
    }

    suspend fun deleteCategoryImage(id: Long) {
        httpClient.delete("${base()}/api/categories/$id/image")
    }

    suspend fun deleteCategory(id: Long) {
        httpClient.delete("${base()}/api/categories/$id")
    }

    suspend fun setCategoryItem(categoryId: Long, itemId: Long, member: Boolean) {
        val url = "${base()}/api/categories/$categoryId/items/$itemId"
        if (member) httpClient.put(url) else httpClient.delete(url)
    }

    suspend fun setCategoryFolder(categoryId: Long, path: String, member: Boolean) {
        val url = "${base()}/api/categories/$categoryId/folders"
        if (member) httpClient.put(url) { parameter("path", path) } else httpClient.delete(url) { parameter("path", path) }
    }

    // --- Playlists ---

    suspend fun playlists(): List<de.axelcypher.asmr.api.PlaylistDto> = httpClient.get("${base()}/api/playlists").body()

    suspend fun playlist(id: Long): de.axelcypher.asmr.api.PlaylistDetailDto =
        httpClient.get("${base()}/api/playlists/$id").body()

    suspend fun createPlaylist(name: String): de.axelcypher.asmr.api.PlaylistDto =
        httpClient.post("${base()}/api/playlists") {
            contentType(ContentType.Application.Json)
            setBody(de.axelcypher.asmr.api.PlaylistRequest(name = name))
        }.body()

    suspend fun updatePlaylist(id: Long, name: String? = null, shared: Boolean? = null) {
        httpClient.patch("${base()}/api/playlists/$id") {
            contentType(ContentType.Application.Json)
            setBody(de.axelcypher.asmr.api.PlaylistRequest(name, shared))
        }
    }

    suspend fun deletePlaylist(id: Long) {
        httpClient.delete("${base()}/api/playlists/$id")
    }

    suspend fun setPlaylistItem(playlistId: Long, itemId: Long, member: Boolean) {
        val url = "${base()}/api/playlists/$playlistId/items/$itemId"
        if (member) httpClient.put(url) else httpClient.delete(url)
    }

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

        fun categoryImageUrl(serverUrl: String, id: Long) = "$serverUrl/api/categories/$id/image"
    }
}

/**
 * Ergänzt ein fehlendes Schema (https), entfernt abschließende Schrägstriche und schreibt Schema und
 * Host klein: die Tastatur macht gern "Https://" daraus, und Coil erkennt dann keine Web-Adresse.
 */
fun normalizeServerUrl(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val scheme = withScheme.substringBefore("://").lowercase()
    val rest = withScheme.substringAfter("://")
    val host = rest.substringBefore('/').lowercase()
    val path = rest.removePrefix(rest.substringBefore('/'))
    return "$scheme://$host$path"
}
