package bayern.kickner.knote.storage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Allowed sheet paths: lowercase segments of a to z, 0 to 9 and hyphen, separated by single slashes. */
val SHEET_PATH_REGEX = Regex("^[a-z0-9-]+(/[a-z0-9-]+)*$")

/** Maximum length of a sheet path in characters. */
const val MAX_PATH_LENGTH = 128

/**
 * True if [path] is a valid sheet path. System paths start with `_` (`/_admin`, `/_auth`, `/_static`), which the
 * regex never allows, so a sheet can never shadow them.
 */
fun isValidSheetPath(path: String): Boolean = path.length <= MAX_PATH_LENGTH && SHEET_PATH_REGEX.matches(path)

/**
 * One sheet as stored on disk, one JSON file per sheet named after [id].
 *
 * @property id Random UUID, also the file name. Never changes, admin URLs use it instead of the path.
 * @property path Public address below the domain, e.g. `projekte/knote`. Rules in [isValidSheetPath].
 * @property title Plain text, never secret: the overview shows it without the password.
 * @property createdBy OIDC `sub` of the admin who created the sheet.
 * @property created Creation time as Unix milliseconds.
 * @property updated Last change as Unix milliseconds. Doubles as optimistic lock when saving an edited sheet.
 * @property enabled False hides the sheet from the public, it answers 404 but stays in the admin area. Files from
 * before this field count as enabled, and an enabled sheet does not write the field at all.
 */
@Serializable
data class Sheet(
    val id: String,
    val path: String,
    val title: String,
    val content: SheetContent,
    val createdBy: String,
    val created: Long,
    val updated: Long,
    val enabled: Boolean = true,
) {
    /** True if the content is encrypted with a sheet password. */
    val isProtected: Boolean get() = content is SheetContent.Encrypted
}

/**
 * Content of a sheet. A protected sheet can only exist encrypted. The [SerialName]s are the values of the `type`
 * field in the files, so renaming a class does not break existing files.
 */
@Serializable
sealed interface SheetContent {
    /** Unprotected Markdown, readable by everyone who knows the path. */
    @Serializable
    @SerialName("plain")
    data class Plain(val markdown: String) : SheetContent

    /** Markdown encrypted with the sheet password, stored as `AES.AESData.toString()`. */
    @Serializable
    @SerialName("encrypted")
    data class Encrypted(val aesData: String) : SheetContent
}
