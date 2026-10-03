package de.axelcypher.asmr.server.db

import de.axelcypher.asmr.api.ImportJobDto
import de.axelcypher.asmr.api.ImportStatus
import java.sql.ResultSet

class ImportStore(private val db: Database, private val now: () -> Long = System::currentTimeMillis) {

    suspend fun create(url: String, userId: Long): ImportJobDto = db.tx {
        val id = insert(
            "INSERT INTO import_jobs (url, status, requested_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            url, ImportStatus.QUEUED.name, userId, now(), now(),
        )
        queryOne("SELECT * FROM import_jobs WHERE id = ?", id) { it.toJob() }!!
    }

    suspend fun get(id: Long): ImportJobDto? =
        db.tx { queryOne("SELECT * FROM import_jobs WHERE id = ?", id) { it.toJob() } }

    suspend fun recent(limit: Int = 50): List<ImportJobDto> =
        db.tx { query("SELECT * FROM import_jobs ORDER BY id DESC LIMIT ?", limit) { it.toJob() } }

    /** Jobs, die beim letzten Herunterfahren noch offen waren. */
    suspend fun unfinished(): List<ImportJobDto> = db.tx {
        query(
            "SELECT * FROM import_jobs WHERE status IN (?, ?) ORDER BY id",
            ImportStatus.QUEUED.name, ImportStatus.RUNNING.name,
        ) { it.toJob() }
    }

    suspend fun setStatus(id: Long, status: ImportStatus, itemId: Long? = null, error: String? = null) = db.tx {
        update(
            "UPDATE import_jobs SET status = ?, item_id = ?, error = ?, updated_at = ? WHERE id = ?",
            status.name, itemId, error, now(), id,
        )
    }

    private fun ResultSet.toJob() = ImportJobDto(
        id = getLong("id"),
        url = getString("url"),
        status = ImportStatus.valueOf(getString("status")),
        itemId = getLongOrNull("item_id"),
        error = getString("error"),
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"),
    )
}
