package de.axelcypher.asmr.server.db

import de.axelcypher.asmr.api.PlaylistDto
import java.sql.ResultSet

data class PlaylistRecord(val id: Long, val ownerId: Long, val ownerName: String, val name: String, val shared: Boolean)

/** Playlists gehören einem Benutzer; geteilte sehen alle (nur der Besitzer ändert sie). */
class PlaylistStore(private val db: Database, private val now: () -> Long = System::currentTimeMillis) {

    /** Eigene und geteilte Playlists anderer. */
    suspend fun visibleTo(userId: Long): List<PlaylistRecord> = db.tx {
        query(
            """SELECT p.*, u.username FROM playlists p JOIN users u ON u.id = p.owner_id
               WHERE p.owner_id = ? OR p.shared = 1 ORDER BY p.owner_id <> ?, p.name COLLATE NOCASE""",
            userId, userId,
        ) { it.toRecord() }
    }

    suspend fun get(id: Long): PlaylistRecord? = db.tx {
        queryOne("SELECT p.*, u.username FROM playlists p JOIN users u ON u.id = p.owner_id WHERE p.id = ?", id) { it.toRecord() }
    }

    suspend fun create(ownerId: Long, name: String): Long = db.tx {
        insert("INSERT INTO playlists (owner_id, name, created_at) VALUES (?, ?, ?)", ownerId, name.trim(), now())
    }

    suspend fun update(id: Long, name: String?, shared: Boolean?) = db.tx {
        name?.let { update("UPDATE playlists SET name = ? WHERE id = ?", it.trim(), id) }
        shared?.let { update("UPDATE playlists SET shared = ? WHERE id = ?", it, id) }
    }

    suspend fun delete(id: Long) = db.tx { update("DELETE FROM playlists WHERE id = ?", id) }

    /** Track-IDs in Reihenfolge. */
    suspend fun itemIds(id: Long): List<Long> = db.tx {
        query("SELECT item_id FROM playlist_items WHERE playlist_id = ? ORDER BY position", id) { it.getLong(1) }
    }

    suspend fun add(id: Long, itemId: Long) = db.tx {
        val position = queryOne("SELECT COALESCE(MAX(position), 0) + 1 FROM playlist_items WHERE playlist_id = ?", id) { it.getInt(1) }!!
        update("INSERT OR IGNORE INTO playlist_items (playlist_id, item_id, position) VALUES (?, ?, ?)", id, itemId, position)
    }

    suspend fun remove(id: Long, itemId: Long) =
        db.tx { update("DELETE FROM playlist_items WHERE playlist_id = ? AND item_id = ?", id, itemId) }

    fun toDto(record: PlaylistRecord, viewerId: Long, visibleItemIds: List<Long>, coverItemId: Long?) = PlaylistDto(
        id = record.id,
        name = record.name,
        ownerName = record.ownerName,
        isMine = record.ownerId == viewerId,
        shared = record.shared,
        itemCount = visibleItemIds.size,
        coverItemId = coverItemId,
    )

    private fun ResultSet.toRecord() = PlaylistRecord(
        id = getLong("id"),
        ownerId = getLong("owner_id"),
        ownerName = getString("username"),
        name = getString("name"),
        shared = getInt("shared") == 1,
    )
}
