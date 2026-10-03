package de.axelcypher.asmr.server.db

import de.axelcypher.asmr.api.CategoryDto
import de.axelcypher.asmr.api.CategoryRequest

/** Rohdaten einer Kategorie; [ItemStore] löst daraus die Tracks auf. */
data class CategoryMembers(val itemIds: Set<Long>, val folders: List<String>)

/**
 * Frei definierte Kategorien. Zugeordnet werden einzelne Tracks oder Ordner (samt Unterordnern);
 * ein Track kann in mehreren Kategorien stecken.
 */
class CategoryStore(private val db: Database) : FolderPathOwner {

    suspend fun list(): List<CategoryDto> = db.tx {
        query("SELECT * FROM categories ORDER BY position, name COLLATE NOCASE") {
            CategoryDto(
                it.getLong("id"), it.getString("name"), it.getString("icon"), it.getString("color"),
                isAmbient = it.getInt("is_ambient") == 1,
                imageVersion = it.getString("image")?.substringAfterLast('/')?.substringBeforeLast('.'),
            )
        }
    }

    suspend fun get(id: Long): CategoryDto? = list().firstOrNull { it.id == id }

    suspend fun create(request: CategoryRequest): CategoryDto = db.tx {
        val position = queryOne("SELECT COALESCE(MAX(position), 0) + 1 FROM categories") { it.getInt(1) }!!
        val id = insert(
            "INSERT INTO categories (name, icon, color, position, is_ambient) VALUES (?, ?, ?, ?, ?)",
            request.name.trim(), request.icon, request.color, position, request.isAmbient,
        )
        CategoryDto(id, request.name.trim(), request.icon, request.color, isAmbient = request.isAmbient)
    }

    suspend fun update(id: Long, request: CategoryRequest) = db.tx {
        update(
            "UPDATE categories SET name = ?, icon = ?, color = ?, is_ambient = ? WHERE id = ?",
            request.name.trim(), request.icon, request.color, request.isAmbient, id,
        )
    }

    suspend fun delete(id: Long) = db.tx { update("DELETE FROM categories WHERE id = ?", id) }

    /** Gespeicherter Pfad des eigenen Bildes (siehe [de.axelcypher.asmr.server.Services.storedFile]). */
    suspend fun imagePath(id: Long): String? = db.tx {
        queryOne("SELECT image FROM categories WHERE id = ?", id) { it.getString(1) }
    }

    suspend fun setImage(id: Long, path: String?) = db.tx { update("UPDATE categories SET image = ? WHERE id = ?", path, id) }

    /**
     * Neue Reihenfolge der genannten Kategorien. Sie übernehmen die Plätze, die sie bisher belegt haben;
     * alle anderen (z.B. die jeweils andere Art, Ambiente oder normal) bleiben, wo sie sind.
     */
    suspend fun reorder(ids: List<Long>) = db.tx {
        val current = query("SELECT id FROM categories ORDER BY position, name COLLATE NOCASE") { it.getLong(1) }
        val wanted = ids.filter { it in current }.distinct()
        val queue = ArrayDeque(wanted)
        current.map { if (it in wanted) queue.removeFirst() else it }
            .forEachIndexed { index, id -> update("UPDATE categories SET position = ? WHERE id = ?", index, id) }
    }

    suspend fun members(id: Long): CategoryMembers = db.tx {
        CategoryMembers(
            itemIds = query("SELECT item_id FROM category_items WHERE category_id = ?", id) { it.getLong(1) }.toSet(),
            folders = query("SELECT path FROM category_folders WHERE category_id = ? ORDER BY path", id) { it.getString(1) },
        )
    }

    suspend fun setItem(id: Long, itemId: Long, member: Boolean) = db.tx {
        if (member) {
            update("INSERT OR IGNORE INTO category_items (category_id, item_id) VALUES (?, ?)", id, itemId)
        } else {
            update("DELETE FROM category_items WHERE category_id = ? AND item_id = ?", id, itemId)
        }
    }

    suspend fun setFolder(id: Long, path: String, member: Boolean) = db.tx {
        if (member) {
            update("INSERT OR IGNORE INTO category_folders (category_id, path) VALUES (?, ?)", id, path)
        } else {
            update("DELETE FROM category_folders WHERE category_id = ? AND path = ?", id, path)
        }
    }

    override suspend fun renameFolder(from: String, to: String) = db.tx {
        query("SELECT category_id, path FROM category_folders") { it.getLong(1) to it.getString(2) }
            .forEach { (category, path) ->
                renamedPath(path, from, to)?.let {
                    update("UPDATE OR REPLACE category_folders SET path = ? WHERE category_id = ? AND path = ?", it, category, path)
                }
            }
    }
}

/** Ordner, deren Tracks als Ambiente gelten. */
class AmbientFolderStore(private val db: Database) : FolderPathOwner {

    suspend fun list(): List<String> = db.tx { query("SELECT path FROM ambient_folders ORDER BY path") { it.getString(1) } }

    suspend fun set(path: String, ambient: Boolean) = db.tx {
        if (ambient) update("INSERT OR IGNORE INTO ambient_folders (path) VALUES (?)", path)
        else update("DELETE FROM ambient_folders WHERE path = ?", path)
    }

    override suspend fun renameFolder(from: String, to: String) = db.tx {
        query("SELECT path FROM ambient_folders") { it.getString(1) }.forEach { path ->
            renamedPath(path, from, to)?.let { update("UPDATE OR REPLACE ambient_folders SET path = ? WHERE path = ?", it, path) }
        }
    }
}
