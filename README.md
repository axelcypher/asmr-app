# ASMR Media Player

Android-App zum Einschlafen und Entspannen mit ASMR-Inhalten, angebunden an einen eigenen
[Audiobookshelf](https://www.audiobookshelf.org/)-Server.

Abgrenzung zur ABS-App bzw. Plappa: kein Hörbuch-Player, sondern Fokus auf Sleep-Timer,
Loops, Trigger-Filter und Offline-Nutzung.

## Server (Audiobookshelf)

- Eigene Bibliothek "ASMR" (Typ Podcast oder Buch, noch offen)
- Anbindung über die ABS REST API: Login/API-Token, Bibliotheken und Items, Streaming
  (Play-Session starten/schließen), Cover, Tags, Genres, Collections, Playlists
- Metadaten: Creator als Autor, Trigger als Tags (Tapping, Whispering, Rain, Roleplay,
  Brushing, ...), Länge
- Fortschritt-Sync nur für lange Items (> 30 min), kurze Clips ignorieren

## Features

**Bibliothek**
- Grid/Liste mit Cover, Creator, Länge
- Filter nach Trigger-Tags (Mehrfachauswahl), Creator, Länge
- Suche, Favoriten (ABS-Collection "Favoriten"), zuletzt gehört
- "Zufällig"-Button: Zufallstitel aus dem aktuellen Filter

**Player**
- Play/Pause, Seek, ±15 s
- Loop (Einzeltitel / Playlist), Shuffle, Gapless Playback
- Lautstärke-Normalisierung
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
- Media3 (ExoPlayer + MediaSessionService)
- Retrofit oder Ktor für die ABS API
- Room als lokaler Cache
- WorkManager für Downloads und Sync
- DataStore für Einstellungen

## MVP

1. Login + Bibliothek anzeigen
2. Streaming mit Media3 inkl. Lockscreen
3. Sleep-Timer mit Fade-Out
4. Loop + Shuffle
5. Tag-Filter
6. Offline-Downloads

## Offene Fragen

- Reicht die ABS-App oder Plappa mit kleinen Anpassungen?
- ABS-Bibliothekstyp: Podcast vs. Buch?
- Wie kommen Inhalte in ABS (yt-dlp + Skript, Patreon, manuell)?
- Tagging automatisieren (aus Titel/Beschreibung)?
- Zugriff von unterwegs: Reverse Proxy / Tailscale
