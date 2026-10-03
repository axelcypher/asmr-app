package de.axelcypher.asmr.server.imports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.math.abs

fun interface AvatarFetcher {
    /** Lädt das Profilbild des Kanals hinter [url] und legt es als JPEG unter [target] ab. */
    suspend fun fetch(url: String, target: Path)
}

/**
 * Holt Kanal-Bilder per yt-dlp. Ein YouTube-Kanal liefert Avatar und Banner; genommen wird das
 * Bild, das am ehesten quadratisch ist, statt sich auf yt-dlps Dateinamen zu verlassen.
 */
class YtDlpAvatarFetcher(private val executable: String, private val tempRoot: Path) : AvatarFetcher {

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    override suspend fun fetch(url: String, target: Path) = runInterruptible(Dispatchers.IO) {
        val work = Files.createTempDirectory(tempRoot.createDirectories(), "avatar")
        try {
            val process = ProcessBuilder(
                executable, "--skip-download", "--write-all-thumbnails", "--convert-thumbnails", "jpg",
                "--playlist-items", "0", "--no-progress",
                "--output", work.resolve("thumb.%(ext)s").toString(), "--", url,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                throw DownloadException("yt-dlp hat zu lange gebraucht")
            }
            val square = work.listDirectoryEntries()
                .filter { it.isRegularFile() && it.extension.lowercase() == "jpg" }
                .mapNotNull { file -> ImageIO.read(file.toFile())?.let { file to abs(it.width.toDouble() / it.height - 1) } }
                .minByOrNull { it.second }
                ?.first
                ?: throw DownloadException(
                    output.lines().lastOrNull { it.startsWith("ERROR") } ?: "Kein Profilbild gefunden",
                )
            target.parent.createDirectories()
            Files.copy(square, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            Unit
        } finally {
            work.deleteRecursively()
        }
    }
}
