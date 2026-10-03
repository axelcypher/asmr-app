package de.axelcypher.asmr.server.library

import de.axelcypher.asmr.api.ApiJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

data class MediaInfo(
    val durationSeconds: Double?,
    val title: String?,
    val artist: String?,
    val comment: String?,
)

interface MediaProbe {
    fun probe(file: Path): MediaInfo?

    /** Schreibt ein Vorschaubild nach [target]; false, wenn das nicht ging. */
    fun thumbnail(file: Path, target: Path): Boolean
}

/** ffprobe/ffmpeg aus dem Container-Image. */
class FfmpegProbe : MediaProbe {

    override fun probe(file: Path): MediaInfo? {
        val output = run("ffprobe", "-v", "quiet", "-print_format", "json", "-show_format", file.toString())
            ?: return null
        val format = ApiJson.parseToJsonElement(output).let { it as? JsonObject }?.get("format") as? JsonObject
            ?: return null
        val tags = (format["tags"] as? JsonObject).orEmpty()
            .mapKeys { it.key.lowercase() }
            .mapValues { (it.value as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } }
        return MediaInfo(
            durationSeconds = (format["duration"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull(),
            title = tags["title"],
            artist = tags["artist"] ?: tags["album_artist"],
            comment = tags["comment"] ?: tags["description"],
        )
    }

    override fun thumbnail(file: Path, target: Path): Boolean {
        target.parent.createDirectories()
        run(
            "ffmpeg", "-v", "quiet", "-y", "-ss", "30", "-i", file.toString(),
            "-frames:v", "1", "-vf", "scale=480:-2", target.toString(),
        )
        if (target.exists()) return true
        // Kürzer als 30 s: erstes Bild nehmen.
        run("ffmpeg", "-v", "quiet", "-y", "-i", file.toString(), "-frames:v", "1", "-vf", "scale=480:-2", target.toString())
        return target.exists()
    }

    private fun run(vararg command: String): String? {
        val process = runCatching { ProcessBuilder(*command).redirectErrorStream(true).start() }.getOrNull()
            ?: return null
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            return null
        }
        return output.takeIf { process.exitValue() == 0 }
    }
}
