package de.axelcypher.asmr.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import de.axelcypher.asmr.AsmrApp
import de.axelcypher.asmr.MainActivity

/**
 * Hält die Hauptspur im Vordergrund (Benachrichtigung, Lockscreen, Kopfhörertasten). Die Player
 * selbst gehören der [PlaybackEngine], damit Ambient-Spur und Sleep-Timer daneben laufen können.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val engine = (application as AsmrApp).container.playbackEngine
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, engine.mainPlayer)
            .setSessionActivity(openApp)
            // Cover brauchen das Session-Token, deshalb dieselbe DataSource wie fürs Audio.
            .setBitmapLoader(
                CacheBitmapLoader(
                    DataSourceBitmapLoader.Builder(this).setDataSourceFactory(engine.dataSourceFactory).build(),
                ),
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        // Die Player gehören der Engine und werden hier nicht freigegeben.
        session?.release()
        session = null
        super.onDestroy()
    }
}
