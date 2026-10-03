package de.axelcypher.asmr.server.library

import de.axelcypher.asmr.api.ApiJson
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Metadaten neben dem Track (`<name>.asmr.json`). Hat Vorrang vor Datei-Tags und Dateinamen, damit
 * Umbenennen in der App die Datei selbst nicht anfasst und die Daten auch ohne Datenbank erhalten bleiben.
 */
@Serializable
data class SidecarMetadata(
    val title: String? = null,
    val creator: String? = null,
    /** Bewertungsmatrix Trigger -> Stärke 1..10. */
    val levels: Map<String, Int>? = null,
    val sourceUrl: String? = null,
    /** Als Ambiente markiert. */
    val ambient: Boolean? = null,
)

object Sidecars {
    private const val SUFFIX = ".asmr.json"

    fun pathFor(audio: Path): Path = audio.resolveSibling(audio.nameWithoutExtension + SUFFIX)

    fun read(audio: Path): SidecarMetadata? {
        val file = pathFor(audio)
        if (!file.isRegularFile()) return null
        return runCatching { ApiJson.decodeFromString<SidecarMetadata>(file.readText()) }.getOrNull()
    }

    /** Schreibt über eine Temp-Datei, damit ein Abbruch keine halbe Datei auf dem NAS hinterlässt. */
    fun write(audio: Path, metadata: SidecarMetadata) {
        val target = pathFor(audio)
        val temp = target.resolveSibling(target.fileName.toString() + ".tmp")
        temp.writeText(PRETTY.encodeToString(SidecarMetadata.serializer(), metadata))
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    }

    private val PRETTY = kotlinx.serialization.json.Json(ApiJson) {
        prettyPrint = true
        explicitNulls = false
    }
}
