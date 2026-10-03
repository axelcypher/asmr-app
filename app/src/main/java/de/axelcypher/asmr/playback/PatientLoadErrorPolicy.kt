package de.axelcypher.asmr.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Gibt bei Netzwerkfehlern nie auf: WLAN-Löcher im Schlaf oder ein Proxy, der lange Streams kappt,
 * sollen nur kurz puffern lassen statt die Wiedergabe zu beenden. Echte Fehler (4xx, kaputte Datei)
 * werden weiter sofort gemeldet.
 */
@OptIn(UnstableApi::class)
class PatientLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val error = loadErrorInfo.exception
        val clientError = error is HttpDataSource.InvalidResponseCodeException && error.responseCode in 400..499
        // Kaputte oder unbekannte Datei: endloses Wiederholen hilft nicht.
        if (clientError || error is androidx.media3.common.ParserException) return super.getRetryDelayMsFor(loadErrorInfo)
        return (loadErrorInfo.errorCount * 1_000L).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = Int.MAX_VALUE

    private companion object {
        const val MAX_RETRY_DELAY_MS = 10_000L
    }
}

/** Fehlercodes aus Netzwerk und I/O, nach denen sich ein Neustart der Wiedergabe lohnt. */
fun isRecoverable(errorCode: Int) = errorCode in 2000..2999 && errorCode != androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND

