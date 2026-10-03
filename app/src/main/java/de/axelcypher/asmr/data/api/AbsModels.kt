package de.axelcypher.asmr.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val AbsJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable
internal data class LoginRequest(val username: String, val password: String)

@Serializable
internal data class LoginResponse(val user: AbsUser)

/**
 * Seit ABS 2.26 liefert der Server [accessToken] und (mit `x-return-tokens: true`) [refreshToken].
 * [token] ist das alte, unbegrenzt gültige Token älterer Server.
 */
@Serializable
internal data class AbsUser(
    val id: String,
    val username: String,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val token: String? = null,
)

@Serializable
internal data class LibrariesResponse(val libraries: List<AbsLibrary>)

@Serializable
data class AbsLibrary(val id: String, val name: String, val mediaType: String)

@Serializable
internal data class LibraryItemsResponse(
    val results: List<AbsLibraryItem>,
    val total: Int,
)

@Serializable
internal data class AbsLibraryItem(val id: String, val media: AbsMedia)

@Serializable
internal data class AbsMedia(
    val metadata: AbsMetadata,
    val duration: Double? = null,
    val tags: List<String> = emptyList(),
    val coverPath: String? = null,
)

/** Bücher haben [authorName], Podcasts [author]. */
@Serializable
internal data class AbsMetadata(
    val title: String? = null,
    val authorName: String? = null,
    val author: String? = null,
)

/** Ein Eintrag der ASMR-Bibliothek, unabhängig vom ABS-Bibliothekstyp. */
data class AsmrItem(
    val id: String,
    val title: String,
    val creator: String?,
    val durationSeconds: Double?,
    val tags: List<String>,
    val hasCover: Boolean,
)

internal fun AbsLibraryItem.toAsmrItem() = AsmrItem(
    id = id,
    title = media.metadata.title ?: "Ohne Titel",
    creator = media.metadata.authorName ?: media.metadata.author,
    durationSeconds = media.duration,
    tags = media.tags,
    hasCover = media.coverPath != null,
)

data class ItemPage(val items: List<AsmrItem>, val total: Int)
