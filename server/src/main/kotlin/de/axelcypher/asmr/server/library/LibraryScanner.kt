package de.axelcypher.asmr.server.library

import de.axelcypher.asmr.server.auth.sha256Hex
import de.axelcypher.asmr.server.db.ItemStore
import de.axelcypher.asmr.server.db.NewItem
import de.axelcypher.asmr.server.imports.TriggerTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.relativeTo
import kotlin.time.Duration

data class ScanResult(val found: Int, val added: Int, val removed: Int)

/**
 * Übernimmt Dateien, die direkt auf dem NAS liegen (nicht über den Import kamen), und entfernt
 * Einträge, deren Datei verschwunden ist. Teilt sich [libraryLock] mit dem Import, damit keine
 * Datei doppelt eingetragen wird.
 */
class LibraryScanner(
    private val items: ItemStore,
    private val mediaDir: Path,
    private val coverDir: Path,
    private val probe: MediaProbe,
    private val libraryLock: Mutex,
) {
    private val log = LoggerFactory.getLogger(LibraryScanner::class.java)
    private val running = Mutex()

    fun start(scope: CoroutineScope, interval: Duration): Job = scope.launch {
        while (isActive) {
            runCatching { scan() }.onFailure { log.warn("Scan fehlgeschlagen", it) }
            delay(interval)
        }
    }

    /** Läuft höchstens einmal gleichzeitig; ein zweiter Aufruf wartet und scannt dann erneut. */
    suspend fun scan(): ScanResult = running.withLock {
        val files = withContext(Dispatchers.IO) { mediaFiles() }
        val known = items.audioPaths()
        var added = 0
        for (file in files) {
            val relative = relativePath(file)
            if (relative in known) continue
            libraryLock.withLock {
                // Erneut prüfen: ein Import könnte die Datei gerade eingetragen haben.
                if (items.idByAudioPath(relative) == null) {
                    items.add(describe(file, relative))
                    added++
                }
            }
        }
        // Ist das NAS nicht gemountet, wirkt der Ordner leer: dann nichts löschen.
        val removed = if (files.isEmpty()) 0 else removeMissing(files.map(::relativePath).toSet())
        log.info("Scan: {} Dateien, {} neu, {} entfernt", files.size, added, removed)
        ScanResult(files.size, added, removed)
    }

    private suspend fun removeMissing(present: Set<String>): Int {
        var removed = 0
        for ((id, path) in items.audioPathsById()) {
            if (path !in present && !mediaDir.resolve(path).exists()) {
                items.delete(id)
                removed++
            }
        }
        return removed
    }

    private fun mediaFiles(): List<Path> {
        if (!mediaDir.isDirectory()) return emptyList()
        return Files.walk(mediaDir).use { stream ->
            stream.filter { it.isRegularFile() && it.extension.lowercase() in MEDIA_EXTENSIONS }
                .filter { path -> path.relativeTo(mediaDir).none { it.toString().startsWith(".") } }
                .toList()
        }
    }

    private suspend fun describe(file: Path, relative: String): NewItem {
        val info = withContext(Dispatchers.IO) { runCatching { probe.probe(file) }.getOrNull() }
        val title = info?.title ?: cleanTitle(file.nameWithoutExtension)
        val segments = relative.split('/')
        val creator = info?.artist ?: segments.takeIf { it.size >= 2 }?.first()
        return NewItem(
            title = title,
            creator = creator,
            durationSeconds = info?.durationSeconds,
            audioPath = relative,
            coverPath = withContext(Dispatchers.IO) { findCover(file) },
            description = info?.comment,
            sourceUrl = null,
            sourceKey = null,
            tags = TriggerTags.detect(title, info?.comment, emptyList()),
        )
    }

    /** Bild neben der Datei, Ordner-Cover oder (bei Videos) ein erzeugtes Vorschaubild. */
    private fun findCover(file: Path): String? {
        val dir = file.parent
        val candidates = IMAGE_EXTENSIONS.map { dir.resolve("${file.nameWithoutExtension}.$it") } +
            FOLDER_COVERS.map(dir::resolve)
        candidates.firstOrNull { it.isRegularFile() }?.let { return relativePath(it) }
        if (file.extension.lowercase() !in VIDEO_EXTENSIONS) return null
        val target = coverDir.resolve("${sha256Hex(relativePath(file)).take(24)}.jpg")
        if (!target.exists() && !probe.thumbnail(file, target)) return null
        return DATA_COVER_PREFIX + target.fileName
    }

    private fun relativePath(file: Path) = file.relativeTo(mediaDir).toString().replace('\\', '/')

    companion object {
        /** Cover, die im Datenordner statt auf dem NAS liegen (erzeugte Video-Vorschauen). */
        const val DATA_COVER_PREFIX = "@covers/"

        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "opus", "ogg", "oga", "flac", "wav", "mka")
        val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "m4v")
        val MEDIA_EXTENSIONS = AUDIO_EXTENSIONS + VIDEO_EXTENSIONS
        private val IMAGE_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp")
        private val FOLDER_COVERS = listOf("cover.jpg", "cover.png", "folder.jpg", "folder.png")
        private val TRAILING_ID = Regex("\\s*\\[[A-Za-z0-9_-]{6,}]$")

        /** "Rain Tapping [dQw4w9WgXcQ]" -> "Rain Tapping"; Unterstriche werden Leerzeichen. */
        fun cleanTitle(fileName: String): String =
            fileName.replace(TRAILING_ID, "").replace('_', ' ').trim().ifEmpty { fileName }
    }
}
