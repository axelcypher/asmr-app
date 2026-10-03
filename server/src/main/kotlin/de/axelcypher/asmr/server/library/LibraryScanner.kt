package de.axelcypher.asmr.server.library

import de.axelcypher.asmr.server.auth.sha256Hex
import de.axelcypher.asmr.server.db.ItemRecord
import de.axelcypher.asmr.server.db.ItemStore
import de.axelcypher.asmr.server.db.NewItem
import de.axelcypher.asmr.server.db.folderOf
import de.axelcypher.asmr.server.db.normalizeLevels
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
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.relativeTo
import kotlin.time.Duration

data class ScanResult(val found: Int, val added: Int, val removed: Int, val updated: Int = 0)

class LibraryException(message: String) : Exception(message)

/**
 * Alles, was Dateien im Medienordner anfasst: Scan, Abgleich von Cover und Metadaten-Datei,
 * Verschieben, Ordner anlegen. Teilt sich [libraryLock] mit dem Import, damit keine Datei doppelt
 * eingetragen oder während eines Imports verschoben wird.
 *
 * Cover-Reihenfolge: Bild mit dem Namen des Tracks, sonst `cover.*`/`folder.*` im Ordner, sonst (bei
 * Videos) ein erzeugtes Vorschaubild; danach greift beim Ausliefern noch das Creator-Profilbild.
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
        val updated = if (files.isEmpty()) 0 else syncExisting()
        log.info("Scan: {} Dateien, {} neu, {} entfernt, {} aktualisiert", files.size, added, removed, updated)
        ScanResult(files.size, added, removed, updated)
    }

    /** Schreibt die aktuellen Metadaten eines Items in seine Metadaten-Datei. */
    suspend fun saveMetadata(id: Long) = libraryLock.withLock {
        val record = items.record(id) ?: throw LibraryException("Nicht gefunden")
        withContext(Dispatchers.IO) { Sidecars.write(mediaDir.resolve(record.audioPath), record.toSidecar()) }
    }

    /**
     * Verschiebt Track, passendes Cover und Metadaten-Datei nach [folder] (relativ zum Medienordner)
     * und bestimmt das Cover am neuen Ort neu.
     */
    suspend fun move(id: Long, folder: String) = libraryLock.withLock {
        val record = items.record(id) ?: throw LibraryException("Nicht gefunden")
        val targetDir = resolveFolder(folder)
        val source = mediaDir.resolve(record.audioPath)
        val targetFolder = if (targetDir == mediaDir.normalize()) "" else relativePath(targetDir)
        if (folderOf(record.audioPath) == targetFolder) return@withLock
        withContext(Dispatchers.IO) {
            targetDir.createDirectories()
            val target = targetDir.resolve(source.fileName)
            if (target.exists()) throw LibraryException("Im Zielordner gibt es schon ${source.fileName}")
            val ownCover = IMAGE_EXTENSIONS.map { source.resolveSibling("${source.nameWithoutExtension}.$it") }
                .firstOrNull { it.isRegularFile() }
            val sidecar = Sidecars.pathFor(source).takeIf { it.isRegularFile() }
            Files.move(source, target)
            ownCover?.let { Files.move(it, targetDir.resolve(it.fileName), StandardCopyOption.REPLACE_EXISTING) }
            sidecar?.let { Files.move(it, Sidecars.pathFor(target), StandardCopyOption.REPLACE_EXISTING) }
            items.setPaths(id, relativePath(target), findCover(target))
        }
    }

    /**
     * Löscht einen Track mit seinen eigenen Dateien (Bild gleichen Namens, Metadaten-Datei, erzeugtes
     * Vorschaubild). Ordner-Cover bleiben, sie gehören allen Tracks im Ordner.
     */
    suspend fun delete(id: Long) = libraryLock.withLock {
        val record = items.record(id) ?: throw LibraryException("Nicht gefunden")
        withContext(Dispatchers.IO) {
            val audio = mediaDir.resolve(record.audioPath)
            // Achtung: Path ist selbst Iterable<Path>, "liste + pfad" würde die Namensteile anhängen.
            val generated = record.coverPath?.takeIf { it.startsWith(DATA_COVER_PREFIX) }
                ?.let { coverDir.resolve(it.removePrefix(DATA_COVER_PREFIX)) }
            val files = buildList {
                IMAGE_EXTENSIONS.forEach { add(audio.resolveSibling("${audio.nameWithoutExtension}.$it")) }
                add(Sidecars.pathFor(audio))
                generated?.let(::add)
                add(audio)
            }
            files.forEach { Files.deleteIfExists(it) }
        }
        items.delete(id)
    }

    /** Legt einen (auch verschachtelten) Ordner an. */
    suspend fun createFolder(folder: String) {
        val dir = resolveFolder(folder)
        if (dir == mediaDir) throw LibraryException("Ordnername fehlt")
        withContext(Dispatchers.IO) { dir.createDirectories() }
    }

    /** Alle Unterordner (rekursiv, relativ), auch leere; versteckte Ordner ausgenommen. */
    suspend fun folders(): List<String> = withContext(Dispatchers.IO) {
        if (!mediaDir.isDirectory()) return@withContext emptyList()
        Files.walk(mediaDir).use { stream ->
            stream.filter { it != mediaDir && it.isDirectory() }
                .map(::relativePath)
                .filter { path -> path.split('/').none { it.startsWith(".") } }
                .toList()
        }
    }

    /** `cover.*`/`folder.*` eines Ordners, relativ zum Medienordner. */
    fun folderCover(folder: String): String? {
        val dir = runCatching { resolveFolder(folder) }.getOrNull() ?: return null
        return folderCoverIn(dir)?.let(::relativePath)
    }

    private fun resolveFolder(folder: String): Path {
        val clean = folder.trim().trim('/').replace('\\', '/')
        if (clean.split('/').any { it == ".." || it.startsWith(".") }) throw LibraryException("Ungültiger Ordner")
        val dir = mediaDir.resolve(clean).normalize()
        if (!dir.startsWith(mediaDir.normalize())) throw LibraryException("Ungültiger Ordner")
        return dir
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

    /** Übernimmt geänderte Metadaten-Dateien und neu hinzugekommene oder entfernte Cover. */
    private suspend fun syncExisting(): Int {
        var updated = 0
        for (record in items.records()) {
            val file = mediaDir.resolve(record.audioPath)
            val (cover, sidecar) = withContext(Dispatchers.IO) { findCover(file) to Sidecars.read(file) }
            var changed = false
            if (cover != record.coverPath) {
                items.setCover(record.id, cover)
                changed = true
            }
            if (sidecar != null) {
                val title = sidecar.title?.takeIf { it != record.title }
                val creator = sidecar.creator?.takeIf { it != record.creator }
                val levels = sidecar.levels?.let(::normalizeLevels)?.takeIf { it != record.levels }
                if (title != null || creator != null || levels != null) {
                    items.updateMetadata(record.id, title, creator, levels)
                    changed = true
                }
            }
            if (changed) updated++
        }
        return updated
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
        val (info, sidecar) = withContext(Dispatchers.IO) {
            runCatching { probe.probe(file) }.getOrNull() to Sidecars.read(file)
        }
        val title = sidecar?.title ?: info?.title ?: cleanTitle(file.nameWithoutExtension)
        val segments = relative.split('/')
        val creator = sidecar?.creator ?: info?.artist ?: segments.takeIf { it.size >= 2 }?.first()
        return NewItem(
            title = title,
            creator = creator,
            durationSeconds = info?.durationSeconds,
            audioPath = relative,
            coverPath = withContext(Dispatchers.IO) { findCover(file) },
            description = info?.comment,
            sourceUrl = sidecar?.sourceUrl,
            sourceKey = null,
            levels = sidecar?.levels?.let(::normalizeLevels) ?: TriggerTags.detect(title, info?.comment, emptyList()),
        )
    }

    private fun findCover(file: Path): String? {
        IMAGE_EXTENSIONS.map { file.resolveSibling("${file.nameWithoutExtension}.$it") }
            .firstOrNull { it.isRegularFile() }
            ?.let { return relativePath(it) }
        folderCoverIn(file.parent)?.let { return relativePath(it) }
        if (file.extension.lowercase() !in VIDEO_EXTENSIONS) return null
        val target = coverDir.resolve("${sha256Hex(relativePath(file)).take(24)}.jpg")
        if (!target.exists() && !probe.thumbnail(file, target)) return null
        return DATA_COVER_PREFIX + target.fileName
    }

    private fun folderCoverIn(dir: Path): Path? {
        if (!dir.isDirectory()) return null
        return dir.listDirectoryEntries().filter { it.isRegularFile() }
            .filter { it.nameWithoutExtension.lowercase() in FOLDER_COVER_NAMES && it.extension.lowercase() in IMAGE_EXTENSIONS }
            .minByOrNull { FOLDER_COVER_NAMES.indexOf(it.nameWithoutExtension.lowercase()) }
    }

    private fun relativePath(file: Path) = file.relativeTo(mediaDir).toString().replace('\\', '/')

    private fun ItemRecord.toSidecar() = SidecarMetadata(title, creator, levels.takeIf { it.isNotEmpty() }, sourceUrl)

    companion object {
        /** Cover, die im Datenordner statt auf dem NAS liegen (erzeugte Video-Vorschauen). */
        const val DATA_COVER_PREFIX = "@covers/"

        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "opus", "ogg", "oga", "flac", "wav", "mka")
        val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "m4v")
        val MEDIA_EXTENSIONS = AUDIO_EXTENSIONS + VIDEO_EXTENSIONS
        val IMAGE_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp")
        private val FOLDER_COVER_NAMES = listOf("cover", "folder")
        private val TRAILING_ID = Regex("\\s*\\[[A-Za-z0-9_-]{6,}]$")

        /** "Rain Tapping [dQw4w9WgXcQ]" -> "Rain Tapping"; Unterstriche werden Leerzeichen. */
        fun cleanTitle(fileName: String): String =
            fileName.replace(TRAILING_ID, "").replace('_', ' ').trim().ifEmpty { fileName }
    }
}
