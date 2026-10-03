package de.axelcypher.asmr

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

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
            .build()
    }
}
