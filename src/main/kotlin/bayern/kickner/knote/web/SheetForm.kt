package bayern.kickner.knote.web

import bayern.kickner.knote.config.MAX_SHEET_PASSWORD_LENGTH
import bayern.kickner.knote.storage.MAX_PATH_LENGTH
import bayern.kickner.knote.storage.isValidSheetPath
import io.ktor.http.Parameters

/**
 * Protection choice in the edit form. A protected sheet offers [KEEP], [CHANGE] and [REMOVE], an unprotected one
 * [LEAVE_OPEN] and [SET]. A new sheet has no choice: a filled password field protects it.
 *
 * @property formValue Value of the radio button.
 * @property label Text next to the radio button.
 */
enum class ProtectionChoice(val formValue: String, val label: String) {
    KEEP("keep", "unverändert"),
    CHANGE("change", "neues Passwort"),
    REMOVE("remove", "Schutz entfernen"),
    LEAVE_OPEN("none", "ungeschützt lassen"),
    SET("set", "Passwort setzen");

    companion object {
        /** Choices for a protected sheet, the first one is preselected. */
        val forProtected = listOf(KEEP, CHANGE, REMOVE)

        /** Choices for an unprotected sheet, the first one is preselected. */
        val forUnprotected = listOf(LEAVE_OPEN, SET)

        /** The choice with [value] as [formValue], or null. */
        fun fromForm(value: String?): ProtectionChoice? = entries.firstOrNull { it.formValue == value }
    }
}

/** Name of the editor's submit button that asks for a preview. */
const val ACTION_FIELD = "action"

/** Value of [ACTION_FIELD] for a preview. Any other value, or none, saves. */
const val PREVIEW_ACTION = "preview"

/**
 * The editor fields as submitted. Path and title are trimmed. Markdown line breaks are normalized to `\n`,
 * because browsers send a textarea with `\r\n`.
 *
 * @property updated Optimistic lock of an edit, null for a new sheet or a damaged form.
 * @property currentPassword Hidden field of a protected sheet. Comes from the browser and is checked before use.
 * @property preview True if the preview button was used instead of the save button.
 */
data class SheetForm(
    val path: String,
    val title: String,
    val markdown: String,
    val password: String,
    val passwordRepeat: String,
    val protection: ProtectionChoice?,
    val updated: Long?,
    val currentPassword: String?,
    val preview: Boolean = false,
)

/** Reads the editor fields from the posted form. Missing fields count as empty. */
fun Parameters.toSheetForm(): SheetForm = SheetForm(
    path = this["path"].orEmpty().trim(),
    title = this["title"].orEmpty().trim(),
    markdown = this["markdown"].orEmpty().replace("\r\n", "\n"),
    password = this["password"].orEmpty(),
    passwordRepeat = this["passwordRepeat"].orEmpty(),
    protection = ProtectionChoice.fromForm(this["protection"]),
    updated = this["updated"]?.toLongOrNull(),
    currentPassword = this["currentPassword"],
    preview = this[ACTION_FIELD] == PREVIEW_ACTION,
)

/** Checks path, title and Markdown size. The messages are German, they are shown in the form. */
fun validateFields(form: SheetForm, maxSheetBytes: Int): List<String> {
    val issues = mutableListOf<String>()
    if (isValidSheetPath(form.path).not())
        issues += "Pfad ungültig: erlaubt sind a bis z, 0 bis 9 und Bindestrich, Abschnitte durch / getrennt, höchstens $MAX_PATH_LENGTH Zeichen"
    if (form.title.length !in 1..MAX_TITLE_LENGTH) issues += "Titel 1 bis $MAX_TITLE_LENGTH Zeichen"
    if (form.title.any { it.isISOControl() }) issues += "Titel darf keine Steuerzeichen enthalten"
    issues += validateMarkdownSize(form.markdown, maxSheetBytes)
    return issues
}

/** Checks the size of [markdown] in UTF-8 bytes. Also used before a preview is rendered. */
fun validateMarkdownSize(markdown: String, maxSheetBytes: Int): List<String> =
    if (markdown.encodeToByteArray().size > maxSheetBytes) listOf("Inhalt zu groß (höchstens $maxSheetBytes Bytes)") else emptyList()

/** Checks a new sheet password and its repetition. The messages are German, they are shown in the form. */
fun validateNewPassword(password: String, repeat: String, minLength: Int): List<String> {
    val issues = mutableListOf<String>()
    if (password.length < minLength) issues += "Passwort mindestens $minLength Zeichen"
    if (password.length > MAX_SHEET_PASSWORD_LENGTH) issues += "Passwort höchstens $MAX_SHEET_PASSWORD_LENGTH Zeichen"
    if (password != repeat) issues += "Passwörter stimmen nicht überein"
    return issues
}
