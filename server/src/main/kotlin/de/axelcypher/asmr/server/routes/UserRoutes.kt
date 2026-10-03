package de.axelcypher.asmr.server.routes

import de.axelcypher.asmr.api.ChangePasswordRequest
import de.axelcypher.asmr.api.CreateUserRequest
import de.axelcypher.asmr.server.ApiException
import de.axelcypher.asmr.server.Services
import de.axelcypher.asmr.server.UserPrincipal
import de.axelcypher.asmr.server.auth.Passwords
import de.axelcypher.asmr.server.clearSessionCookie
import de.axelcypher.asmr.server.currentUser
import de.axelcypher.asmr.server.longParameter
import de.axelcypher.asmr.server.requireAdmin
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put

private const val MIN_PASSWORD_LENGTH = 10

fun Route.userRoutes(services: Services) {
    post("/auth/logout") {
        services.users.deleteSession(call.principal<UserPrincipal>()!!.token)
        call.clearSessionCookie()
        call.respond(HttpStatusCode.NoContent)
    }

    get("/me") { call.respond(call.currentUser().toDto()) }

    put("/me/password") {
        val user = call.currentUser()
        val request = call.receive<ChangePasswordRequest>()
        // Ein SSO-Konto ohne lokales Passwort darf sein erstes Passwort direkt setzen.
        val hash = user.passwordHash
        if (hash != null && (request.currentPassword == null || !Passwords.verify(request.currentPassword!!, hash))) {
            throw ApiException(HttpStatusCode.Forbidden, "Aktuelles Passwort falsch")
        }
        checkPassword(request.newPassword)
        services.users.setPassword(user.id, request.newPassword)
        call.respond(HttpStatusCode.NoContent)
    }

    get("/users") {
        call.requireAdmin()
        call.respond(services.users.list().map { it.toDto() })
    }

    post("/users") {
        call.requireAdmin()
        val request = call.receive<CreateUserRequest>()
        if (request.username.isBlank()) throw ApiException(HttpStatusCode.BadRequest, "Benutzername fehlt")
        if (services.users.byUsername(request.username.trim()) != null) {
            throw ApiException(HttpStatusCode.Conflict, "Benutzername ist vergeben")
        }
        checkPassword(request.password)
        val user = services.users.create(request.username.trim(), request.password, request.isAdmin)
        call.respond(HttpStatusCode.Created, user.toDto())
    }

    // Admin-Rolle umschalten bzw. Passwort zurücksetzen (Weboberfläche).
    patch("/users/{id}") {
        val admin = call.requireAdmin()
        val id = call.longParameter("id")
        services.users.byId(id) ?: throw ApiException(HttpStatusCode.NotFound, "Nicht gefunden")
        val request = call.receive<de.axelcypher.asmr.api.UpdateUserRequest>()
        request.isAdmin?.let { makeAdmin ->
            if (!makeAdmin && id == admin.id) throw ApiException(HttpStatusCode.BadRequest, "Die eigene Admin-Rolle bleibt")
            services.users.setAdmin(id, makeAdmin)
        }
        request.password?.let {
            checkPassword(it)
            services.users.setPassword(id, it)
        }
        call.respond(services.users.byId(id)!!.toDto())
    }

    delete("/users/{id}") {
        val admin = call.requireAdmin()
        val id = call.longParameter("id")
        if (id == admin.id) throw ApiException(HttpStatusCode.BadRequest, "Das eigene Konto kann nicht gelöscht werden")
        services.users.delete(id)
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun checkPassword(password: String) {
    if (password.length < MIN_PASSWORD_LENGTH) {
        throw ApiException(HttpStatusCode.BadRequest, "Passwort braucht mindestens $MIN_PASSWORD_LENGTH Zeichen")
    }
}
