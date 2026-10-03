package de.axelcypher.asmr.server.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement

/**
 * SQLite mit einer einzigen Verbindung. Schreibzugriffe laufen nacheinander (Mutex), das reicht für
 * einen privaten Server und erspart Locking-Fehler.
 */
class Database private constructor(private val connection: Connection) {

    private val mutex = Mutex()

    suspend fun <T> tx(block: Connection.() -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            connection.autoCommit = false
            try {
                connection.block().also { connection.commit() }
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }

    fun close() = connection.close()

    companion object {
        fun open(file: Path): Database = open("jdbc:sqlite:${file.toAbsolutePath()}")

        /** Für Tests: `jdbc:sqlite::memory:`. */
        fun open(url: String): Database {
            val connection = DriverManager.getConnection(url)
            connection.createStatement().use {
                it.execute("PRAGMA foreign_keys = ON")
                it.execute("PRAGMA journal_mode = WAL")
                it.execute("PRAGMA busy_timeout = 5000")
            }
            migrate(connection)
            return Database(connection)
        }

        private fun migrate(connection: Connection) {
            val version = connection.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
            }
            MIGRATIONS.drop(version).forEachIndexed { index, sql ->
                connection.createStatement().use { st ->
                    sql.split(";").map(String::trim).filter(String::isNotEmpty).forEach(st::execute)
                    st.execute("PRAGMA user_version = ${version + index + 1}")
                }
            }
        }

        /** Nur anhängen, nie bestehende Einträge ändern. */
        private val MIGRATIONS = listOf(
            """
            CREATE TABLE users (
                id INTEGER PRIMARY KEY,
                username TEXT NOT NULL UNIQUE COLLATE NOCASE,
                display_name TEXT,
                email TEXT COLLATE NOCASE,
                password_hash TEXT,
                is_admin INTEGER NOT NULL DEFAULT 0,
                oidc_subject TEXT UNIQUE,
                created_at INTEGER NOT NULL
            );
            CREATE TABLE sessions (
                token_hash TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                created_at INTEGER NOT NULL,
                last_used_at INTEGER NOT NULL
            );
            CREATE TABLE items (
                id INTEGER PRIMARY KEY,
                title TEXT NOT NULL,
                creator TEXT,
                duration_seconds REAL,
                audio_path TEXT NOT NULL,
                cover_path TEXT,
                description TEXT,
                source_url TEXT,
                source_key TEXT UNIQUE,
                added_at INTEGER NOT NULL
            );
            CREATE TABLE item_tags (
                item_id INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE,
                tag TEXT NOT NULL COLLATE NOCASE,
                PRIMARY KEY (item_id, tag)
            );
            CREATE INDEX item_tags_tag ON item_tags(tag);
            CREATE TABLE favorites (
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                item_id INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (user_id, item_id)
            );
            CREATE TABLE import_jobs (
                id INTEGER PRIMARY KEY,
                url TEXT NOT NULL,
                status TEXT NOT NULL,
                item_id INTEGER REFERENCES items(id) ON DELETE SET NULL,
                error TEXT,
                requested_by INTEGER REFERENCES users(id) ON DELETE SET NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """,
            "CREATE UNIQUE INDEX items_audio_path ON items(audio_path)",
            """
            ALTER TABLE users ADD COLUMN groups TEXT NOT NULL DEFAULT '[]';
            CREATE TABLE folder_access (
                path TEXT PRIMARY KEY,
                groups TEXT NOT NULL DEFAULT '[]',
                user_ids TEXT NOT NULL DEFAULT '[]'
            );
            CREATE TABLE creators (
                name TEXT PRIMARY KEY COLLATE NOCASE,
                links TEXT NOT NULL DEFAULT '[]',
                avatar_path TEXT
            );
            ALTER TABLE item_tags ADD COLUMN level INTEGER NOT NULL DEFAULT 5
            """,
            """
            CREATE TABLE categories (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                icon TEXT NOT NULL,
                color TEXT NOT NULL,
                position INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE category_items (
                category_id INTEGER NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
                item_id INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE,
                PRIMARY KEY (category_id, item_id)
            );
            CREATE TABLE category_folders (
                category_id INTEGER NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
                path TEXT NOT NULL,
                PRIMARY KEY (category_id, path)
            );
            ALTER TABLE items ADD COLUMN is_ambient INTEGER NOT NULL DEFAULT 0;
            CREATE TABLE ambient_folders (path TEXT PRIMARY KEY);
            CREATE TABLE playlists (
                id INTEGER PRIMARY KEY,
                owner_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                name TEXT NOT NULL,
                shared INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            );
            CREATE TABLE playlist_items (
                playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
                item_id INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE,
                position INTEGER NOT NULL,
                PRIMARY KEY (playlist_id, item_id)
            )
            """,
            """
            ALTER TABLE creators ADD COLUMN is_creator INTEGER NOT NULL DEFAULT 0;
            UPDATE creators SET is_creator = 1 WHERE avatar_path IS NOT NULL OR links <> '[]'
            """,
            """
            CREATE TABLE trigger_groups (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL UNIQUE COLLATE NOCASE,
                position INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE triggers (
                id INTEGER PRIMARY KEY,
                group_id INTEGER NOT NULL REFERENCES trigger_groups(id) ON DELETE CASCADE,
                name TEXT NOT NULL UNIQUE COLLATE NOCASE,
                position INTEGER NOT NULL DEFAULT 0
            )
            """,
            seedTriggerCatalog(),
            "ALTER TABLE categories ADD COLUMN is_ambient INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE categories ADD COLUMN image TEXT",
        )
    }
}

// Kleine JDBC-Helfer, damit die Stores lesbar bleiben.

fun Connection.update(sql: String, vararg args: Any?): Int =
    prepareStatement(sql).use { it.bind(args).executeUpdate() }

fun Connection.insert(sql: String, vararg args: Any?): Long =
    prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { st ->
        st.bind(args).executeUpdate()
        st.generatedKeys.use { it.next(); it.getLong(1) }
    }

fun <T> Connection.query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { st ->
        st.bind(args).executeQuery().use { rs ->
            buildList { while (rs.next()) add(map(rs)) }
        }
    }

fun <T> Connection.queryOne(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? =
    query(sql, *args, map = map).firstOrNull()

private fun PreparedStatement.bind(args: Array<out Any?>): PreparedStatement {
    args.forEachIndexed { i, arg -> setObject(i + 1, if (arg is Boolean) (if (arg) 1 else 0) else arg) }
    return this
}

fun ResultSet.getLongOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }
fun ResultSet.getDoubleOrNull(column: String): Double? = getDouble(column).takeUnless { wasNull() }

/** Übernimmt den bisher fest eingebauten Trigger-Katalog in die Datenbank (einmalig, per Migration). */
private fun seedTriggerCatalog(): String = buildString {
    fun quote(value: String) = "'" + value.replace("'", "''") + "'"
    de.axelcypher.asmr.api.TRIGGER_CATALOG.forEachIndexed { groupIndex, (group, triggers) ->
        append("INSERT INTO trigger_groups (id, name, position) VALUES (${groupIndex + 1}, ${quote(group)}, $groupIndex);\n")
        triggers.forEachIndexed { index, trigger ->
            append("INSERT INTO triggers (group_id, name, position) VALUES (${groupIndex + 1}, ${quote(trigger)}, $index);\n")
        }
    }
}
