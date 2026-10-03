package de.axelcypher.asmr.server.routes

import de.axelcypher.asmr.api.ItemSort
import de.axelcypher.asmr.api.UpdateItemRequest
import de.axelcypher.asmr.server.ApiException
import de.axelcypher.asmr.server.Services
import de.axelcypher.asmr.server.currentUser
import de.axelcypher.asmr.server.db.ItemQuery
import de.axelcypher.asmr.server.longParameter
import de.axelcypher.asmr.server.requireAdmin
import de.axelcypher.asmr.server.viewer
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.launch

private const val MAX_PAGE_SIZE = 200

fun Route.itemRoutes(services: Services) {
    get("/tags") { call.respond(services.items.tags(call.viewer(services))) }

    // Läuft im Hintergrund; das Ergebnis steht im Log, die App lädt danach neu.
    post("/library/scan") {
        call.requireAdmin()
        call.application.launch { services.scanner.scan() }
        call.respond(HttpStatusCode.Accepted)
    }

    route("/items") {
        get {
            val params = call.request.queryParameters
            val query = ItemQuery(
                search = params["q"],
                tags = params.getAll("tag").orEmpty(),
                creator = params["creator"],
                favoritesOnly = params["favorites"] == "true",
                category = params["category"]?.toLongOrNull()?.let { services.categories.members(it) },
                ambientFolders = if (params["ambient"] == "true") services.ambientFolders.list() else null,
                minDuration = params["minDuration"]?.toDoubleOrNull(),
                maxDuration = params["maxDuration"]?.toDoubleOrNull(),
                sort = params["sort"]?.let { runCatching { ItemSort.valueOf(it.uppercase()) }.getOrNull() }
                    ?: ItemSort.TITLE,
                page = params["page"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                pageSize = params["pageSize"]?.toIntOrNull()?.coerceIn(1, MAX_PAGE_SIZE) ?: 60,
            )
            val page = services.items.list(call.viewer(services), query)
            call.respond(page.copy(items = page.items.withAmbient(services.ambientFolders.list())))
        }

        route("/{id}") {
            get {
                val item = services.items.get(call.longParameter("id"), call.viewer(services)) ?: notFound()
                call.respond(listOf(item).withAmbient(services.ambientFolders.list()).single())
            }

            // Metadaten ändern bzw. verschieben; landet in der Metadaten-Datei neben dem Track.
            patch {
                call.requireAdmin()
                val request = call.receive<UpdateItemRequest>()
                val id = call.longParameter("id")
                services.items.record(id) ?: notFound()
                val title = request.title?.trim()?.ifEmpty { null }
                if (title != null || request.creator != null || request.levels != null) {
                    services.items.updateMetadata(id, title, request.creator?.trim(), request.levels)
                    services.scanner.saveMetadata(id)
                }
                request.folder?.let { services.scanner.move(id, it) }
                call.respond(services.items.get(id, call.viewer(services))!!)
            }

            delete {
                call.requireAdmin()
                services.scanner.delete(call.longParameter("id"))
                call.respond(HttpStatusCode.NoContent)
            }

            put("/favorite") {
                val id = call.longParameter("id")
                services.items.get(id, call.viewer(services)) ?: notFound()
                services.items.setFavorite(call.currentUser().id, id, favorite = true)
                call.respond(HttpStatusCode.NoContent)
            }

            delete("/favorite") {
                services.items.setFavorite(call.currentUser().id, call.longParameter("id"), favorite = false)
                call.respond(HttpStatusCode.NoContent)
            }

            // Range-Requests übernimmt das PartialContent-Plugin, damit Media3 spulen kann.
            get("/audio") {
                val files = services.items.files(call.longParameter("id"), call.viewer(services)) ?: notFound()
                call.respondFile(services.storedFile(files.audioPath))
            }

            get("/cover") {
                val files = services.items.files(call.longParameter("id"), call.viewer(services)) ?: notFound()
                call.respondFile(services.storedFile(files.coverPath ?: notFound()))
            }
        }
    }
}

fun notFound(): Nothing = throw ApiException(HttpStatusCode.NotFound, "Nicht gefunden")
