package de.axelcypher.asmr.server.db

import de.axelcypher.asmr.api.ApiJson
import de.axelcypher.asmr.api.CreatorDto
import de.axelcypher.asmr.api.CreatorLinkDto
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.net.URI

/** Profil-Links und Profilbild pro Creator; der Name entspricht dem Creator der Items. */
class CreatorStore(private val db: Database) {

    suspend fun get(name: String): CreatorDto = db.tx {
        queryOne("SELECT * FROM creators WHERE name = ?", name) {
            CreatorDto(
                name = name,
                links = ApiJson.decodeFromString(STRINGS, it.getString("links")).map(::link),
                hasAvatar = it.getString("avatar_path") != null,
            )
        } ?: CreatorDto(name, emptyList(), hasAvatar = false)
    }

    suspend fun avatarPath(name: String): String? =
        db.tx { queryOne("SELECT avatar_path FROM creators WHERE name = ?", name) { it.getString(1) } }

    suspend fun setLinks(name: String, links: List<String>) = db.tx {
        ensure(name)
        val clean = links.map(String::trim).filter(String::isNotEmpty).distinct()
        update("UPDATE creators SET links = ? WHERE name = ?", ApiJson.encodeToString(STRINGS, clean), name)
    }

    suspend fun setAvatar(name: String, avatarPath: String?) = db.tx {
        ensure(name)
        update("UPDATE creators SET avatar_path = ? WHERE name = ?", avatarPath, name)
    }

    private fun java.sql.Connection.ensure(name: String) =
        update("INSERT OR IGNORE INTO creators (name) VALUES (?)", name)

    companion object {
        private val STRINGS = ListSerializer(String.serializer())

        private val KNOWN = listOf(
            "youtube.com" to "YouTube", "youtu.be" to "YouTube", "patreon.com" to "Patreon",
            "fansly.com" to "Fansly", "onlyfans.com" to "OnlyFans", "twitch.tv" to "Twitch",
            "tiktok.com" to "TikTok", "instagram.com" to "Instagram", "x.com" to "X", "twitter.com" to "X",
            "ko-fi.com" to "Ko-fi", "soundcloud.com" to "SoundCloud", "spotify.com" to "Spotify",
            "linktr.ee" to "Linktree", "reddit.com" to "Reddit",
        )

        /** Bezeichnung aus der Domain, z.B. "Patreon"; unbekannte Seiten zeigen den Hostnamen. */
        fun link(url: String): CreatorLinkDto {
            val host = runCatching { URI(url).host }.getOrNull()?.lowercase()?.removePrefix("www.") ?: url
            val label = KNOWN.firstOrNull { (domain, _) -> host == domain || host.endsWith(".$domain") }?.second ?: host
            return CreatorLinkDto(url, label)
        }
    }
}
