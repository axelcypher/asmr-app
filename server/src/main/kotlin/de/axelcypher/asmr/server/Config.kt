package de.axelcypher.asmr.server

import java.nio.file.Path
import kotlin.io.path.Path

/** Alle Einstellungen kommen aus Umgebungsvariablen, siehe README im Ordner server/. */
data class Config(
    val port: Int,
    /** Datenbank, temporäre Downloads. */
    val dataDir: Path,
    /** Audiodateien und Cover (NFS-Share). */
    val mediaDir: Path,
    /** Öffentliche Basis-URL, wird für den OIDC-Callback gebraucht. */
    val publicUrl: String?,
    val ytDlp: String,
    /** Wie oft der Medienordner nach neuen Dateien durchsucht wird. */
    val scanIntervalMinutes: Int,
    val oidc: OidcConfig?,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun value(name: String) = env[name]?.takeIf { it.isNotBlank() }
            val oidc = value("ASMR_OIDC_CLIENT_ID")?.let { clientId ->
                OidcConfig(
                    providerName = value("ASMR_OIDC_PROVIDER_NAME") ?: "SSO",
                    discoveryUrl = value("ASMR_OIDC_DISCOVERY_URL")
                        ?: error("ASMR_OIDC_DISCOVERY_URL fehlt, obwohl ASMR_OIDC_CLIENT_ID gesetzt ist"),
                    clientId = clientId,
                    clientSecret = value("ASMR_OIDC_CLIENT_SECRET"),
                    scopes = value("ASMR_OIDC_SCOPES") ?: "openid email profile",
                    usernameClaim = value("ASMR_OIDC_USERNAME_CLAIM") ?: "preferred_username",
                    adminGroup = value("ASMR_OIDC_ADMIN_GROUP"),
                    accountMatching = value("ASMR_OIDC_ACCOUNT_MATCHING")
                        ?.let { AccountMatching.valueOf(it.uppercase()) }
                        ?: AccountMatching.AUTO_PROVISION,
                )
            }
            val publicUrl = value("ASMR_PUBLIC_URL")?.trimEnd('/')
            require(oidc == null || publicUrl != null) { "ASMR_PUBLIC_URL wird für SSO benötigt" }
            return Config(
                port = value("ASMR_PORT")?.toInt() ?: 8080,
                dataDir = Path(value("ASMR_DATA_DIR") ?: "/data"),
                mediaDir = Path(value("ASMR_MEDIA_DIR") ?: "/media"),
                publicUrl = publicUrl,
                ytDlp = value("ASMR_YTDLP") ?: "yt-dlp",
                scanIntervalMinutes = value("ASMR_SCAN_INTERVAL_MINUTES")?.toInt() ?: 30,
                oidc = oidc,
            )
        }
    }
}

data class OidcConfig(
    val providerName: String,
    val discoveryUrl: String,
    val clientId: String,
    /** Leer bei einem Public Client (nur PKCE). */
    val clientSecret: String?,
    val scopes: String,
    val usernameClaim: String,
    /** Mitglieder dieser Gruppe (Claim `groups`) werden Admin. */
    val adminGroup: String?,
    val accountMatching: AccountMatching,
)

/**
 * Wie eine SSO-Identität, die der Server noch nicht kennt, einem Konto zugeordnet wird
 * (wie bei DeckLedger, jede Stufe schließt die vorherige ein).
 */
enum class AccountMatching {
    /** Nur bereits verknüpfte Identitäten. */
    MANUAL,

    /** Zusätzlich: verifizierte E-Mail passt zu einem noch nicht verknüpften Konto. */
    EMAIL,

    /** Zusätzlich: unbekannte Identitäten bekommen ein neues Konto (nie Admin, außer per Gruppe). */
    AUTO_PROVISION,
}
