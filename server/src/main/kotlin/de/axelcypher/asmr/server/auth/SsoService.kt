package de.axelcypher.asmr.server.auth

import de.axelcypher.asmr.api.APP_SSO_REDIRECT
import de.axelcypher.asmr.server.AccountMatching
import de.axelcypher.asmr.server.OidcConfig
import de.axelcypher.asmr.server.db.UserRecord
import de.axelcypher.asmr.server.db.UserStore
import io.ktor.http.URLBuilder
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * SSO-Login für die App:
 * 1. Die App öffnet `/api/auth/sso/start?code_challenge=…` im Browser (Custom Tab).
 * 2. Der Server leitet zum Provider weiter und erhält dort den Callback.
 * 3. Der Server leitet mit einem Einmal-Code auf [APP_SSO_REDIRECT] zurück.
 * 4. Die App tauscht Code + `code_verifier` gegen ein Session-Token.
 *
 * Der Einmal-Code ist an die PKCE-Challenge der App gebunden, damit eine fremde App, die den Redirect
 * abfängt, damit nichts anfangen kann. Zustände liegen nur im Speicher (eine Server-Instanz).
 */
class SsoService(
    private val config: OidcConfig,
    private val oidc: OidcClient,
    private val users: UserStore,
    private val callbackUrl: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(SsoService::class.java)

    private data class PendingLogin(val codeVerifier: String, val appChallenge: String, val createdAt: Long)
    private data class LoginCode(val userId: Long, val appChallenge: String, val createdAt: Long)

    private val pending = ConcurrentHashMap<String, PendingLogin>()
    private val codes = ConcurrentHashMap<String, LoginCode>()

    val providerName get() = config.providerName

    suspend fun start(appChallenge: String): String {
        if (!CHALLENGE.matches(appChallenge)) throw OidcException("Ungültige code_challenge")
        cleanup()
        val state = randomToken()
        val verifier = randomToken(48)
        pending[state] = PendingLogin(verifier, appChallenge, now())
        return oidc.authorizationUrl(callbackUrl, state, nonce = randomToken(), codeChallenge = pkceChallenge(verifier))
    }

    /** Verarbeitet den Provider-Callback und liefert die URL, auf die die App zurückgeleitet wird. */
    suspend fun callback(state: String?, code: String?, error: String?): String {
        val login = state?.let { pending.remove(it) }
        val result = try {
            when {
                error != null -> throw OidcException("Anmeldung abgebrochen: $error")
                login == null || now() - login.createdAt > FLOW_TTL_MS ->
                    throw OidcException("Anmeldung abgelaufen, bitte erneut versuchen")
                code == null -> throw OidcException("Provider hat keinen Code geliefert")
                else -> {
                    val user = resolveUser(oidc.identity(code, callbackUrl, login.codeVerifier))
                    val loginCode = randomToken()
                    codes[loginCode] = LoginCode(user.id, login.appChallenge, now())
                    "code" to loginCode
                }
            }
        } catch (e: OidcException) {
            log.warn("SSO-Login fehlgeschlagen: {}", e.message)
            "error" to (e.message ?: "SSO fehlgeschlagen")
        }
        return URLBuilder(APP_SSO_REDIRECT).apply { parameters.append(result.first, result.second) }.buildString()
    }

    /** Liefert die Benutzer-ID für einen gültigen Einmal-Code, oder null. */
    fun exchange(code: String, codeVerifier: String): Long? {
        val entry = codes.remove(code) ?: return null
        if (now() - entry.createdAt > CODE_TTL_MS) return null
        if (!constantTimeEquals(pkceChallenge(codeVerifier), entry.appChallenge)) return null
        return entry.userId
    }

    internal suspend fun resolveUser(identity: OidcIdentity): UserRecord {
        val isAdmin = config.adminGroup?.let { it in identity.groups }

        users.bySubject(identity.subject)?.let { user ->
            users.updateFromSso(user.id, identity.displayName, identity.email, isAdmin, identity.groups)
            return users.byId(user.id)!!
        }

        if (config.accountMatching >= AccountMatching.EMAIL && identity.email != null) {
            val match = users.unlinkedByEmail(identity.email)
            if (match != null) {
                if (!identity.emailVerified) {
                    throw OidcException("E-Mail-Adresse beim Provider nicht bestätigt, Konto wird nicht verknüpft")
                }
                users.linkSubject(match.id, identity.subject)
                users.updateFromSso(match.id, identity.displayName, null, isAdmin, identity.groups)
                log.info("SSO-Identität mit bestehendem Konto {} verknüpft", match.username)
                return users.byId(match.id)!!
            }
        }

        if (config.accountMatching == AccountMatching.AUTO_PROVISION) {
            val username = identity.username ?: identity.email?.substringBefore('@') ?: "user"
            val user = users.create(
                username = username,
                password = null,
                isAdmin = isAdmin ?: false,
                displayName = identity.displayName,
                email = identity.email,
                oidcSubject = identity.subject,
            )
            users.updateFromSso(user.id, null, null, null, identity.groups)
            log.info("Konto {} per SSO angelegt", user.username)
            return users.byId(user.id)!!
        }

        throw OidcException("Für diese Identität gibt es kein Konto")
    }

    private fun cleanup() {
        val cutoff = now() - FLOW_TTL_MS
        pending.entries.removeIf { it.value.createdAt < cutoff }
        codes.entries.removeIf { it.value.createdAt < cutoff }
    }

    private companion object {
        const val FLOW_TTL_MS = 10 * 60 * 1000L
        const val CODE_TTL_MS = 2 * 60 * 1000L
        val CHALLENGE = Regex("[A-Za-z0-9_-]{43}")
    }
}
