package de.axelcypher.asmr.server.db

import de.axelcypher.asmr.api.UserDto
import de.axelcypher.asmr.server.auth.Passwords
import de.axelcypher.asmr.server.auth.randomToken
import de.axelcypher.asmr.server.auth.sha256Hex
import java.sql.Connection
import java.sql.ResultSet
import kotlin.time.Duration.Companion.days

data class UserRecord(
    val id: Long,
    val username: String,
    val displayName: String?,
    val email: String?,
    val passwordHash: String?,
    val isAdmin: Boolean,
    val oidcSubject: String?,
) {
    fun toDto() = UserDto(id, username, displayName, isAdmin, hasPassword = passwordHash != null)
}

/** Konten und Sessions. Session-Tokens werden nur gehasht gespeichert. */
class UserStore(private val db: Database, private val now: () -> Long = System::currentTimeMillis) {

    suspend fun count(): Int = db.tx { queryOne("SELECT COUNT(*) FROM users") { it.getInt(1) }!! }

    suspend fun list(): List<UserRecord> = db.tx { query("SELECT * FROM users ORDER BY username") { it.toUser() } }

    suspend fun byId(id: Long): UserRecord? = db.tx { userById(id) }

    suspend fun byUsername(username: String): UserRecord? =
        db.tx { queryOne("SELECT * FROM users WHERE username = ?", username) { it.toUser() } }

    suspend fun bySubject(subject: String): UserRecord? =
        db.tx { queryOne("SELECT * FROM users WHERE oidc_subject = ?", subject) { it.toUser() } }

    suspend fun unlinkedByEmail(email: String): UserRecord? = db.tx {
        queryOne("SELECT * FROM users WHERE email = ? AND oidc_subject IS NULL", email) { it.toUser() }
    }

    suspend fun create(
        username: String,
        password: String?,
        isAdmin: Boolean,
        displayName: String? = null,
        email: String? = null,
        oidcSubject: String? = null,
    ): UserRecord = db.tx {
        val id = insert(
            """INSERT INTO users (username, display_name, email, password_hash, is_admin, oidc_subject, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?)""",
            freeUsername(username), displayName, email, password?.let(Passwords::hash), isAdmin, oidcSubject, now(),
        )
        userById(id)!!
    }

    suspend fun linkSubject(userId: Long, subject: String) =
        db.tx { update("UPDATE users SET oidc_subject = ? WHERE id = ?", subject, userId) }

    suspend fun updateFromSso(userId: Long, displayName: String?, email: String?, isAdmin: Boolean?) = db.tx {
        update(
            "UPDATE users SET display_name = COALESCE(?, display_name), email = COALESCE(?, email) WHERE id = ?",
            displayName, email, userId,
        )
        if (isAdmin != null) update("UPDATE users SET is_admin = ? WHERE id = ?", isAdmin, userId)
    }

    suspend fun setPassword(userId: Long, password: String) =
        db.tx { update("UPDATE users SET password_hash = ? WHERE id = ?", Passwords.hash(password), userId) }

    suspend fun delete(userId: Long) = db.tx { update("DELETE FROM users WHERE id = ?", userId) }

    suspend fun createSession(userId: Long): String {
        val token = randomToken()
        db.tx {
            insert(
                "INSERT INTO sessions (token_hash, user_id, created_at, last_used_at) VALUES (?, ?, ?, ?)",
                sha256Hex(token), userId, now(), now(),
            )
        }
        return token
    }

    /** Liefert den Benutzer zu einem gültigen Token; Sessions laufen nach [SESSION_IDLE] ohne Nutzung ab. */
    suspend fun userForToken(token: String): UserRecord? = db.tx {
        val hash = sha256Hex(token)
        val userId = queryOne(
            "SELECT user_id FROM sessions WHERE token_hash = ? AND last_used_at > ?",
            hash, now() - SESSION_IDLE.inWholeMilliseconds,
        ) { it.getLong(1) } ?: return@tx null
        update("UPDATE sessions SET last_used_at = ? WHERE token_hash = ?", now(), hash)
        userById(userId)
    }

    suspend fun deleteSession(token: String) =
        db.tx { update("DELETE FROM sessions WHERE token_hash = ?", sha256Hex(token)) }

    private fun Connection.userById(id: Long) =
        queryOne("SELECT * FROM users WHERE id = ?", id) { it.toUser() }

    /** Hängt bei Kollisionen (z.B. SSO-Benutzername existiert lokal schon) eine Zahl an. */
    private fun Connection.freeUsername(wanted: String): String {
        val base = wanted.trim().ifEmpty { "user" }
        var candidate = base
        var n = 2
        while (queryOne("SELECT 1 FROM users WHERE username = ?", candidate) { true } != null) {
            candidate = "$base$n"
            n++
        }
        return candidate
    }

    private fun ResultSet.toUser() = UserRecord(
        id = getLong("id"),
        username = getString("username"),
        displayName = getString("display_name"),
        email = getString("email"),
        passwordHash = getString("password_hash"),
        isAdmin = getInt("is_admin") == 1,
        oidcSubject = getString("oidc_subject"),
    )

    companion object {
        val SESSION_IDLE = 180.days
    }
}
