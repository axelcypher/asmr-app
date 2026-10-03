package de.axelcypher.asmr.server.auth

import de.axelcypher.asmr.server.OidcConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.basicAuth
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class OidcIdentity(
    val subject: String,
    val username: String?,
    val displayName: String?,
    val email: String?,
    val emailVerified: Boolean,
    val groups: List<String>,
)

class OidcException(message: String) : Exception(message)

/**
 * Authorization Code Flow mit PKCE gegen einen OIDC-Provider (z.B. Authentik). Funktioniert als
 * Public Client (ohne Secret) und als Confidential Client (`client_secret_basic`).
 */
class OidcClient(
    private val config: OidcConfig,
    private val http: HttpClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Endpoints(val authorize: String, val token: String, val userinfo: String)

    @Volatile
    private var cached: Pair<Long, Endpoints>? = null

    private suspend fun endpoints(): Endpoints {
        cached?.let { (at, endpoints) -> if (now() - at < DISCOVERY_TTL_MS) return endpoints }
        val response = http.get(config.discoveryUrl)
        if (!response.status.isSuccess()) throw OidcException("Discovery antwortet mit ${response.status.value}")
        val doc = response.body<JsonObject>()
        fun field(name: String) = doc[name]?.jsonPrimitive?.contentOrNull
            ?: throw OidcException("Discovery-Dokument ohne $name")
        val endpoints = Endpoints(field("authorization_endpoint"), field("token_endpoint"), field("userinfo_endpoint"))
        cached = now() to endpoints
        return endpoints
    }

    suspend fun authorizationUrl(redirectUri: String, state: String, nonce: String, codeChallenge: String): String =
        URLBuilder(endpoints().authorize).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", config.clientId)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("scope", config.scopes)
            parameters.append("state", state)
            parameters.append("nonce", nonce)
            parameters.append("code_challenge", codeChallenge)
            parameters.append("code_challenge_method", "S256")
        }.buildString()

    /** Tauscht den Code gegen Tokens und liest die Identität vom Userinfo-Endpunkt. */
    suspend fun identity(code: String, redirectUri: String, codeVerifier: String): OidcIdentity {
        val endpoints = endpoints()
        val tokenResponse = http.submitForm(
            url = endpoints.token,
            formParameters = parameters {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", redirectUri)
                append("code_verifier", codeVerifier)
                append("client_id", config.clientId)
            },
        ) {
            config.clientSecret?.let { basicAuth(config.clientId, it) }
        }
        if (!tokenResponse.status.isSuccess()) {
            throw OidcException("Token-Endpunkt antwortet mit ${tokenResponse.status.value}: ${tokenResponse.bodyAsText().take(300)}")
        }
        val accessToken = tokenResponse.body<JsonObject>()["access_token"]?.jsonPrimitive?.contentOrNull
            ?: throw OidcException("Token-Antwort ohne access_token")

        val userinfo = http.get(endpoints.userinfo) { bearerAuth(accessToken) }
        if (!userinfo.status.isSuccess()) {
            val reason = userinfo.headers["WWW-Authenticate"] ?: userinfo.bodyAsText().take(300)
            throw OidcException("Userinfo antwortet mit ${userinfo.status.value}: $reason")
        }
        return toIdentity(userinfo.body<JsonObject>())
    }

    internal fun toIdentity(claims: JsonObject): OidcIdentity {
        fun string(name: String) = (claims[name] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }
        return OidcIdentity(
            subject = string("sub") ?: throw OidcException("Provider liefert keine Nutzerkennung (sub)"),
            username = string(config.usernameClaim),
            displayName = string("name"),
            email = string("email"),
            emailVerified = (claims["email_verified"] as? JsonPrimitive)?.booleanOrNull ?: false,
            groups = (claims["groups"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
        )
    }

    private companion object {
        const val DISCOVERY_TTL_MS = 5 * 60 * 1000L
    }
}
