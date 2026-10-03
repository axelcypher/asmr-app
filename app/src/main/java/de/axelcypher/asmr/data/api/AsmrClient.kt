package de.axelcypher.asmr.data.api

import de.axelcypher.asmr.api.ApiJson
import de.axelcypher.asmr.api.AuthConfigDto
import de.axelcypher.asmr.api.ItemPage
import de.axelcypher.asmr.api.LoginRequest
import de.axelcypher.asmr.api.LoginResponse
import de.axelcypher.asmr.api.SsoExchangeRequest
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
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
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
    }
}

/** Ergänzt ein fehlendes Schema (https) und entfernt abschließende Schrägstriche. */
fun normalizeServerUrl(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    return if (trimmed.contains("://")) trimmed else "https://$trimmed"
}
