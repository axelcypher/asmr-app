package de.axelcypher.asmr.server.db

import de.axelcypher.asmr.api.TriggerEntryDto
import de.axelcypher.asmr.api.TriggerGroupAdminDto
import de.axelcypher.asmr.api.TriggerGroupDto

/**
 * Katalog der Bewertungsmatrix: Gruppen mit Reglern (Triggern). Die Werte an den Tracks hängen am
 * Trigger-Namen; Umbenennen zieht der Aufrufer in den Tracks nach (siehe [ItemStore.renameTag]).
 */
class TriggerCatalogStore(private val db: Database) {

    /** Für App und Editor: Gruppen mit Trigger-Namen in Reihenfolge. */
    suspend fun catalog(): List<TriggerGroupDto> = admin().map { group -> TriggerGroupDto(group.name, group.triggers.map { it.name }) }

    /** Für die Verwaltung: mit IDs und wie viele Tracks den Trigger nutzen. */
    suspend fun admin(): List<TriggerGroupAdminDto> = db.tx {
        val usage = query("SELECT tag, COUNT(*) FROM item_tags GROUP BY tag COLLATE NOCASE") { it.getString(1).lowercase() to it.getInt(2) }.toMap()
        val triggers = query("SELECT * FROM triggers ORDER BY position, name COLLATE NOCASE") {
            TriggerEntryDto(it.getLong("id"), it.getString("name"), it.getLong("group_id"), usage[it.getString("name").lowercase()] ?: 0)
        }.groupBy { it.groupId }
        query("SELECT * FROM trigger_groups ORDER BY position, name COLLATE NOCASE") {
            TriggerGroupAdminDto(it.getLong("id"), it.getString("name"), triggers[it.getLong("id")].orEmpty())
        }
    }

    suspend fun trigger(id: Long): TriggerEntryDto? = admin().flatMap { it.triggers }.firstOrNull { it.id == id }

    suspend fun groupExists(id: Long) = db.tx { queryOne("SELECT 1 FROM trigger_groups WHERE id = ?", id) { true } != null }

    /** Name schon vergeben (Gruppen bzw. Trigger, Groß/klein egal)? */
    suspend fun groupNameTaken(name: String, except: Long? = null) = db.tx {
        queryOne("SELECT id FROM trigger_groups WHERE name = ?", name) { it.getLong(1) }?.let { it != except } ?: false
    }

    suspend fun triggerNameTaken(name: String, except: Long? = null) = db.tx {
        queryOne("SELECT id FROM triggers WHERE name = ?", name) { it.getLong(1) }?.let { it != except } ?: false
    }

    suspend fun createGroup(name: String): Long = db.tx {
        val position = queryOne("SELECT COALESCE(MAX(position), -1) + 1 FROM trigger_groups") { it.getInt(1) }!!
        insert("INSERT INTO trigger_groups (name, position) VALUES (?, ?)", name, position)
    }

    suspend fun renameGroup(id: Long, name: String) = db.tx { update("UPDATE trigger_groups SET name = ? WHERE id = ?", name, id) }

    /** Löscht die Gruppe samt ihren Reglern; Werte an Tracks bleiben als eigene Trigger erhalten. */
    suspend fun deleteGroup(id: Long) = db.tx { update("DELETE FROM trigger_groups WHERE id = ?", id) }

    suspend fun reorderGroups(ids: List<Long>) = db.tx {
        ids.forEachIndexed { index, id -> update("UPDATE trigger_groups SET position = ? WHERE id = ?", index, id) }
    }

    suspend fun createTrigger(groupId: Long, name: String): Long = db.tx {
        val position = queryOne("SELECT COALESCE(MAX(position), -1) + 1 FROM triggers WHERE group_id = ?", groupId) { it.getInt(1) }!!
        insert("INSERT INTO triggers (group_id, name, position) VALUES (?, ?, ?)", groupId, name, position)
    }

    suspend fun updateTrigger(id: Long, name: String?, groupId: Long?) = db.tx {
        name?.let { update("UPDATE triggers SET name = ? WHERE id = ?", it, id) }
        groupId?.let {
            val position = queryOne("SELECT COALESCE(MAX(position), -1) + 1 FROM triggers WHERE group_id = ?", it) { r -> r.getInt(1) }!!
            update("UPDATE triggers SET group_id = ?, position = ? WHERE id = ?", it, position, id)
        }
    }

    suspend fun deleteTrigger(id: Long) = db.tx { update("DELETE FROM triggers WHERE id = ?", id) }

    suspend fun reorderTriggers(ids: List<Long>) = db.tx {
        ids.forEachIndexed { index, id -> update("UPDATE triggers SET position = ? WHERE id = ?", index, id) }
    }
}
