package de.axelcypher.asmr

import android.app.Application
import coil3.EventListener
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

class AsmrApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /**
     * Cover und Profilbilder über Coils OkHttp-Fetcher; das Session-Token hängt nur an Anfragen an
     * den eigenen Server, damit es nie an fremde Hosts geht.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val session = container.currentSession
                val serverHost = session?.serverUrl?.toHttpUrlOrNull()?.host
                if (session == null || request.url.host != serverHost) return@addInterceptor chain.proceed(request)
                chain.proceed(request.newBuilder().header("Authorization", "Bearer ${session.token}").build())
            }
            .build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            // Eigenes Verzeichnis: der alte Cache aus der Zeit des Ktor-Bildladers wird nicht mehr gelesen.
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("images-v2").toOkioPath())
                    .maxSizePercent(0.05)
                    .build()
            }
            .eventListener(object : EventListener() {
                override fun onError(request: ImageRequest, result: ErrorResult) {
                    container.reportImageError("${request.data}: ${result.throwable}")
                }
            })
            .build()
    }
}
