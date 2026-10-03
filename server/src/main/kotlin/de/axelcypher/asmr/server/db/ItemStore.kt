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
    val tags: List<String>,
)

data class ItemFiles(val audioPath: String, val coverPath: String?)

data class ItemQuery(
    val search: String? = null,
    val tags: List<String> = emptyList(),
    val creator: String? = null,
    val favoritesOnly: Boolean = false,
    val sort: ItemSort = ItemSort.TITLE,
    val page: Int = 0,
    val pageSize: Int = 60,
)

class ItemStore(private val db: Database, private val now: () -> Long = System::currentTimeMillis) {

    suspend fun add(item: NewItem): Long = db.tx {
        val id = insert(
            """INSERT INTO items (title, creator, duration_seconds, audio_path, cover_path, description,
                                  source_url, source_key, added_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            item.title, item.creator, item.durationSeconds, item.audioPath, item.coverPath, item.description,
            item.sourceUrl, item.sourceKey, now(),
        )
        setTags(id, item.tags)
        id
    }

    suspend fun idBySourceKey(sourceKey: String): Long? =
        db.tx { queryOne("SELECT id FROM items WHERE source_key = ?", sourceKey) { it.getLong(1) } }

    suspend fun get(id: Long, userId: Long): ItemDto? = db.tx {
        queryOne("$SELECT_ITEM WHERE i.id = ?", userId, id) { it.toItem() }
            ?.withTags(this)
    }

    suspend fun files(id: Long): ItemFiles? = db.tx {
        queryOne("SELECT audio_path, cover_path FROM items WHERE id = ?", id) {
            ItemFiles(it.getString("audio_path"), it.getString("cover_path"))
        }
    }

    suspend fun list(userId: Long, q: ItemQuery): ItemPage = db.tx {
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
        val whereSql = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        val order = when (q.sort) {
            ItemSort.TITLE -> "i.title COLLATE NOCASE, i.id"
            ItemSort.ADDED -> "i.added_at DESC, i.id DESC"
            ItemSort.RANDOM -> "RANDOM()"
        }
        val total = queryOne(
            "SELECT COUNT(*) FROM items i $FAVORITE_JOIN $whereSql", userId, *args.toTypedArray(),
        ) { it.getInt(1) }!!
        val items = query(
            "$SELECT_ITEM $whereSql ORDER BY $order LIMIT ? OFFSET ?",
            userId, *args.toTypedArray(), q.pageSize, q.page * q.pageSize,
        ) { it.toItem() }.map { it.withTags(this) }
        ItemPage(items, total, q.page, q.pageSize)
    }

    suspend fun update(id: Long, title: String?, creator: String?, tags: List<String>?): Boolean = db.tx {
        val exists = queryOne("SELECT 1 FROM items WHERE id = ?", id) { true } != null
        if (exists) {
            title?.let { update("UPDATE items SET title = ? WHERE id = ?", it, id) }
            creator?.let { update("UPDATE items SET creator = ? WHERE id = ?", it.ifBlank { null }, id) }
            tags?.let { setTags(id, it) }
        }
        exists
    }

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

    suspend fun tags(): List<TagDto> = db.tx {
        query("SELECT tag, COUNT(*) AS n FROM item_tags GROUP BY tag COLLATE NOCASE ORDER BY n DESC, tag") {
            TagDto(it.getString("tag"), it.getInt("n"))
        }
    }

    private fun Connection.setTags(itemId: Long, tags: List<String>) {
        update("DELETE FROM item_tags WHERE item_id = ?", itemId)
        tags.map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase).forEach {
            update("INSERT INTO item_tags (item_id, tag) VALUES (?, ?)", itemId, it)
        }
    }

    private fun ItemDto.withTags(connection: Connection) = copy(
        tags = connection.query("SELECT tag FROM item_tags WHERE item_id = ? ORDER BY tag", id) { it.getString(1) },
    )

    private fun ResultSet.toItem() = ItemDto(
        id = getLong("id"),
        title = getString("title"),
        creator = getString("creator"),
        durationSeconds = getDoubleOrNull("duration_seconds"),
        tags = emptyList(),
        hasCover = getString("cover_path") != null,
        isFavorite = getLongOrNull("fav_item") != null,
        sourceUrl = getString("source_url"),
        addedAt = getLong("added_at"),
    )

    private companion object {
        /** Der erste Parameter ist immer die Benutzer-ID für den Favoriten-Join. */
        const val FAVORITE_JOIN = "LEFT JOIN favorites f ON f.item_id = i.id AND f.user_id = ?"
        const val SELECT_ITEM = "SELECT i.*, f.item_id AS fav_item FROM items i $FAVORITE_JOIN"
    }
}
