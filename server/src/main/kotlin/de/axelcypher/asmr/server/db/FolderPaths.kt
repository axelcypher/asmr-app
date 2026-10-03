package de.axelcypher.asmr.server.db

/** Speichert Ordnerpfade und zieht sie nach, wenn ein Ordner auf dem NAS umbenannt wird. */
interface FolderPathOwner {
    /** Ersetzt [from] (und alles darunter) durch [to]. */
    suspend fun renameFolder(from: String, to: String)
}

/** Neuer Pfad, falls [path] gleich [from] ist oder darunter liegt, sonst null. */
fun renamedPath(path: String, from: String, to: String): String? = when {
    path == from -> to
    path.startsWith("$from/") -> to + path.removePrefix(from)
    else -> null
}

/** Pfad-Bereich für SQL: `audio_path >= 'Ordner/' AND audio_path < 'Ordner0'` (siehe ItemStore). */
fun folderRange(folder: String) = "$folder/" to "${folder}0"
