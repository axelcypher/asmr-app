package de.axelcypher.asmr.server.imports

import de.axelcypher.asmr.api.ImportStatus
import de.axelcypher.asmr.server.db.ImportStore
import de.axelcypher.asmr.server.db.ItemStore
import de.axelcypher.asmr.server.db.NewItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.extension
import kotlin.io.path.relativeTo

/** Arbeitet Import-Jobs nacheinander ab, damit yt-dlp den Server nicht mit Parallel-Downloads flutet. */
class ImportWorker(
    private val imports: ImportStore,
    private val items: ItemStore,
    private val downloader: Downloader,
    private val tempDir: Path,
    private val mediaDir: Path,
    /** Mit dem [de.axelcypher.asmr.server.library.LibraryScanner] geteilt. */
    private val libraryLock: Mutex,
) {
    private val log = LoggerFactory.getLogger(ImportWorker::class.java)
    private val queue = Channel<Long>(Channel.UNLIMITED)

    fun start(scope: CoroutineScope): Job = scope.launch {
        imports.unfinished().forEach { queue.send(it.id) }
        for (jobId in queue) process(jobId)
    }

    fun enqueue(jobId: Long) {
        queue.trySend(jobId)
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    suspend fun process(jobId: Long) {
        val job = imports.get(jobId) ?: return
        imports.setStatus(jobId, ImportStatus.RUNNING)
        val workDir = tempDir.resolve("import-$jobId")
        try {
            workDir.deleteRecursively()
            workDir.createDirectories()
            val download = downloader.download(job.url, workDir)
            val itemId = store(download)
            imports.setStatus(jobId, ImportStatus.DONE, itemId = itemId)
            log.info("Import {} fertig: {} -> Item {}", jobId, job.url, itemId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Import {} fehlgeschlagen: {}", jobId, e.message)
            imports.setStatus(jobId, ImportStatus.FAILED, error = e.message ?: e::class.simpleName)
        } finally {
            workDir.deleteRecursively()
        }
    }

    private suspend fun store(download: Download): Long = libraryLock.withLock {
        val info = download.info
        val sourceKey = "${info.extractor}:${info.id}"
        items.idBySourceKey(sourceKey)?.let { return@withLock it }

        val folder = mediaDir.resolve(safeName(info.uploader ?: "Unbekannt")).createDirectories()
        val baseName = "${safeName(info.title)} [${safeName(info.id)}]"
        val audio = move(download.audio, folder.resolve("$baseName.${download.audio.extension}"))
        val cover = download.cover?.let { move(it, folder.resolve("$baseName.jpg")) }

        items.add(
            NewItem(
                title = info.title,
                creator = info.uploader,
                durationSeconds = info.durationSeconds,
                audioPath = audio.relativeTo(mediaDir).toString().replace('\\', '/'),
                coverPath = cover?.relativeTo(mediaDir)?.toString()?.replace('\\', '/'),
                description = info.description,
                sourceUrl = info.webpageUrl,
                sourceKey = sourceKey,
                tags = TriggerTags.detect(info.title, info.description, info.tags),
            ),
        )
    }

    /** Temp-Ordner und NFS-Share liegen auf verschiedenen Dateisystemen: Move ist dort Copy + Delete. */
    private fun move(source: Path, target: Path): Path =
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)

    companion object {
        private val UNSAFE = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

        fun safeName(value: String): String =
            value.replace(UNSAFE, "_").trim().trim('.').take(120).ifEmpty { "_" }
    }
}
