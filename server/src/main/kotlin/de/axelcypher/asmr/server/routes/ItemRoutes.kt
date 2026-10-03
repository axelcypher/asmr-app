package de.axelcypher.asmr.server.routes

import de.axelcypher.asmr.api.ItemSort
import de.axelcypher.asmr.api.UpdateItemRequest
import de.axelcypher.asmr.server.ApiException
import de.axelcypher.asmr.server.Services
import de.axelcypher.asmr.server.currentUser
import de.axelcypher.asmr.server.db.ItemQuery
import de.axelcypher.asmr.server.longParameter
import de.axelcypher.asmr.server.requireAdmin
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

private const val MAX_PAGE_SIZE = 200

fun Route.itemRoutes(services: Services) {
    get("/tags") { call.respond(services.items.tags()) }

    route("/items") {
        get {
            val params = call.request.queryParameters
            val query = ItemQuery(
                search = params["q"],
                tags = params.getAll("tag").orEmpty(),
                creator = params["creator"],
                favoritesOnly = params["favorites"] == "true",
                sort = params["sort"]?.let { runCatching { ItemSort.valueOf(it.uppercase()) }.getOrNull() }
                    ?: ItemSort.TITLE,
                page = params["page"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                pageSize = params["pageSize"]?.toIntOrNull()?.coerceIn(1, MAX_PAGE_SIZE) ?: 60,
            )
            call.respond(services.items.list(call.currentUser().id, query))
        }

        route("/{id}") {
            get {
                call.respond(services.items.get(call.longParameter("id"), call.currentUser().id) ?: notFound())
            }

            patch {
                val request = call.receive<UpdateItemRequest>()
                val id = call.longParameter("id")
                if (!services.items.update(id, request.title?.trim()?.ifEmpty { null }, request.creator, request.tags)) {
                    notFound()
                }
                call.respond(services.items.get(id, call.currentUser().id)!!)
            }

            delete {
                call.requireAdmin()
                val files = services.items.delete(call.longParameter("id")) ?: notFound()
                listOfNotNull(files.audioPath, files.coverPath).forEach {
                    Files.deleteIfExists(call.mediaFile(services, it))
                }
                call.respond(HttpStatusCode.NoContent)
            }

            put("/favorite") {
                services.items.setFavorite(call.currentUser().id, call.longParameter("id"), favorite = true)
                call.respond(HttpStatusCode.NoContent)
            }

            delete("/favorite") {
                services.items.setFavorite(call.currentUser().id, call.longParameter("id"), favorite = false)
                call.respond(HttpStatusCode.NoContent)
            }

            // Range-Requests übernimmt das PartialContent-Plugin, damit Media3 spulen kann.
            get("/audio") {
                val files = services.items.files(call.longParameter("id")) ?: notFound()
                call.respondFile(call.mediaFile(services, files.audioPath).existingOrNotFound())
            }

            get("/cover") {
                val cover = services.items.files(call.longParameter("id"))?.coverPath ?: notFound()
                call.respondFile(call.mediaFile(services, cover).existingOrNotFound())
            }
        }
    }
}

private fun notFound(): Nothing = throw ApiException(HttpStatusCode.NotFound, "Nicht gefunden")

/** Löst einen in der DB gespeicherten relativen Pfad auf, ohne den Medienordner verlassen zu können. */
private fun ApplicationCall.mediaFile(services: Services, relative: String): Path {
    val root = services.mediaDir.toAbsolutePath().normalize()
    val file = root.resolve(relative).normalize()
    if (!file.startsWith(root)) throw ApiException(HttpStatusCode.NotFound, "Nicht gefunden")
    return file
}

private fun Path.existingOrNotFound() = toFile().also { if (!isRegularFile()) notFound() }
