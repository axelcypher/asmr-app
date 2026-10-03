package de.axelcypher.asmr.server.imports

import de.axelcypher.asmr.api.ApiJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

data class SourceInfo(
    val id: String,
    val extractor: String,
    val title: String,
    val uploader: String?,
    val durationSeconds: Double?,
    val description: String?,
    val tags: List<String>,
    val webpageUrl: String,
)

data class Download(val audio: Path, val cover: Path?, val info: SourceInfo)

fun interface Downloader {
    /** Lädt Audio, Cover und Metadaten nach [targetDir]. */
    suspend fun download(url: String, targetDir: Path): Download
}

class DownloadException(message: String) : Exception(message)

/** Lädt mit yt-dlp nur die Audiospur (ohne Neukodierung) samt Thumbnail und Info-JSON. */
class YtDlpDownloader(private val executable: String) : Downloader {

    override suspend fun download(url: String, targetDir: Path): Download = runInterruptible(Dispatchers.IO) {
        val process = ProcessBuilder(
            executable,
            "--no-playlist",
            "--no-progress",
            "--format", "bestaudio/best",
            "--extract-audio",
            "--write-thumbnail",
            "--convert-thumbnails", "jpg",
            "--write-info-json",
            "--output", targetDir.resolve("media.%(ext)s").toString(),
            "--",
            url,
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw DownloadException("yt-dlp hat nach $TIMEOUT_MINUTES Minuten abgebrochen")
        }
        if (process.exitValue() != 0) {
            val reason = output.lines().lastOrNull { it.startsWith("ERROR") } ?: output.takeLast(500)
            throw DownloadException(reason.trim())
        }
        collect(targetDir)
    }

    private fun collect(dir: Path): Download {
        val files = dir.listDirectoryEntries()
        val infoFile = files.firstOrNull { it.name.endsWith(".info.json") }
            ?: throw DownloadException("yt-dlp hat keine Metadaten geschrieben")
        val cover = files.firstOrNull { it.name.endsWith(".jpg") }
        val audio = files.firstOrNull { it != infoFile && it != cover && !it.name.endsWith(".part") }
            ?: throw DownloadException("yt-dlp hat keine Audiodatei geschrieben")
        val info = ApiJson.decodeFromString<YtDlpInfo>(infoFile.readText())
        return Download(audio, cover, info.toSourceInfo())
    }

    @Serializable
    private data class YtDlpInfo(
        val id: String,
        @SerialName("extractor_key") val extractor: String = "generic",
        val title: String,
        val uploader: String? = null,
        val channel: String? = null,
        val duration: Double? = null,
        val description: String? = null,
        val tags: List<String> = emptyList(),
        @SerialName("webpage_url") val webpageUrl: String,
    ) {
        fun toSourceInfo() = SourceInfo(
            id = id,
            extractor = extractor.lowercase(),
            title = title,
            uploader = channel ?: uploader,
            durationSeconds = duration,
            description = description,
            tags = tags,
            webpageUrl = webpageUrl,
        )
    }

    private companion object {
        const val TIMEOUT_MINUTES = 60L
    }
}
