package de.axelcypher.asmr.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON-Einstellungen, die App und Server gleich verwenden. */
val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** Redirect-URI, unter der die App das Ergebnis des SSO-Logins entgegennimmt. */
const val APP_SSO_REDIRECT = "de.axelcypher.asmr://sso"

// --- Auth ---------------------------------------------------------------------------------------

@Serializable
data class AuthConfigDto(
    val passwordLogin: Boolean,
    val sso: SsoConfigDto?,
)

@Serializable
data class SsoConfigDto(val providerName: String)

@Serializable
data class LoginRequest(val username: String, val password: String)

/** Tauscht den Einmal-Code aus dem SSO-Redirect gegen ein Session-Token (PKCE zwischen App und Server). */
@Serializable
data class SsoExchangeRequest(val code: String, val codeVerifier: String)

@Serializable
data class LoginResponse(val token: String, val user: UserDto)

@Serializable
data class UserDto(
    val id: Long,
    val username: String,
    val displayName: String?,
    val isAdmin: Boolean,
    val hasPassword: Boolean,
)

@Serializable
data class CreateUserRequest(val username: String, val password: String, val isAdmin: Boolean = false)

@Serializable
data class ChangePasswordRequest(val currentPassword: String? = null, val newPassword: String)

// --- Bibliothek ---------------------------------------------------------------------------------

@Serializable
data class ItemDto(
    val id: Long,
    val title: String,
    val creator: String?,
    val durationSeconds: Double?,
    val tags: List<String>,
    val hasCover: Boolean,
    val isFavorite: Boolean,
    val sourceUrl: String?,
    val addedAt: Long,
)

@Serializable
data class ItemPage(val items: List<ItemDto>, val total: Int, val page: Int, val pageSize: Int)

@Serializable
enum class ItemSort { TITLE, ADDED, RANDOM }

/** Nur gesetzte Felder werden geändert. */
@Serializable
data class UpdateItemRequest(val title: String? = null, val creator: String? = null, val tags: List<String>? = null)

@Serializable
data class TagDto(val name: String, val count: Int)

// --- Import -------------------------------------------------------------------------------------

@Serializable
data class ImportRequest(val url: String)

@Serializable
enum class ImportStatus { QUEUED, RUNNING, DONE, FAILED }

@Serializable
data class ImportJobDto(
    val id: Long,
    val url: String,
    val status: ImportStatus,
    val itemId: Long?,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

// --- Fehler -------------------------------------------------------------------------------------

@Serializable
data class ErrorDto(val error: String)
