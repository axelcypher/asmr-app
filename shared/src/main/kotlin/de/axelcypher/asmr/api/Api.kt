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
    /** SSO-Gruppen vom letzten Login. */
    val groups: List<String> = emptyList(),
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
    /** Trigger mit Stärke > 0, stärkste zuerst. */
    val tags: List<String>,
    val hasCover: Boolean,
    val isFavorite: Boolean,
    val sourceUrl: String?,
    val addedAt: Long,
    /** Ordner relativ zum Medienordner, "" für die oberste Ebene. */
    val folder: String = "",
    /** Bewertungsmatrix: Trigger -> Stärke 1..10 (nur Einträge > 0). */
    val levels: Map<String, Int> = emptyMap(),
)

@Serializable
data class ItemPage(val items: List<ItemDto>, val total: Int, val page: Int, val pageSize: Int)

@Serializable
enum class ItemSort { TITLE, ADDED, RANDOM }

/** Nur gesetzte Felder werden geändert; [folder] verschiebt die Datei auf dem NAS. */
@Serializable
data class UpdateItemRequest(
    val title: String? = null,
    val creator: String? = null,
    /** Ersetzt die komplette Matrix; Stärke 0 entfernt einen Trigger. */
    val levels: Map<String, Int>? = null,
    val folder: String? = null,
)

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

// --- Ordner und Zugriff -------------------------------------------------------------------------

@Serializable
data class FolderDto(
    /** Relativ zum Medienordner, z.B. "Gibi ASMR/Roleplays". */
    val path: String,
    val name: String,
    /** Sichtbare Items in diesem Ordner und allen Unterordnern. */
    val itemCount: Int,
    val hasCover: Boolean,
    /** Für Admins: Ordner hat eine Zugriffsbeschränkung. */
    val restricted: Boolean = false,
)

@Serializable
data class FolderListing(val path: String, val folders: List<FolderDto>, val items: List<ItemDto>)

@Serializable
data class CreateFolderRequest(val path: String)

/** Ein eingeschränkter Ordner (samt Unterordnern) ist nur für diese Gruppen und Benutzer sichtbar. */
@Serializable
data class FolderAccessDto(val path: String, val groups: List<String> = emptyList(), val userIds: List<Long> = emptyList())

/** Alles, was der Admin-Dialog für Zugriffsrechte braucht. */
@Serializable
data class AccessOverviewDto(
    val rules: List<FolderAccessDto>,
    /** Bekannte SSO-Gruppen aller Benutzer. */
    val groups: List<String>,
    val users: List<UserDto>,
    val ssoEnabled: Boolean,
)

// --- Creator ------------------------------------------------------------------------------------

@Serializable
data class CreatorLinkDto(val url: String, val label: String)

@Serializable
data class CreatorDto(val name: String, val links: List<CreatorLinkDto>, val hasAvatar: Boolean)

@Serializable
data class UpdateCreatorRequest(val links: List<String>)
