# ASMR Media Player

Android-App zum Einschlafen und Entspannen mit ASMR-Inhalten, angebunden an einen eigenen
[Audiobookshelf](https://www.audiobookshelf.org/)-Server (ABS), der bereits vorhanden ist.

Abgrenzung zur ABS-App bzw. Plappa: kein Hörbuch-Player, sondern Fokus auf Sleep-Timer,
Loops, Trigger-Filter und Offline-Nutzung.

## Server (Audiobookshelf)

- Eigene Bibliothek "ASMR" (Typ Podcast oder Buch, noch offen; siehe Offene Fragen)
- Anbindung über die ABS REST API: Login/API-Token, Bibliotheken und Items, Streaming
  (Play-Session starten/schließen), Cover, Tags, Genres, Collections, Playlists
- Metadaten: Creator als Autor, Trigger als Tags (Tapping, Whispering, Rain, Roleplay,
  Brushing, ...), Länge
- Fortschritt-Sync nur für lange Items (Grenze noch offen, etwa > 30 min), kurze Clips ignorieren

## Features

**Bibliothek**
- Grid/Liste mit Cover, Creator, Länge
- Filter nach Trigger-Tags (Mehrfachauswahl), Creator, Länge
- Suche, Favoriten (ABS-Collection "Favoriten"), zuletzt gehört
- "Zufällig"-Button: Zufallstitel aus dem aktuellen Filter

**Player**
- Play/Pause, Seek, ±15 s
- Loop (Einzeltitel / Playlist), Shuffle, Gapless Playback
- Lautstärke-Normalisierung (die Creator sind sehr unterschiedlich laut)
- Lockscreen- und Notification-Controls, Kopfhörer-/Bluetooth-Tasten
- Android Auto (optional)

**Sleep-Timer**
- Presets 15 / 30 / 45 / 60 / 90 min, Ende des Titels
- Einstellbares Fade-Out (1–5 min)
- Shake-to-extend (+10 min)
- Optional: Schwarz-/Dim-Modus, automatisch "Nicht stören"

**Offline**
- Downloads einzelner Items / Playlists
- Automatischer Download von Favoriten (nur WLAN)
- Speicherlimit und Auto-Cleanup

**Später:** zweite Spur als Ambient-Layer (Regen, Rauschen) mit eigener Lautstärke

## Tech-Stack

- Kotlin + Jetpack Compose
- Media3 (ExoPlayer + MediaSessionService) für Hintergrund-Wiedergabe, Lockscreen und Android Auto
- Retrofit oder Ktor für die ABS API
- Room als lokaler Cache für Bibliothek, Downloads und Fortschritt
- WorkManager für Downloads und Sync
- DataStore für Einstellungen (Server-URL, Token, Timer-Standardwerte)

## MVP

1. Login + Bibliothek anzeigen
2. Streaming mit Media3 inkl. Lockscreen
3. Sleep-Timer mit Fade-Out
4. Loop + Shuffle
5. Tag-Filter
6. Offline-Downloads

## Offene Fragen

- Reicht die ABS-App oder Plappa mit kleinen Anpassungen?
- ABS-Bibliothekstyp: Podcast vs. Buch, was bildet Einzel-Clips besser ab?
- Wie kommen Inhalte in ABS (YouTube-Download via yt-dlp + Skript, Patreon-Downloads, manuell)?
- Tagging automatisieren (aus Titel/Beschreibung)?
- Zugriff von unterwegs: Reverse Proxy / Tailscale

## Entwicklung

Voraussetzungen: Android Studio (bringt JDK und SDK mit), compileSdk 37.

```sh
./gradlew testDebugUnitTest assembleDebug
```

Stand: Login am ABS-Server (Access-/Refresh-Token, ab ABS 2.26) und Bibliothek als Grid mit
Cover, Creator und Länge, Nachladen beim Scrollen, Wechsel zwischen Bibliotheken.

### Release-Signatur

- Keystore und Passwörter liegen außerhalb des Repos unter `~/.keystores/`
  (`asmr-app-release.jks`, `asmr-app-keystore.properties`). Lokale Release-Builds lesen sie
  automatisch.
- GitHub Actions nutzt die Repo-Secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
  `ANDROID_KEY_ALIAS` und `ANDROID_KEY_PASSWORD`.
- Jeder Push auf `main` baut ein signiertes APK als Artefakt; ein Tag `v*` erzeugt zusätzlich
  ein GitHub-Release.
- Den Keystore sicher sichern: ohne ihn lassen sich installierte Versionen nicht mehr updaten.
