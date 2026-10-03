package de.axelcypher.asmr.server.routes

import de.axelcypher.asmr.api.ImportRequest
import de.axelcypher.asmr.server.ApiException
import de.axelcypher.asmr.server.Services
import de.axelcypher.asmr.server.currentUser
import de.axelcypher.asmr.server.longParameter
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.importRoutes(services: Services) = route("/imports") {
    get { call.respond(services.imports.recent()) }

    get("/{id}") {
        call.respond(
            services.imports.get(call.longParameter("id"))
                ?: throw ApiException(HttpStatusCode.NotFound, "Nicht gefunden"),
        )
    }

    post {
        val url = call.receive<ImportRequest>().url.trim()
        // Nur http(s): yt-dlp würde sonst auch lokale Dateipfade annehmen.
        val parsed = runCatching { Url(url) }.getOrNull()
        if (parsed == null || parsed.protocol !in setOf(URLProtocol.HTTP, URLProtocol.HTTPS) || parsed.host.isEmpty()) {
            throw ApiException(HttpStatusCode.BadRequest, "Bitte eine http(s)-URL angeben")
        }
        val job = services.imports.create(url, call.currentUser().id)
        services.worker.enqueue(job.id)
        call.respond(HttpStatusCode.Accepted, job)
    }
}
