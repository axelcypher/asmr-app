package de.axelcypher.asmr.server

import de.axelcypher.asmr.api.ApiJson
import de.axelcypher.asmr.api.ErrorDto
import de.axelcypher.asmr.server.auth.SsoService
import de.axelcypher.asmr.server.db.ImportStore
import de.axelcypher.asmr.server.db.ItemStore
import de.axelcypher.asmr.server.db.UserRecord
import de.axelcypher.asmr.server.db.UserStore
import de.axelcypher.asmr.server.imports.ImportWorker
import de.axelcypher.asmr.server.routes.authRoutes
import de.axelcypher.asmr.server.routes.importRoutes
import de.axelcypher.asmr.server.routes.itemRoutes
import de.axelcypher.asmr.server.routes.userRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.nio.file.Path

/** Alles, was die Routen brauchen; in Tests mit Fakes befüllt. */
class Services(
    val users: UserStore,
    val items: ItemStore,
    val imports: ImportStore,
    val worker: ImportWorker,
    val sso: SsoService?,
    val mediaDir: Path,
)

data class UserPrincipal(val user: UserRecord, val token: String)

/** Fehler, die als `{"error": …}` mit passendem Status beim Client ankommen. */
class ApiException(val status: HttpStatusCode, message: String) : Exception(message)

fun ApplicationCall.currentUser(): UserRecord = principal<UserPrincipal>()!!.user

fun ApplicationCall.requireAdmin(): UserRecord =
    currentUser().also { if (!it.isAdmin) throw ApiException(HttpStatusCode.Forbidden, "Nur für Admins") }

fun ApplicationCall.longParameter(name: String): Long =
    parameters[name]?.toLongOrNull() ?: throw ApiException(HttpStatusCode.BadRequest, "Ungültige $name")

fun Application.asmrModule(services: Services) {
    val log = LoggerFactory.getLogger("asmr")

    install(ContentNegotiation) { json(ApiJson) }
    install(PartialContent)
    install(AutoHeadResponse)
    install(CallLogging) {
        level = Level.INFO
        filter { it.request.path() != "/health" }
    }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ErrorDto(e.message ?: e.status.description)) }
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorDto(e.message ?: "Ungültige Anfrage"))
        }
        exception<Throwable> { call, e ->
            log.error("Unbehandelter Fehler bei {}", call.request.path(), e)
            call.respond(HttpStatusCode.InternalServerError, ErrorDto("Interner Fehler"))
        }
    }
    install(Authentication) {
        bearer("api") {
            authenticate { credential ->
                services.users.userForToken(credential.token)?.let { UserPrincipal(it, credential.token) }
            }
        }
    }

    routing {
        get("/health") { call.respondText("ok") }
        route("/api") {
            authRoutes(services)
            authenticate("api") {
                userRoutes(services)
                itemRoutes(services)
                importRoutes(services)
            }
        }
    }
}
