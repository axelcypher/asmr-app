package de.axelcypher.asmr.server.routes

import de.axelcypher.asmr.api.CATEGORY_ICONS
import de.axelcypher.asmr.api.CategoryDetailDto
import de.axelcypher.asmr.api.CategoryDto
import de.axelcypher.asmr.api.CategoryRequest
import de.axelcypher.asmr.api.HomeDto
import de.axelcypher.asmr.api.ItemDto
import de.axelcypher.asmr.api.PlaylistDetailDto
import de.axelcypher.asmr.api.PlaylistDto
import de.axelcypher.asmr.api.PlaylistRequest
import de.axelcypher.asmr.server.ApiException
import de.axelcypher.asmr.server.Services
import de.axelcypher.asmr.server.currentUser
import de.axelcypher.asmr.server.db.ItemQuery
import de.axelcypher.asmr.server.db.PlaylistRecord
import de.axelcypher.asmr.server.db.Viewer
import de.axelcypher.asmr.server.longParameter
import de.axelcypher.asmr.server.requireAdmin
import de.axelcypher.asmr.server.viewer
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

private const val HOME_ROW = 12

/** Startseite, Kategorien, Ambiente und Playlists. */
fun Route.homeRoutes(services: Services) {
    get("/home") {
        val viewer = call.viewer(services)
        val ambientFolders = services.ambientFolders.list()
        call.respond(
            HomeDto(
                creators = services.items.creators(viewer),
                favorites = services.items.list(viewer, ItemQuery(favoritesOnly = true, pageSize = HOME_ROW)).items
                    .withAmbient(ambientFolders),
                playlists = playlists(services, viewer),
                ambient = services.items.list(viewer, ItemQuery(ambientFolders = ambientFolders, pageSize = HOME_ROW)).items
                    .withAmbient(ambientFolders),
                categories = categories(services, viewer),
            ),
        )
    }

    get("/creators") { call.respond(services.items.creators(call.viewer(services))) }

    route("/categories") {
        get { call.respond(categories(services, call.viewer(services))) }

        post {
            call.requireAdmin()
            call.respond(HttpStatusCode.Created, services.categories.create(call.receive<CategoryRequest>().validated()))
        }

        route("/{id}") {
            get {
                call.requireAdmin()
                val id = call.longParameter("id")
                val category = services.categories.get(id) ?: notFound()
                val members = services.categories.members(id)
                call.respond(CategoryDetailDto(category, members.folders, members.itemIds.toList()))
            }

            patch {
                call.requireAdmin()
                val id = call.longParameter("id")
                services.categories.get(id) ?: notFound()
                services.categories.update(id, call.receive<CategoryRequest>().validated())
                call.respond(HttpStatusCode.NoContent)
            }

            delete {
                call.requireAdmin()
                services.categories.delete(call.longParameter("id"))
                call.respond(HttpStatusCode.NoContent)
            }

            put("/items/{itemId}") { setCategoryItem(services, member = true) }
            delete("/items/{itemId}") { setCategoryItem(services, member = false) }
            put("/folders") { setCategoryFolder(services, member = true) }
            delete("/folders") { setCategoryFolder(services, member = false) }
        }
    }

    // Ambiente markieren: einzelne Tracks (landet in der Metadaten-Datei) oder ganze Ordner.
    put("/items/{id}/ambient") { setItemAmbient(services, ambient = true) }
    delete("/items/{id}/ambient") { setItemAmbient(services, ambient = false) }
    put("/folders/ambient") { setFolderAmbient(services, ambient = true) }
    delete("/folders/ambient") { setFolderAmbient(services, ambient = false) }

    route("/playlists") {
        get { call.respond(playlists(services, call.viewer(services))) }

        post {
            val name = call.receive<PlaylistRequest>().name?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw ApiException(HttpStatusCode.BadRequest, "Name fehlt")
            val id = services.playlists.create(call.currentUser().id, name)
            call.respond(HttpStatusCode.Created, playlistDetail(services, call.viewer(services), services.playlists.get(id)!!).playlist)
        }

        route("/{id}") {
            get {
                val record = call.visiblePlaylist(services)
                call.respond(playlistDetail(services, call.viewer(services), record))
            }

            patch {
                val record = call.ownPlaylist(services)
                val request = call.receive<PlaylistRequest>()
                services.playlists.update(record.id, request.name?.trim()?.takeIf { it.isNotEmpty() }, request.shared)
                call.respond(HttpStatusCode.NoContent)
            }

            delete {
                services.playlists.delete(call.ownPlaylist(services).id)
                call.respond(HttpStatusCode.NoContent)
            }

            put("/items/{itemId}") {
                val record = call.ownPlaylist(services)
                val itemId = call.longParameter("itemId")
                services.items.get(itemId, call.viewer(services)) ?: notFound()
                services.playlists.add(record.id, itemId)
                call.respond(HttpStatusCode.NoContent)
            }

            delete("/items/{itemId}") {
                services.playlists.remove(call.ownPlaylist(services).id, call.longParameter("itemId"))
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

/** Ambient-Flag aus markierten Ordnern ergänzen (das Flag am Track selbst kommt aus der DB). */
fun List<ItemDto>.withAmbient(folders: List<String>): List<ItemDto> = map { item ->
    if (item.isAmbient || folders.none { item.folder == it || item.folder.startsWith("$it/") }) item
    else item.copy(isAmbient = true)
}

private suspend fun categories(services: Services, viewer: Viewer): List<CategoryDto> =
    services.categories.list().map { category ->
        val members = services.categories.members(category.id)
        category.copy(itemCount = services.items.list(viewer, ItemQuery(category = members, pageSize = 1)).total)
    }

private suspend fun playlists(services: Services, viewer: Viewer): List<PlaylistDto> =
    services.playlists.visibleTo(viewer.userId).map { playlistDetail(services, viewer, it).playlist }

private suspend fun playlistDetail(services: Services, viewer: Viewer, record: PlaylistRecord): PlaylistDetailDto {
    val items = services.items.byIds(viewer, services.playlists.itemIds(record.id))
        .withAmbient(services.ambientFolders.list())
    val cover = items.firstOrNull { it.hasCover }?.id
    return PlaylistDetailDto(services.playlists.toDto(record, viewer.userId, items.map { it.id }, cover), items)
}

private suspend fun ApplicationCall.visiblePlaylist(services: Services): PlaylistRecord {
    val record = services.playlists.get(longParameter("id")) ?: notFound()
    if (!record.shared && record.ownerId != currentUser().id) notFound()
    return record
}

private suspend fun ApplicationCall.ownPlaylist(services: Services): PlaylistRecord {
    val record = visiblePlaylist(services)
    if (record.ownerId != currentUser().id) throw ApiException(HttpStatusCode.Forbidden, "Nur der Besitzer kann die Playlist ändern")
    return record
}

private suspend fun io.ktor.server.routing.RoutingContext.setCategoryItem(services: Services, member: Boolean) {
    call.requireAdmin()
    val id = call.longParameter("id")
    services.categories.get(id) ?: notFound()
    services.categories.setItem(id, call.longParameter("itemId"), member)
    call.respond(HttpStatusCode.NoContent)
}

private suspend fun io.ktor.server.routing.RoutingContext.setCategoryFolder(services: Services, member: Boolean) {
    call.requireAdmin()
    val id = call.longParameter("id")
    services.categories.get(id) ?: notFound()
    val path = call.request.queryParameters["path"]?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
        ?: throw ApiException(HttpStatusCode.BadRequest, "Ordner fehlt")
    services.categories.setFolder(id, path, member)
    call.respond(HttpStatusCode.NoContent)
}

private suspend fun io.ktor.server.routing.RoutingContext.setItemAmbient(services: Services, ambient: Boolean) {
    call.requireAdmin()
    val id = call.longParameter("id")
    services.items.record(id) ?: notFound()
    services.items.setAmbient(id, ambient)
    services.scanner.saveMetadata(id)
    call.respond(HttpStatusCode.NoContent)
}

private suspend fun io.ktor.server.routing.RoutingContext.setFolderAmbient(services: Services, ambient: Boolean) {
    call.requireAdmin()
    val path = call.request.queryParameters["path"]?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
        ?: throw ApiException(HttpStatusCode.BadRequest, "Ordner fehlt")
    services.ambientFolders.set(path, ambient)
    call.respond(HttpStatusCode.NoContent)
}

private fun CategoryRequest.validated(): CategoryRequest {
    if (name.isBlank()) throw ApiException(HttpStatusCode.BadRequest, "Name fehlt")
    if (icon !in CATEGORY_ICONS) throw ApiException(HttpStatusCode.BadRequest, "Unbekanntes Icon")
    if (!Regex("#[0-9A-Fa-f]{6}").matches(color)) throw ApiException(HttpStatusCode.BadRequest, "Ungültige Farbe")
    return this
}
