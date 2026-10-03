package de.axelcypher.asmr.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import de.axelcypher.asmr.AsmrApp

/**
 * Rückmeldung der Installations-Session: braucht Android eine Bestätigung (oder Play Protect eine
 * Entscheidung), öffnen wir den Dialog; Fehler landen als Meldung in der App.
 */
class UpdateResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as AsmrApp).container
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // Android startet die App neu.
            PackageInstaller.STATUS_FAILURE_ABORTED -> container.updateMessages.tryEmit("Update abgebrochen")
            else -> container.updateMessages.tryEmit(
                "Update fehlgeschlagen: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Status $status"),
            )
        }
    }
}
