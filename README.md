# ASMR Media Player

Android-App zum Einschlafen und Entspannen mit ASMR-Inhalten, mit eigenem Server als Backend.

Kein Hörbuch-Player, sondern Fokus auf Sleep-Timer, Loops, Trigger-Filter und Offline-Nutzung.
Audiobookshelf wurde verworfen: das Datenmodell (Bücher/Podcasts) passt nicht zu einzelnen Clips
mit Trigger-Tags, und Inhalte sollen per yt-dlp direkt auf dem Server importiert werden.

## Aufbau

| Modul | Inhalt |
| --- | --- |
| `app/` | Android-App (Kotlin, Jetpack Compose) |
| `server/` | Backend (Kotlin, Ktor, SQLite), läuft als Docker-Container |
| `shared/` | API-Modelle, die App und Server gemeinsam nutzen |

## Server

- **Konten:** mehrere Benutzer mit eigenen Favoriten. Login per Passwort oder SSO (OIDC, z.B.
  Authentik). Session-Tokens werden nur gehasht gespeichert und laufen nach 180 Tagen ohne
  Nutzung ab.
- **Erster Start:** ohne Konten legt der Server `admin` mit Zufallspasswort an und schreibt das
  Passwort einmalig ins Log.
- **Bibliothek:** Titel, Creator, Länge, Trigger-Tags, Cover; Suche, Filter nach mehreren Tags,
  Creator und Favoriten, Sortierung nach Titel, Datum oder zufällig.
- **Streaming:** `GET /api/items/{id}/audio` mit Range-Requests (Spulen in Media3, Downloads).
- **Import:** `POST /api/imports` mit einer URL; yt-dlp lädt Audio (ohne Neukodierung), Cover und
  Metadaten. Trigger-Tags werden aus Titel und Beschreibung abgeleitet. Doppelte Quellen werden
  erkannt.
- **Speicher:** Audiodateien und Cover auf dem NAS (`/media`, NFS), Datenbank in `/data`.
- **Ordner:** Die Bibliothek spiegelt die Ordnerstruktur auf dem NAS (z.B. ein Ordner pro Creator);
  Admins legen Ordner an und verschieben Tracks. Neue Dateien findet ein Scan (beim Start, alle 30 min,
  per Knopf im Profil).
- **Cover:** Bild mit dem Namen des Tracks, sonst `cover.*`/`folder.*` im Ordner, sonst (bei Videos)
  ein Vorschaubild, sonst das Profilbild des Creators.
- **Metadaten-Datei:** Titel, Creator und Bewertung stehen in `<name>.asmr.json` neben dem Track.
  Die App ändert nur diese Datei, nie den Dateinamen; der Scan liest sie, auch nach Datenbankverlust.
- **Bewertungsmatrix:** pro Track Stärke 0–10 je Trigger (Kisses, Talking, Licking, Scratching …);
  angezeigt werden nur Trigger über 0. Erkannte Trigger starten bei 5.
- **Zugriff:** Admins schränken Ordner (samt Unterordnern) auf SSO-Gruppen und/oder einzelne
  Benutzer ein. Gesperrte Inhalte sind für andere unsichtbar, Admins sehen alles.
- **Creator:** Links zu YouTube, Patreon, Fansly & Co. und ein Profilbild (Upload oder vom YouTube-Kanal).

### SSO

Ablauf für die App: Die App öffnet `/api/auth/sso/start` im Custom Tab, der Server führt den
Authorization Code Flow mit PKCE gegen den Provider aus und leitet mit einem Einmal-Code auf
`de.axelcypher.asmr://sso` zurück. Die App tauscht den Code (zusätzlich per PKCE an die App
gebunden) gegen ein Session-Token.

Neue Identitäten werden wie bei DeckLedger über `ASMR_OIDC_ACCOUNT_MATCHING` zugeordnet:
`manual` (nur bereits verknüpfte), `email` (verifizierte E-Mail eines unverknüpften Kontos) oder
`auto_provision` (zusätzlich neues Konto anlegen). Mitglieder von `ASMR_OIDC_ADMIN_GROUP` werden
bei jedem Login Admin.

Im Provider (Authentik) als Redirect-URI eintragen: `https://<host>/api/auth/sso/callback`.

### Umgebungsvariablen

| Variable | Bedeutung | Standard |
| --- | --- | --- |
| `ASMR_PORT` | HTTP-Port | `8080` |
| `ASMR_DATA_DIR` | Datenbank, temporäre Downloads | `/data` |
| `ASMR_MEDIA_DIR` | Audiodateien und Cover | `/media` |
| `ASMR_PUBLIC_URL` | öffentliche Basis-URL, Pflicht für SSO | – |
| `ASMR_YTDLP` | Pfad zu yt-dlp | `yt-dlp` |
| `ASMR_OIDC_CLIENT_ID` | aktiviert SSO | – |
| `ASMR_OIDC_CLIENT_SECRET` | leer bei Public Client | – |
| `ASMR_OIDC_DISCOVERY_URL` | `.well-known/openid-configuration` des Providers | – |
| `ASMR_OIDC_PROVIDER_NAME` | Name auf dem Login-Button | `SSO` |
| `ASMR_OIDC_SCOPES` | angefragte Scopes | `openid email profile` |
| `ASMR_OIDC_USERNAME_CLAIM` | Claim für den Benutzernamen | `preferred_username` |
| `ASMR_OIDC_ADMIN_GROUP` | Gruppe (Claim `groups`) für Admin-Rechte | – |
| `ASMR_OIDC_ACCOUNT_MATCHING` | `manual`, `email`, `auto_provision` | `auto_provision` |

### Deployment

Die Workflow-Datei `.github/workflows/server.yml` testet den Server, baut das Image
`ghcr.io/axelcypher/asmr-server` und setzt den neuen `sha-…`-Tag in
`axelcypher/gitops-homelab` (`apps/docker/asmr-server/prod.env`). Komodo deployt von dort per
Resource Sync auf `vm-docker-01`. Dafür braucht das Repo das Secret `GITOPS_DEPLOY_TOKEN`
(Schreibrecht auf gitops-homelab).

## App-Features (geplant)

### Bibliothek

- Grid/Liste mit Cover, Creator, Länge
- Filter nach Trigger-Tags (Mehrfachauswahl), Creator, Länge
- Suche, Favoriten, zuletzt gehört
- "Zufällig"-Button: Zufallstitel aus dem aktuellen Filter
- Import per URL (auch über "Teilen" aus der YouTube-App)

### Player

- Play/Pause, Seek, ±15 s
- Loop (Einzeltitel / Playlist), Shuffle, Gapless Playback
- Lautstärke-Normalisierung (die Creator sind sehr unterschiedlich laut)
- Lockscreen- und Notification-Controls, Kopfhörer-/Bluetooth-Tasten
- Android Auto (optional)

### Sleep-Timer

- Presets 15 / 30 / 45 / 60 / 90 min, Ende des Titels
- Einstellbares Fade-Out (1–5 min)
- Shake-to-extend (+10 min)
- Optional: Schwarz-/Dim-Modus, automatisch "Nicht stören"

### Offline

- Downloads einzelner Items / Playlists
- Automatischer Download von Favoriten (nur WLAN)
- Speicherlimit und Auto-Cleanup

### Zweite Spur (Ambient) ✔

- Beliebiges Item der Bibliothek als Ambient-Spur (lange antippen), läuft im Loop mit eigener
  Lautstärke, folgt Play/Pause und wird vom Sleep-Timer mit ausgeblendet

## MVP

1. Login + Bibliothek anzeigen ✔
2. Streaming mit Media3 inkl. Lockscreen ✔
3. Sleep-Timer mit Fade-Out ✔
4. Loop + Shuffle ✔
5. Tag-Filter
6. Offline-Downloads

## Offene Fragen

- Fortschritt merken: nur für lange Items (etwa > 30 min)?
- Weitere Importwege (Patreon-Downloads, Upload)?

## Entwicklung

Voraussetzungen: Android Studio (bringt JDK und SDK mit), compileSdk 37.

```sh
./gradlew testDebugUnitTest :server:test assembleDebug
# Server lokal starten (yt-dlp und ffmpeg im PATH für Importe)
ASMR_DATA_DIR=./tmp/data ASMR_MEDIA_DIR=./tmp/media ./gradlew :server:run
```

### Release-Signatur

- Keystore und Passwörter liegen außerhalb des Repos unter `~/.keystores/`
  (`asmr-app-release.jks`, `asmr-app-keystore.properties`). Lokale Release-Builds lesen sie
  automatisch.
- GitHub Actions nutzt die Repo-Secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
  `ANDROID_KEY_ALIAS` und `ANDROID_KEY_PASSWORD`.
- Jeder Push auf `main` baut ein signiertes APK (Artefakt und Nightly-Release); ein Tag `v*` erzeugt
  ein stabiles GitHub-Release.
- Den Keystore sicher sichern: ohne ihn lassen sich installierte Versionen nicht mehr updaten.

### Auto-Update

Die App prüft beim Start die GitHub-Releases (Kanal im Menü wählbar):

- **Stabil:** Releases aus Tags `v*`
- **Nightly:** Vorab-Release `nightly`, das jeder Push auf `main` ersetzt

Die CI setzt `versionCode` auf die Run-Nummer und benennt das APK `asmr-player-<versionCode>.apk`;
die App vergleicht diese Nummer mit der eigenen.
