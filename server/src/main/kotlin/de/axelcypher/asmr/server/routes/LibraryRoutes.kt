package de.axelcypher.asmr.server.routes

import de.axelcypher.asmr.api.AccessOverviewDto
import de.axelcypher.asmr.api.CreateFolderRequest
import de.axelcypher.asmr.api.FolderAccessDto
import de.axelcypher.asmr.api.FolderDto
import de.axelcypher.asmr.api.FolderListing
import de.axelcypher.asmr.api.UpdateCreatorRequest
import de.axelcypher.asmr.server.ApiException
import de.axelcypher.asmr.server.Services
import de.axelcypher.asmr.server.auth.sha256Hex
import de.axelcypher.asmr.server.currentUser
import de.axelcypher.asmr.server.imports.DownloadException
import de.axelcypher.asmr.server.library.LibraryException
import de.axelcypher.asmr.server.requireAdmin
import de.axelcypher.asmr.server.viewer
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes

fun Route.libraryRoutes(services: Services) {
    route("/folders") {
        // Ein Ordner: direkte Unterordner (mit rekursiver Anzahl) und die Tracks darin.
        get {
            val user = call.currentUser()
            val viewer = call.viewer(services)
            val path = cleanFolder(call.request.queryParameters["path"].orEmpty())
            if (viewer.hiddenFolders.any { path == it || path.startsWith("$it/") }) notFound()

            // Auf dem NAS umbenannt oder verschoben? Dann vor der Antwort neu einlesen.
            if (services.scanner.isStale(path)) services.scanner.scan()
            val all = services.items.inFolder(viewer, path)
            val counts = all.filter { it.folder != path }.groupingBy { childName(path, it.folder) }.eachCount()
            // Admins sehen auch leere Ordner, damit sie Tracks hineinschieben können.
            val empty = if (user.isAdmin) {
                services.scanner.folders().filter { parentOf(it) == path }.map { it.substringAfterLast('/') }
            } else {
                emptyList()
            }
            val restricted = services.folderAccess.rules().map { it.path }.toSet()
            val folders = (counts.keys + empty).distinct().sortedBy(String::lowercase).map { name ->
                val childPath = join(path, name)
                FolderDto(
                    path = childPath,
                    name = name,
                    itemCount = counts[name] ?: 0,
                    hasCover = folderCover(services, childPath) != null,
                    restricted = user.isAdmin && childPath in restricted,
                )
            }
            call.respond(FolderListing(path, folders, all.filter { it.folder == path }))
        }

        get("/cover") {
            val viewer = call.viewer(services)
            val path = cleanFolder(call.request.queryParameters["path"].orEmpty())
            if (viewer.hiddenFolders.any { path == it || path.startsWith("$it/") }) notFound()
            call.respondFile(services.storedFile(folderCover(services, path) ?: notFound()))
        }

        // Alle Ordner, als Ziel beim Verschieben.
        get("/all") {
            call.requireAdmin()
            call.respond(services.scanner.folders().sortedBy(String::lowercase))
        }

        post {
            call.requireAdmin()
            library { services.scanner.createFolder(call.receive<CreateFolderRequest>().path) }
            call.respond(HttpStatusCode.Created)
        }
    }

    route("/admin/access") {
        get {
            call.requireAdmin()
            call.respond(
                AccessOverviewDto(
                    rules = services.folderAccess.rules(),
                    groups = services.users.knownGroups(),
                    users = services.users.list().map { it.toDto() },
                    ssoEnabled = services.sso != null,
                ),
            )
        }

        put {
            call.requireAdmin()
            val rule = call.receive<FolderAccessDto>()
            val path = cleanFolder(rule.path)
            if (path.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, "Die oberste Ebene lässt sich nicht einschränken")
            services.folderAccess.set(rule.copy(path = path))
            call.respond(HttpStatusCode.NoContent)
        }

        delete {
            call.requireAdmin()
            services.folderAccess.remove(cleanFolder(call.request.queryParameters["path"].orEmpty()))
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/creators/{name}") {
        get { call.respond(services.creators.get(call.creatorName())) }

        put {
            call.requireAdmin()
            val name = call.creatorName()
            services.creators.setLinks(name, call.receive<UpdateCreatorRequest>().links)
            call.respond(services.creators.get(name))
        }

        get("/avatar") {
            val avatar = services.creators.avatarPath(call.creatorName()) ?: notFound()
            call.respondFile(services.storedFile(avatar))
        }

        // Bild direkt im Body (z.B. aus der Galerie der App).
        put("/avatar") {
            call.requireAdmin()
            val name = call.creatorName()
            val bytes = call.receive<ByteArray>()
            services.creators.setAvatar(name, saveAvatar(services, name, bytes))
            call.respond(services.creators.get(name))
        }

        // Profilbild vom YouTube-Kanal holen (erster YouTube-Link des Creators).
        post("/avatar/fetch") {
            call.requireAdmin()
            val name = call.creatorName()
            val url = services.creators.get(name).links.firstOrNull { it.label == "YouTube" }?.url
                ?: throw ApiException(HttpStatusCode.BadRequest, "Kein YouTube-Link hinterlegt")
            val temp = services.avatarDir.createDirectories().resolve("fetch-${sha256Hex(name).take(12)}.jpg")
            try {
                services.avatarFetcher.fetch(url, temp)
                services.creators.setAvatar(name, saveAvatar(services, name, withContext(Dispatchers.IO) { temp.readBytes() }))
            } catch (e: DownloadException) {
                throw ApiException(HttpStatusCode.BadGateway, e.message ?: "Profilbild nicht gefunden")
            } finally {
                withContext(Dispatchers.IO) { Files.deleteIfExists(temp) }
            }
            call.respond(services.creators.get(name))
        }
    }
}

private fun ApplicationCall.creatorName() =
    parameters["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: throw ApiException(HttpStatusCode.BadRequest, "Name fehlt")

private suspend fun library(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: LibraryException) {
        throw ApiException(HttpStatusCode.BadRequest, e.message ?: "Fehlgeschlagen")
    }
}

/** `cover.*` im Ordner, sonst das Profilbild des gleichnamigen Creators (typisch: ein Ordner pro Creator). */
private suspend fun folderCover(services: Services, path: String): String? =
    services.scanner.folderCover(path)
        ?: path.takeIf { it.isNotEmpty() }?.let { services.creators.avatarPath(it.substringAfterLast('/')) }

/** Prüft und verkleinert das Bild, speichert es als JPEG und liefert den gespeicherten Pfad. */
private suspend fun saveAvatar(services: Services, name: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
    val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
        ?: throw ApiException(HttpStatusCode.BadRequest, "Kein lesbares Bild")
    val scale = minOf(1.0, AVATAR_SIZE.toDouble() / maxOf(image.width, image.height))
    val width = (image.width * scale).toInt().coerceAtLeast(1)
    val height = (image.height * scale).toInt().coerceAtLeast(1)
    // JPEG kennt keine Transparenz: auf RGB zeichnen.
    val rgb = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    rgb.createGraphics().apply {
        setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        drawImage(image, 0, 0, width, height, java.awt.Color.BLACK, null)
        dispose()
    }
    val fileName = "${sha256Hex(name.lowercase()).take(24)}.jpg"
    val target: Path = services.avatarDir.createDirectories().resolve(fileName)
    ImageIO.write(rgb, "jpg", target.toFile())
    Services.AVATAR_PREFIX + fileName
}

private const val AVATAR_SIZE = 512

private fun cleanFolder(path: String) = path.trim().trim('/').replace('\\', '/')

private fun parentOf(path: String) = path.substringBeforeLast('/', missingDelimiterValue = "")

private fun join(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"

/** Erster Pfadteil von [folder] unterhalb von [parent]. */
private fun childName(parent: String, folder: String): String =
    (if (parent.isEmpty()) folder else folder.removePrefix("$parent/")).substringBefore('/')
