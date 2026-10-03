package de.axelcypher.asmr.data.api

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
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException

/**
 * Zugriff auf die Audiobookshelf REST API. Tokens kommen aus dem [SessionStore]; ein abgelaufenes
 * Access-Token wird über `/auth/refresh` erneuert. Scheitert das, wird die Session gelöscht und die
 * App springt zurück zum Login.
 */
class AbsClient(engine: HttpClientEngine, private val sessionStore: SessionStore) {

    val httpClient = HttpClient(engine) {
        expectSuccess = true
        install(ContentNegotiation) { json(AbsJson) }
        install(Auth) {
            bearer {
                loadTokens {
                    sessionStore.current()?.let { BearerTokens(it.accessToken, it.refreshToken) }
                }
                refreshTokens {
                    val session = sessionStore.current() ?: return@refreshTokens null
                    val refreshToken = session.refreshToken
                    if (refreshToken == null) {
                        sessionStore.clear()
                        return@refreshTokens null
                    }
                    try {
                        val user = client.post("${session.serverUrl}/auth/refresh") {
                            markAsRefreshTokenRequest()
                            header("x-refresh-token", refreshToken)
                        }.body<LoginResponse>().user
                        val accessToken = user.accessToken ?: error("Kein Access-Token in der Antwort")
                        val newRefreshToken = user.refreshToken ?: refreshToken
                        sessionStore.updateTokens(accessToken, newRefreshToken)
                        BearerTokens(accessToken, newRefreshToken)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        sessionStore.clear()
                        null
                    }
                }
                sendWithoutRequest { request -> request.url.pathSegments.lastOrNull() != "login" }
            }
        }
    }

    suspend fun login(serverUrl: String, username: String, password: String): Session {
        val baseUrl = normalizeServerUrl(serverUrl)
        val user = httpClient.post("$baseUrl/login") {
            contentType(ContentType.Application.Json)
            header("x-return-tokens", "true")
            setBody(LoginRequest(username, password))
        }.body<LoginResponse>().user
        val accessToken = user.accessToken ?: user.token ?: error("Server hat kein Token geliefert")
        clearCachedTokens()
        return Session(baseUrl, user.username, accessToken, user.refreshToken)
    }

    suspend fun logout() {
        sessionStore.clear()
        clearCachedTokens()
    }

    suspend fun libraries(): List<AbsLibrary> =
        httpClient.get("${requireSession().serverUrl}/api/libraries")
            .body<LibrariesResponse>().libraries

    suspend fun libraryItems(libraryId: String, page: Int, pageSize: Int = PAGE_SIZE): ItemPage {
        val response = httpClient.get("${requireSession().serverUrl}/api/libraries/$libraryId/items") {
            parameter("limit", pageSize)
            parameter("page", page)
            parameter("sort", "media.metadata.title")
            parameter("minified", 1)
        }.body<LibraryItemsResponse>()
        return ItemPage(response.results.map { it.toAsmrItem() }, response.total)
    }

    private suspend fun requireSession(): Session =
        sessionStore.current() ?: throw IllegalStateException("Nicht angemeldet")

    private fun clearCachedTokens() {
        httpClient.authProviders.filterIsInstance<BearerAuthProvider>().forEach { it.clearToken() }
    }

    companion object {
        const val PAGE_SIZE = 60

        fun coverUrl(serverUrl: String, itemId: String) = "$serverUrl/api/items/$itemId/cover"
    }
}

/** Ergänzt ein fehlendes Schema (https) und entfernt abschließende Schrägstriche. */
fun normalizeServerUrl(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    return if (trimmed.contains("://")) trimmed else "https://$trimmed"
}
