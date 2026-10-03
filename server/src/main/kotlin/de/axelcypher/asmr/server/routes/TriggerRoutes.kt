package de.axelcypher.asmr.server.routes

import de.axelcypher.asmr.api.NameRequest
import de.axelcypher.asmr.api.OrderRequest
import de.axelcypher.asmr.api.TriggerRequest
import de.axelcypher.asmr.server.ApiException
import de.axelcypher.asmr.server.Services
import de.axelcypher.asmr.server.longParameter
import de.axelcypher.asmr.server.requireAdmin
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** Verwaltung der Bewertungsmatrix (Gruppen und Regler), nur für Admins. */
fun Route.triggerRoutes(services: Services) = route("/admin/triggers") {
    val catalog = services.triggers

    get {
        call.requireAdmin()
        call.respond(catalog.admin())
    }

    route("/groups") {
        post {
            call.requireAdmin()
            val name = call.receive<NameRequest>().name.cleanName()
            if (catalog.groupNameTaken(name)) conflict("Gruppe \"$name\" gibt es schon")
            call.respond(HttpStatusCode.Created, mapOf("id" to catalog.createGroup(name)))
        }

        put("/order") {
            call.requireAdmin()
            catalog.reorderGroups(call.receive<OrderRequest>().ids)
            call.respond(HttpStatusCode.NoContent)
        }

        patch("/{id}") {
            call.requireAdmin()
            val id = call.longParameter("id")
            if (!catalog.groupExists(id)) notFound()
            val name = call.receive<NameRequest>().name.cleanName()
            if (catalog.groupNameTaken(name, except = id)) conflict("Gruppe \"$name\" gibt es schon")
            catalog.renameGroup(id, name)
            call.respond(HttpStatusCode.NoContent)
        }

        delete("/{id}") {
            call.requireAdmin()
            catalog.deleteGroup(call.longParameter("id"))
            call.respond(HttpStatusCode.NoContent)
        }
    }

    post {
        call.requireAdmin()
        val request = call.receive<TriggerRequest>()
        val groupId = request.groupId ?: throw ApiException(HttpStatusCode.BadRequest, "Gruppe fehlt")
        if (!catalog.groupExists(groupId)) notFound()
        val name = (request.name ?: "").cleanName()
        if (catalog.triggerNameTaken(name)) conflict("Regler \"$name\" gibt es schon")
        call.respond(HttpStatusCode.Created, mapOf("id" to catalog.createTrigger(groupId, name)))
    }

    put("/order") {
        call.requireAdmin()
        catalog.reorderTriggers(call.receive<OrderRequest>().ids)
        call.respond(HttpStatusCode.NoContent)
    }

    // Umbenennen zieht die Werte in allen Tracks und deren Metadaten-Dateien nach.
    patch("/{id}") {
        call.requireAdmin()
        val trigger = catalog.trigger(call.longParameter("id")) ?: notFound()
        val request = call.receive<TriggerRequest>()
        val name = request.name?.cleanName()?.takeIf { it != trigger.name }
        if (name != null && catalog.triggerNameTaken(name, except = trigger.id)) conflict("Regler \"$name\" gibt es schon")
        if (request.groupId != null && !catalog.groupExists(request.groupId!!)) notFound()
        catalog.updateTrigger(trigger.id, name, request.groupId?.takeIf { it != trigger.groupId })
        if (name != null) {
            services.items.renameTag(trigger.name, name).forEach { services.scanner.saveMetadata(it) }
        }
        call.respond(HttpStatusCode.NoContent)
    }

    // ?purge=true entfernt die Werte auch aus allen Tracks; sonst bleiben sie als eigener Trigger.
    delete("/{id}") {
        call.requireAdmin()
        val trigger = catalog.trigger(call.longParameter("id")) ?: notFound()
        catalog.deleteTrigger(trigger.id)
        if (call.request.queryParameters["purge"] == "true") {
            services.items.removeTag(trigger.name).forEach { services.scanner.saveMetadata(it) }
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun String.cleanName(): String =
    trim().takeIf { it.isNotEmpty() && it.length <= 40 } ?: throw ApiException(HttpStatusCode.BadRequest, "Name fehlt oder ist zu lang")

private fun conflict(message: String): Nothing = throw ApiException(HttpStatusCode.Conflict, message)
