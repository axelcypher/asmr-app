package de.axelcypher.asmr.server.routes

import de.axelcypher.asmr.api.AuthConfigDto
import de.axelcypher.asmr.api.LoginRequest
import de.axelcypher.asmr.api.LoginResponse
import de.axelcypher.asmr.api.SsoConfigDto
import de.axelcypher.asmr.api.SsoExchangeRequest
import de.axelcypher.asmr.server.ApiException
import de.axelcypher.asmr.server.Services
import de.axelcypher.asmr.server.auth.OidcException
import de.axelcypher.asmr.server.auth.Passwords
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** Öffentliche Endpunkte rund um die Anmeldung. */
fun Route.authRoutes(services: Services) = route("/auth") {
    get("/config") {
        call.respond(AuthConfigDto(passwordLogin = true, sso = services.sso?.let { SsoConfigDto(it.providerName) }))
    }

    post("/login") {
        val request = call.receive<LoginRequest>()
        val user = services.users.byUsername(request.username.trim())
        val hash = user?.passwordHash
        if (user == null || hash == null || !Passwords.verify(request.password, hash)) {
            throw ApiException(HttpStatusCode.Unauthorized, "Benutzername oder Passwort falsch")
        }
        call.respond(LoginResponse(services.users.createSession(user.id), user.toDto()))
    }

    route("/sso") {
        get("/start") {
            val sso = services.sso ?: throw ApiException(HttpStatusCode.NotFound, "SSO ist nicht eingerichtet")
            val challenge = call.request.queryParameters["code_challenge"]
                ?: throw ApiException(HttpStatusCode.BadRequest, "code_challenge fehlt")
            val url = try {
                sso.start(challenge)
            } catch (e: OidcException) {
                throw ApiException(HttpStatusCode.BadGateway, e.message ?: "SSO nicht erreichbar")
            }
            call.respondRedirect(url)
        }

        get("/callback") {
            val sso = services.sso ?: throw ApiException(HttpStatusCode.NotFound, "SSO ist nicht eingerichtet")
            val params = call.request.queryParameters
            call.respondRedirect(sso.callback(params["state"], params["code"], params["error"]))
        }

        post("/exchange") {
            val sso = services.sso ?: throw ApiException(HttpStatusCode.NotFound, "SSO ist nicht eingerichtet")
            val request = call.receive<SsoExchangeRequest>()
            val user = sso.exchange(request.code, request.codeVerifier)?.let { services.users.byId(it) }
                ?: throw ApiException(HttpStatusCode.Unauthorized, "Anmeldecode ungültig oder abgelaufen")
            call.respond(LoginResponse(services.users.createSession(user.id), user.toDto()))
        }
    }
}
