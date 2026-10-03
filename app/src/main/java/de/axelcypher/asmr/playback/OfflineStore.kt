package de.axelcypher.asmr.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import de.axelcypher.asmr.api.ApiJson
import de.axelcypher.asmr.api.ItemDto
import de.axelcypher.asmr.data.api.AsmrClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** Stand eines Downloads für die Oberfläche. */
data class OfflineState(val downloaded: Boolean, val percent: Int)

/**
 * Heruntergeladene Tracks für die Wiedergabe ohne Server. Audio liegt im Media3-Cache (abgelegt unter
 * dem Schlüssel `item-<id>`, unabhängig von der Server-URL), das Cover als Datei daneben; die
 * Track-Daten selbst stecken im Download, damit die Offline-Liste ohne Server auskommt.
 */
@OptIn(UnstableApi::class)
class OfflineStore(
    private val context: Context,
    private val httpClient: HttpClient,
    /** Hängt das Session-Token an; dieselbe Factory nutzt der Player. */
    upstream: DataSource.Factory,
) {
    private val databaseProvider = StandaloneDatabaseProvider(context)

    // Downloads werden nie automatisch verdrängt; gelöscht wird nur auf Wunsch.
    private val cache = SimpleCache(File(context.filesDir, "offline-audio"), NoOpCacheEvictor(), databaseProvider)
    private val coverDir = File(context.filesDir, "offline-covers").apply { mkdirs() }

    val downloadManager = DownloadManager(context, databaseProvider, cache, upstream, Executors.newFixedThreadPool(2))
        .apply { maxParallelDownloads = 2 }

    /** Liest zuerst aus den Downloads, sonst vom Server; gestreamtes Audio wird nicht zwischengespeichert. */
    val playbackDataSourceFactory: DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        .setCacheWriteDataSinkFactory(null)

    private val _states = MutableStateFlow<Map<Long, OfflineState>>(emptyMap())
    val states: StateFlow<Map<Long, OfflineState>> = _states

    private val _items = MutableStateFlow<List<ItemDto>>(emptyList())

    /** Vollständig heruntergeladene Tracks, alphabetisch. */
    val items: StateFlow<List<ItemDto>> = _items

    init {
        downloadManager.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) = refresh()
            override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) =
                refresh()
            override fun onDownloadRemoved(manager: DownloadManager, download: Download) = refresh()
        })
        downloadManager.resumeDownloads()
    }

    /** Startet den Download von Audio (im Hintergrund-Dienst) und Cover. */
    suspend fun download(item: ItemDto, serverUrl: String) {
        if (item.hasCover) {
            runCatching {
                val bytes = httpClient.get(AsmrClient.coverUrl(serverUrl, item.id)).readRawBytes()
                withContext(Dispatchers.IO) { coverFile(item.id).writeBytes(bytes) }
            }
        }
        val request = DownloadRequest.Builder(item.id.toString(), AsmrClient.audioUrl(serverUrl, item.id).toUri())
            .setCustomCacheKey(cacheKey(item.id))
            .setData(ApiJson.encodeToString(ItemDto.serializer(), item).toByteArray())
            .build()
        DownloadService.sendAddDownload(context, AsmrDownloadService::class.java, request, false)
    }

    fun remove(itemId: Long) {
        DownloadService.sendRemoveDownload(context, AsmrDownloadService::class.java, itemId.toString(), false)
        coverFile(itemId).delete()
    }

    /** Lokales Cover eines heruntergeladenen Tracks, sonst null. */
    fun coverFile(itemId: Long): File = File(coverDir, "$itemId.jpg")

    fun localCover(itemId: Long): File? = coverFile(itemId).takeIf { it.isFile }

    private fun refresh() {
        val downloads = buildList {
            downloadManager.downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) add(cursor.download)
            }
        }
        _states.value = downloads.associate { download ->
            download.request.id.toLong() to OfflineState(
                downloaded = download.state == Download.STATE_COMPLETED,
                percent = download.percentDownloaded.takeIf { it >= 0 }?.toInt() ?: 0,
            )
        }
        _items.value = downloads.filter { it.state == Download.STATE_COMPLETED }
            .mapNotNull { runCatching { ApiJson.decodeFromString<ItemDto>(String(it.request.data)) }.getOrNull() }
            .sortedBy { it.title.lowercase() }
    }

    companion object {
        fun cacheKey(itemId: Long) = "item-$itemId"
    }
}
