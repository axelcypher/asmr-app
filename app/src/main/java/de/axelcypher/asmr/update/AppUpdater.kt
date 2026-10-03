package de.axelcypher.asmr.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import de.axelcypher.asmr.BuildConfig
import de.axelcypher.asmr.api.ApiJson
import de.axelcypher.asmr.data.settings.UpdateChannel
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

data class AppUpdate(val versionCode: Int, val versionName: String, val apkUrl: String)

/**
 * Sucht in den GitHub-Releases nach einem neueren APK. Die CI benennt die APKs
 * `asmr-player-<versionCode>.apk`; Kanal STABLE nimmt nur echte Releases (Tags `v*`), NIGHTLY
 * zusätzlich das Vorab-Release `nightly`, das bei jedem Push auf `main` neu gebaut wird.
 */
class AppUpdater(private val context: Context) {

    private val http = HttpClient(OkHttp) {
        expectSuccess = true
        install(ContentNegotiation) { json(ApiJson) }
    }

    suspend fun check(channel: UpdateChannel): AppUpdate? {
        val releases = http.get(RELEASES_URL) {
            header("Accept", "application/vnd.github+json")
        }.body<List<GitHubRelease>>()
        return releases
            .filter { !it.draft && (channel == UpdateChannel.NIGHTLY || !it.prerelease) }
            .mapNotNull { release ->
                release.assets.firstNotNullOfOrNull { asset ->
                    APK_NAME.matchEntire(asset.name)?.let {
                        AppUpdate(it.groupValues[1].toInt(), release.name ?: release.tag, asset.url)
                    }
                }
            }
            .maxByOrNull { it.versionCode }
            ?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
    }

    /** Lädt das APK und liefert den Intent, der den System-Installer öffnet. */
    suspend fun download(update: AppUpdate): Intent {
        val bytes = http.get(update.apkUrl).readRawBytes()
        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, "updates").apply { mkdirs() }
                .resolve("asmr-player.apk")
                .apply { writeBytes(bytes) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tag: String,
        val name: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    private data class GitHubAsset(
        val name: String,
        @SerialName("browser_download_url") val url: String,
    )

    private companion object {
        const val RELEASES_URL = "https://api.github.com/repos/axelcypher/asmr-app/releases?per_page=30"
        val APK_NAME = Regex("asmr-player-(\\d+)\\.apk")
    }
}
