package de.axelcypher.asmr.server.db

import de.axelcypher.asmr.api.ApiJson
import de.axelcypher.asmr.api.FolderAccessDto
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Zugriffsregeln pro Ordner. Ein Ordner mit Regel ist samt Unterordnern nur für die genannten
 * SSO-Gruppen und Benutzer sichtbar; ohne Regel sehen ihn alle. Admins sehen immer alles.
 * Verschachtelte Regeln gelten zusätzlich: wer den Elternordner nicht sieht, sieht auch keinen Unterordner.
 */
class FolderAccessStore(private val db: Database) {

    suspend fun rules(): List<FolderAccessDto> = db.tx {
        query("SELECT * FROM folder_access ORDER BY path") {
            FolderAccessDto(
                path = it.getString("path"),
                groups = ApiJson.decodeFromString(STRINGS, it.getString("groups")),
                userIds = ApiJson.decodeFromString(LONGS, it.getString("user_ids")),
            )
        }
    }

    suspend fun set(rule: FolderAccessDto) = db.tx {
        update(
            "INSERT OR REPLACE INTO folder_access (path, groups, user_ids) VALUES (?, ?, ?)",
            rule.path,
            ApiJson.encodeToString(STRINGS, rule.groups.map(String::trim).filter(String::isNotEmpty).distinct()),
            ApiJson.encodeToString(LONGS, rule.userIds.distinct()),
        )
    }

    suspend fun remove(path: String) = db.tx { update("DELETE FROM folder_access WHERE path = ?", path) }

    /** Ordnerpfade, die [user] nicht sehen darf. */
    suspend fun hiddenFor(user: UserRecord): List<String> =
        if (user.isAdmin) emptyList() else rules().filterNot { allows(it, user) }.map { it.path }

    suspend fun viewer(user: UserRecord) = Viewer(user.id, hiddenFor(user))

    private fun allows(rule: FolderAccessDto, user: UserRecord) =
        user.id in rule.userIds || rule.groups.any { group -> user.groups.any { it.equals(group, ignoreCase = true) } }

    private companion object {
        val STRINGS = ListSerializer(String.serializer())
        val LONGS = ListSerializer(Long.serializer())
    }
}
