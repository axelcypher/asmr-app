package de.axelcypher.asmr.server

import de.axelcypher.asmr.api.ApiJson
import de.axelcypher.asmr.server.auth.OidcClient
import de.axelcypher.asmr.server.auth.SsoService
import de.axelcypher.asmr.server.auth.randomToken
import de.axelcypher.asmr.server.db.CreatorStore
import de.axelcypher.asmr.server.db.Database
import de.axelcypher.asmr.server.db.FolderAccessStore
import de.axelcypher.asmr.server.db.ImportStore
import de.axelcypher.asmr.server.db.ItemStore
import de.axelcypher.asmr.server.db.UserStore
import de.axelcypher.asmr.server.imports.ImportWorker
import de.axelcypher.asmr.server.imports.YtDlpAvatarFetcher
import de.axelcypher.asmr.server.imports.YtDlpDownloader
import de.axelcypher.asmr.server.library.FfmpegProbe
import de.axelcypher.asmr.server.library.LibraryScanner
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.slf4j.LoggerFactory
import kotlin.io.path.createDirectories
import kotlin.time.Duration.Companion.minutes

fun main() {
    val log = LoggerFactory.getLogger("asmr")
    val config = Config.fromEnv()
    config.dataDir.createDirectories()
    config.mediaDir.createDirectories()

    val db = Database.open(config.dataDir.resolve("asmr.db"))
    val users = UserStore(db)
    val items = ItemStore(db)
    val imports = ImportStore(db)

    runBlocking { bootstrapAdmin(users, log) }

    val sso = config.oidc?.let { oidc ->
        val http = HttpClient(CIO) {
            install(ContentNegotiation) { json(ApiJson) }
            install(HttpTimeout) { requestTimeoutMillis = 10_000 }
            defaultRequest { header("User-Agent", "asmr-server (OIDC client)") }
        }
        SsoService(oidc, OidcClient(oidc, http), users, "${config.publicUrl}/api/auth/sso/callback")
    }
    log.info("SSO {}", if (sso != null) "aktiv (${sso.providerName})" else "nicht eingerichtet")

    val libraryLock = Mutex()
    val coverDir = config.dataDir.resolve("covers").createDirectories()
    val scanner = LibraryScanner(items, config.mediaDir, coverDir, FfmpegProbe(), libraryLock)
    val worker = ImportWorker(
        imports = imports,
        items = items,
        downloader = YtDlpDownloader(config.ytDlp),
        tempDir = config.dataDir.resolve("tmp").createDirectories(),
        mediaDir = config.mediaDir,
        libraryLock = libraryLock,
    )
    val services = Services(
        users = users,
        items = items,
        imports = imports,
        folderAccess = FolderAccessStore(db),
        creators = CreatorStore(db),
        worker = worker,
        sso = sso,
        scanner = scanner,
        avatarFetcher = YtDlpAvatarFetcher(config.ytDlp, config.dataDir.resolve("tmp")),
        mediaDir = config.mediaDir,
        coverDir = coverDir,
        avatarDir = config.dataDir.resolve("avatars").createDirectories(),
    )

    embeddedServer(Netty, port = config.port) {
        asmrModule(services)
        worker.start(this)
        scanner.start(this, config.scanIntervalMinutes.minutes)
    }.start(wait = true)
}

/** Ohne Konten legt der Server einen Admin an und schreibt das Passwort einmalig ins Log. */
private suspend fun bootstrapAdmin(users: UserStore, log: org.slf4j.Logger) {
    if (users.count() > 0) return
    val password = randomToken(18)
    users.create("admin", password, isAdmin = true)
    log.warn("Erster Start: Konto 'admin' angelegt, Passwort: {}  (bitte nach dem ersten Login ändern)", password)
}
