package de.axelcypher.asmr.server.db

import de.axelcypher.asmr.api.ItemDto
import de.axelcypher.asmr.api.ItemPage
import de.axelcypher.asmr.api.ItemSort
import de.axelcypher.asmr.api.TagDto
import java.sql.Connection
import java.sql.ResultSet

data class NewItem(
    val title: String,
    val creator: String?,
    val durationSeconds: Double?,
    /** Relativ zum Medienordner. */
    val audioPath: String,
    val coverPath: String?,
    val description: String?,
    val sourceUrl: String?,
    /** Eindeutige Quelle, z.B. `youtube:abc123`, verhindert Doppelimporte. */
    val sourceKey: String?,
    /** Bewertungsmatrix Trigger -> Stärke. */
    val levels: Map<String, Int>,
    val ambient: Boolean = false,
)

data class ItemFiles(val audioPath: String, val coverPath: String?)

/** Was der Scanner mit Metadaten-Datei und Cover abgleicht. */
data class ItemRecord(
    val id: Long,
    val title: String,
    val creator: String?,
    val audioPath: String,
    val coverPath: String?,
    val levels: Map<String, Int>,
    val sourceUrl: String?,
    val ambient: Boolean = false,
)

/**
 * Wer fragt: bestimmt Favoriten und welche Ordner ausgeblendet sind (siehe [FolderAccessStore]).
 * [hiddenFolders] enthält Ordnerpfade ohne abschließenden Schrägstrich.
 */
data class Viewer(val userId: Long, val hiddenFolders: List<String> = emptyList()) {
    fun canSee(audioPath: String) = hiddenFolders.none { audioPath.startsWith("$it/") }
}

data class ItemQuery(
    val search: String? = null,
    val tags: List<String> = emptyList(),
    val creator: String? = null,
    val favoritesOnly: Boolean = false,
    /** Nur Tracks dieser Kategorie (direkt oder über einen zugeordneten Ordner). */
    val category: CategoryMembers? = null,
    /** Nur Ambiente: markierte Tracks oder Tracks in diesen Ordnern. */
    val ambientFolders: List<String>? = null,
    /** Länge in Sekunden (jeweils inklusive). */
    val minDuration: Double? = null,
    val maxDuration: Double? = null,
    val sort: ItemSort = ItemSort.TITLE,
    val page: Int = 0,
    val pageSize: Int = 60,
)

/** Ordner eines relativen Pfads, "" für die oberste Ebene. */
fun folderOf(audioPath: String) = audioPath.substringBeforeLast('/', missingDelimiterValue = "")

class ItemStore(private val db: Database, private val now: () -> Long = System::currentTimeMillis) {

    suspend fun add(item: NewItem): Long = db.tx {
        val id = insert(
            """INSERT INTO items (title, creator, duration_seconds, audio_path, cover_path, description,
                                  source_url, source_key, added_at, is_ambient)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            item.title, item.creator, item.durationSeconds, item.audioPath, item.coverPath, item.description,
            item.sourceUrl, item.sourceKey, now(), item.ambient,
        )
        setLevels(id, item.levels)
        id
    }

    suspend fun idByAudioPath(audioPath: String): Long? =
        db.tx { queryOne("SELECT id FROM items WHERE audio_path = ?", audioPath) { it.getLong(1) } }

    suspend fun audioPaths(): Set<String> =
        db.tx { query("SELECT audio_path FROM items") { it.getString(1) }.toSet() }

    suspend fun audioPathsById(): List<Pair<Long, String>> =
        db.tx { query("SELECT id, audio_path FROM items") { it.getLong(1) to it.getString(2) } }

    suspend fun idBySourceKey(sourceKey: String): Long? =
        db.tx { queryOne("SELECT id FROM items WHERE source_key = ?", sourceKey) { it.getLong(1) } }

    suspend fun records(): List<ItemRecord> = db.tx {
        query("SELECT * FROM items") { it.toRecord() }.map { it.copy(levels = levelsOf(it.id)) }
    }

    suspend fun record(id: Long): ItemRecord? = db.tx {
        queryOne("SELECT * FROM items WHERE id = ?", id) { it.toRecord() }?.let { it.copy(levels = levelsOf(it.id)) }
    }

    /** Liefert das Item nur, wenn der [viewer] es sehen darf. */
    suspend fun get(id: Long, viewer: Viewer): ItemDto? = db.tx {
        val where = mutableListOf("i.id = ?")
        val args = mutableListOf<Any?>(id)
        hiddenClause(viewer, where, args)
        queryOne("$SELECT_ITEM WHERE ${where.joinToString(" AND ")}", viewer.userId, *args.toTypedArray()) { it.toItem() }
            ?.withTags(this)
    }

    /** Dateien eines Items, sofern der [viewer] es sehen darf. */
    suspend fun files(id: Long, viewer: Viewer): ItemFiles? = db.tx {
        queryOne("SELECT audio_path, cover_path, c.avatar_path FROM items i $CREATOR_JOIN WHERE i.id = ?", id) {
            ItemFiles(it.getString("audio_path"), it.getString("cover_path") ?: it.getString("avatar_path"))
        }?.takeIf { viewer.canSee(it.audioPath) }
    }

    suspend fun list(viewer: Viewer, q: ItemQuery): ItemPage = db.tx {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        q.search?.takeIf { it.isNotBlank() }?.let {
            where += "(i.title LIKE ? OR i.creator LIKE ?)"
            args += "%$it%"
            args += "%$it%"
        }
        q.creator?.let {
            where += "i.creator = ? COLLATE NOCASE"
            args += it
        }
        q.tags.forEach {
            where += "EXISTS (SELECT 1 FROM item_tags t WHERE t.item_id = i.id AND t.tag = ?)"
            args += it
        }
        if (q.favoritesOnly) where += "f.item_id IS NOT NULL"
        q.category?.let { anyOf(where, args, it.itemIds, it.folders, extra = null) }
        q.ambientFolders?.let { anyOf(where, args, emptySet(), it, extra = "i.is_ambient = 1") }
        q.minDuration?.let {
            where += "i.duration_seconds >= ?"
            args += it
        }
        q.maxDuration?.let {
            where += "i.duration_seconds <= ?"
            args += it
        }
        hiddenClause(viewer, where, args)
        val whereSql = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        val order = when (q.sort) {
            ItemSort.TITLE -> "i.title COLLATE NOCASE, i.id"
            ItemSort.ADDED -> "i.added_at DESC, i.id DESC"
            ItemSort.RANDOM -> "RANDOM()"
        }
        val total = queryOne(
            "SELECT COUNT(*) FROM items i $FAVORITE_JOIN $whereSql", viewer.userId, *args.toTypedArray(),
        ) { it.getInt(1) }!!
        val items = query(
            "$SELECT_ITEM $whereSql ORDER BY $order LIMIT ? OFFSET ?",
            viewer.userId, *args.toTypedArray(), q.pageSize, q.page * q.pageSize,
        ) { it.toItem() }.map { it.withTags(this) }
        ItemPage(items, total, q.page, q.pageSize)
    }

    /** Alle für den [viewer] sichtbaren Items unterhalb von [folder] (rekursiv), für die Ordneransicht. */
    suspend fun inFolder(viewer: Viewer, folder: String): List<ItemDto> = db.tx {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        if (folder.isNotEmpty()) {
            where += "(i.audio_path >= ? AND i.audio_path < ?)"
            args += "$folder/"
            args += "${folder}0"
        }
        hiddenClause(viewer, where, args)
        val whereSql = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        query("$SELECT_ITEM $whereSql ORDER BY i.title COLLATE NOCASE, i.id", viewer.userId, *args.toTypedArray()) {
            it.toItem()
        }.map { it.withTags(this) }
    }

    suspend fun updateMetadata(id: Long, title: String?, creator: String?, levels: Map<String, Int>?) = db.tx {
        title?.let { update("UPDATE items SET title = ? WHERE id = ?", it, id) }
        creator?.let { update("UPDATE items SET creator = ? WHERE id = ?", it.ifBlank { null }, id) }
        levels?.let { setLevels(id, it) }
    }

    suspend fun setPaths(id: Long, audioPath: String, coverPath: String?) = db.tx {
        update("UPDATE items SET audio_path = ?, cover_path = ? WHERE id = ?", audioPath, coverPath, id)
    }

    /**
     * Benennt einen Trigger in allen Tracks um (Groß/klein egal) und liefert die betroffenen IDs,
     * damit der Aufrufer die Metadaten-Dateien neu schreiben kann. Hat ein Track beide Namen,
     * gewinnt der höhere Wert.
     */
    suspend fun renameTag(old: String, new: String): List<Long> = db.tx {
        val affected = query("SELECT item_id, level FROM item_tags WHERE tag = ?", old) { it.getLong(1) to it.getInt(2) }
        affected.forEach { (itemId, level) ->
            val existing = queryOne("SELECT level FROM item_tags WHERE item_id = ? AND tag = ? AND tag <> ?", itemId, new, old) { it.getInt(1) }
            update("DELETE FROM item_tags WHERE item_id = ? AND tag = ?", itemId, old)
            update("DELETE FROM item_tags WHERE item_id = ? AND tag = ?", itemId, new)
            update("INSERT INTO item_tags (item_id, tag, level) VALUES (?, ?, ?)", itemId, new, maxOf(level, existing ?: 0))
        }
        affected.map { it.first }
    }

    /** Entfernt einen Trigger aus allen Tracks; liefert die betroffenen IDs. */
    suspend fun removeTag(name: String): List<Long> = db.tx {
        val affected = query("SELECT item_id FROM item_tags WHERE tag = ?", name) { it.getLong(1) }
        update("DELETE FROM item_tags WHERE tag = ?", name)
        affected
    }

    suspend fun setAmbient(id: Long, ambient: Boolean) =
        db.tx { update("UPDATE items SET is_ambient = ? WHERE id = ?", ambient, id) }

    /** Sichtbare Tracks in der Reihenfolge von [ids] (z.B. einer Playlist). */
    suspend fun byIds(viewer: Viewer, ids: List<Long>): List<ItemDto> {
        if (ids.isEmpty()) return emptyList()
        val found = db.tx {
            val where = mutableListOf("i.id IN (${ids.joinToString { "?" }})")
            val args = ids.toMutableList<Any?>()
            hiddenClause(viewer, where, args)
            query("$SELECT_ITEM WHERE ${where.joinToString(" AND ")}", viewer.userId, *args.toTypedArray()) { it.toItem() }
                .map { it.withTags(this) }
        }.associateBy { it.id }
        return ids.mapNotNull(found::get)
    }

    /** Creator der sichtbaren Tracks mit Anzahl, meiste zuerst. */
    /** [onlyMarked]: nur als Creator markierte (Übersicht der App); sonst alle Namen (Verwaltung, Filter). */
    suspend fun creators(viewer: Viewer, onlyMarked: Boolean = false): List<de.axelcypher.asmr.api.CreatorSummaryDto> = db.tx {
        val where = mutableListOf("i.creator IS NOT NULL")
        val args = mutableListOf<Any?>()
        hiddenClause(viewer, where, args)
        val having = if (onlyMarked) "HAVING MAX(COALESCE(c.is_creator, 0)) = 1" else ""
        query(
            """SELECT i.creator, COUNT(*) AS n, MAX(c.avatar_path) AS avatar, MAX(COALESCE(c.is_creator, 0)) AS marked
               FROM items i $CREATOR_JOIN WHERE ${where.joinToString(" AND ")}
               GROUP BY i.creator COLLATE NOCASE $having ORDER BY n DESC, i.creator""",
            *args.toTypedArray(),
        ) { de.axelcypher.asmr.api.CreatorSummaryDto(it.getString(1), it.getInt(2), it.getString(3) != null, it.getInt(4) == 1) }
    }

    /** "Einer von": Track-IDs, Ordner-Bereiche oder eine zusätzliche Bedingung. Leer heißt: nichts. */
    private fun anyOf(
        where: MutableList<String>,
        args: MutableList<Any?>,
        ids: Set<Long>,
        folders: List<String>,
        extra: String?,
    ) {
        val parts = mutableListOf<String>()
        if (ids.isNotEmpty()) {
            parts += "i.id IN (${ids.joinToString { "?" }})"
            args.addAll(ids)
        }
        folders.forEach {
            val (from, to) = folderRange(it)
            parts += "(i.audio_path >= ? AND i.audio_path < ?)"
            args += from
            args += to
        }
        extra?.let { parts += it }
        where += if (parts.isEmpty()) "0" else parts.joinToString(" OR ", "(", ")")
    }

    suspend fun setCover(id: Long, coverPath: String?) =
        db.tx { update("UPDATE items SET cover_path = ? WHERE id = ?", coverPath, id) }

    /** Löscht den Eintrag und gibt die Dateien zurück, die der Aufrufer entfernen soll. */
    suspend fun delete(id: Long): ItemFiles? = db.tx {
        val files = queryOne("SELECT audio_path, cover_path FROM items WHERE id = ?", id) {
            ItemFiles(it.getString("audio_path"), it.getString("cover_path"))
        }
        update("DELETE FROM items WHERE id = ?", id)
        files
    }

    suspend fun setFavorite(userId: Long, itemId: Long, favorite: Boolean) = db.tx {
        if (favorite) {
            update("INSERT OR IGNORE INTO favorites (user_id, item_id, created_at) VALUES (?, ?, ?)", userId, itemId, now())
        } else {
            update("DELETE FROM favorites WHERE user_id = ? AND item_id = ?", userId, itemId)
        }
    }

    suspend fun tags(viewer: Viewer): List<TagDto> = db.tx {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        hiddenClause(viewer, where, args)
        val whereSql = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        query(
            """SELECT t.tag, COUNT(*) AS n FROM item_tags t JOIN items i ON i.id = t.item_id $whereSql
               GROUP BY t.tag COLLATE NOCASE ORDER BY n DESC, t.tag""",
            *args.toTypedArray(),
        ) { TagDto(it.getString("tag"), it.getInt("n")) }
    }

    /** Pfad-Bereich statt LIKE: nichts zu escapen, und '0' folgt in ASCII direkt auf '/'. */
    private fun hiddenClause(viewer: Viewer, where: MutableList<String>, args: MutableList<Any?>) {
        viewer.hiddenFolders.forEach {
            where += "NOT (i.audio_path >= ? AND i.audio_path < ?)"
            args += "$it/"
            args += "${it}0"
        }
    }

    private fun Connection.setLevels(itemId: Long, levels: Map<String, Int>) {
        update("DELETE FROM item_tags WHERE item_id = ?", itemId)
        normalizeLevels(levels).forEach { (tag, level) ->
            update("INSERT INTO item_tags (item_id, tag, level) VALUES (?, ?, ?)", itemId, tag, level)
        }
    }

    /** Stärkste zuerst, bei Gleichstand alphabetisch. */
    private fun Connection.levelsOf(itemId: Long): Map<String, Int> =
        query("SELECT tag, level FROM item_tags WHERE item_id = ? ORDER BY level DESC, tag", itemId) {
            it.getString(1) to it.getInt(2)
        }.toMap()

    private fun ItemDto.withTags(connection: Connection): ItemDto {
        val levels = connection.levelsOf(id)
        return copy(tags = levels.keys.toList(), levels = levels)
    }

    private fun ResultSet.toItem(): ItemDto {
        val audioPath = getString("audio_path")
        return ItemDto(
            id = getLong("id"),
            title = getString("title"),
            creator = getString("creator"),
            durationSeconds = getDoubleOrNull("duration_seconds"),
            tags = emptyList(),
            hasCover = getString("cover_path") != null || getString("avatar_path") != null,
            isFavorite = getLongOrNull("fav_item") != null,
            sourceUrl = getString("source_url"),
            addedAt = getLong("added_at"),
            folder = folderOf(audioPath),
            isAmbient = getInt("is_ambient") == 1,
        )
    }

    private fun ResultSet.toRecord() = ItemRecord(
        id = getLong("id"),
        title = getString("title"),
        creator = getString("creator"),
        audioPath = getString("audio_path"),
        coverPath = getString("cover_path"),
        levels = emptyMap(),
        sourceUrl = getString("source_url"),
        ambient = getInt("is_ambient") == 1,
    )

    private companion object {
        const val CREATOR_JOIN = "LEFT JOIN creators c ON c.name = i.creator"

        /** Der erste Parameter ist immer die Benutzer-ID für den Favoriten-Join. */
        const val FAVORITE_JOIN = "LEFT JOIN favorites f ON f.item_id = i.id AND f.user_id = ?"
        const val SELECT_ITEM =
            "SELECT i.*, f.item_id AS fav_item, c.avatar_path FROM items i $FAVORITE_JOIN $CREATOR_JOIN"
    }
}

/** Trimmt Namen, entfernt Doppelte (Groß/klein egal) und alles mit Stärke 0; begrenzt auf 1..10. */
fun normalizeLevels(levels: Map<String, Int>): Map<String, Int> =
    levels.entries
        .map { it.key.trim() to it.value.coerceIn(0, de.axelcypher.asmr.api.MAX_TRIGGER_LEVEL) }
        .filter { (tag, level) -> tag.isNotEmpty() && level > 0 }
        .distinctBy { it.first.lowercase() }
        .toMap()
