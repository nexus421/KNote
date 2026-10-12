package bayern.kickner.knote.web

import bayern.kickner.knote.auth.LOGIN_PATH
import bayern.kickner.knote.auth.LOGOUT_PATH
import bayern.kickner.knote.storage.MAX_PATH_LENGTH
import bayern.kickner.knote.storage.Sheet
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotlinx.html.*

/** Path of the only stylesheet, served from `resources/static`. */
const val STYLESHEET_PATH = "/_static/knote.css"

/** Path of the admin overview. */
const val ADMIN_PATH = "/_admin"

/** Maximum title length in characters. */
const val MAX_TITLE_LENGTH = 100

/**
 * HTML `pattern` for the path field. The browser checks it before sending, the server checks it again. Browsers
 * compile it with the `v` flag, where a hyphen inside a character class must be escaped.
 */
private const val PATH_PATTERN = "[a-z0-9\\-]+(/[a-z0-9\\-]+)*"

/** A link shown below a message, e.g. back to the overview. */
data class PageLink(val href: String, val label: String)

/** What the editor form edits. */
sealed interface EditorTarget {
    /** A sheet that does not exist yet. */
    data object New : EditorTarget

    /**
     * An existing sheet.
     *
     * @property updated [Sheet.updated] when the editor was opened, sent back as optimistic lock.
     * @property currentPassword The password the sheet was unlocked with, null for an unprotected sheet. Sent back in
     * a hidden field so saving does not ask for it again. The server checks it before every save.
     */
    data class Existing(val id: String, val updated: Long, val currentPassword: String?) : EditorTarget
}

/**
 * Values shown in the editor fields.
 *
 * @property password Filled in again after a preview or a validation error. An empty field would otherwise save a
 * new sheet without protection on the next click. Admin pages are never cached.
 */
data class EditorValues(
    val path: String = "",
    val title: String = "",
    val markdown: String = "",
    val protection: ProtectionChoice? = null,
    val password: String = "",
    val passwordRepeat: String = "",
)

/**
 * Responds with a complete page: shared head, stylesheet and [content] inside `<main>`. The language is German
 * like all texts on the pages. kotlinx.html escapes every text, [markdownArticle] is the only place with raw HTML.
 */
suspend fun ApplicationCall.respondPage(status: HttpStatusCode = HttpStatusCode.OK, title: String, content: MAIN.() -> Unit) =
    respondHtml(status) {
        lang = "de"
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            title(title)
            link(rel = "stylesheet", href = STYLESHEET_PATH)
        }
        body {
            main { content() }
        }
    }

/** Responds with a page that only shows [heading], [text] and an optional [link]. Used for every error. */
suspend fun ApplicationCall.respondMessage(status: HttpStatusCode, heading: String, text: String, link: PageLink? = null) =
    respondPage(status, heading) {
        h1 { +heading }
        p { +text }
        if (link != null) p { a(href = link.href) { +link.label } }
    }

/** Answers `303 See Other` with [location]: after a successful form post the browser loads [location] with GET. */
suspend fun ApplicationCall.respondSeeOther(location: String) {
    response.header(HttpHeaders.Location, location)
    respond(HttpStatusCode.SeeOther)
}

/** The 404 page. Unknown and invalid paths look the same. */
suspend fun ApplicationCall.respondNotFound() =
    respondMessage(HttpStatusCode.NotFound, "Nicht gefunden", "Unter dieser Adresse gibt es kein Blatt.")

/** Start page for visitors without admin login. */
fun MAIN.landing() {
    h1 { +"KNote" }
    p { +"Blätter sind direkt über ihre Adresse erreichbar. Die Verwaltung braucht eine Anmeldung." }
    p { a(href = LOGIN_PATH, classes = "button") { +"Anmelden" } }
}

/** A rendered sheet. [html] must come from `renderMarkdown`. */
fun MAIN.sheetView(title: String, html: String) {
    h1 { +title }
    markdownArticle(html)
}

/** Embeds rendered Markdown. [html] must come from `renderMarkdown`, which escapes raw HTML and filters URLs. */
private fun FlowContent.markdownArticle(html: String) {
    article(classes = "markdown") { unsafe { +html } }
}

/**
 * Password form of a protected sheet. Posts the field `password` to [action].
 *
 * @param error Shown above the field, e.g. after a wrong password.
 */
fun MAIN.unlockForm(heading: String, hint: String, action: String, error: String? = null) {
    h1 { +heading }
    p { +hint }
    if (error != null) p(classes = "error") { +error }
    form(action = action, method = FormMethod.post) {
        label {
            htmlFor = "password"
            +"Passwort"
        }
        passwordInput(name = "password") {
            id = "password"
            required = true
            autoFocus = true
            autoComplete = "current-password"
        }
        div(classes = "actions") { submitInput { value = "Entsperren" } }
    }
}

/**
 * The admin overview: all sheets sorted by path, with links to edit and delete.
 *
 * @param formatTime Formats [Sheet.updated] for display.
 */
fun MAIN.overview(sheets: List<Sheet>, formatTime: (Long) -> String) {
    div(classes = "bar") {
        h1 { +"Blätter" }
        a(href = "$ADMIN_PATH/new", classes = "button") { +"Neues Blatt" }
        form(action = LOGOUT_PATH, method = FormMethod.post) { submitInput(classes = "secondary") { value = "Abmelden" } }
    }
    if (sheets.isEmpty()) {
        p { +"Noch keine Blätter." }
        return
    }
    table {
        thead {
            tr {
                th { +"Pfad" }
                th { +"Titel" }
                th { +"Schutz" }
                th { +"Geändert" }
                th { +"Aktionen" }
            }
        }
        tbody {
            sheets.forEach { sheet ->
                tr {
                    td { a(href = "/${sheet.path}") { +sheet.path } }
                    td { +sheet.title }
                    td { if (sheet.isProtected) +"geschützt" }
                    td { +formatTime(sheet.updated) }
                    td(classes = "actions") {
                        a(href = "$ADMIN_PATH/edit/${sheet.id}") { +"Bearbeiten" }
                        a(href = "$ADMIN_PATH/delete/${sheet.id}") { +"Löschen" }
                    }
                }
            }
        }
    }
}

/**
 * The form to create or edit a sheet.
 *
 * The first submit button saves, so Enter in a text field saves as before. The second one sends
 * [ACTION_FIELD]`=`[PREVIEW_ACTION] and gets the same form back with [preview] above it, nothing is saved.
 *
 * @param errors Validation messages shown above the form.
 * @param minPasswordLength Shown as hint and set as `minlength` on the password fields.
 * @param preview Rendered Markdown from `renderMarkdown`, shown above the form. Null shows no preview.
 */
fun MAIN.editor(target: EditorTarget, values: EditorValues, errors: List<String>, minPasswordLength: Int, preview: String? = null) {
    val action = when (target) {
        EditorTarget.New -> "$ADMIN_PATH/new"
        is EditorTarget.Existing -> "$ADMIN_PATH/edit/${target.id}"
    }
    h1 { +if (target == EditorTarget.New) "Neues Blatt" else "Blatt bearbeiten" }
    if (errors.isNotEmpty()) ul(classes = "error") { errors.forEach { li { +it } } }
    if (preview != null) section(classes = "preview") {
        h2 { +"Vorschau (nicht gespeichert)" }
        markdownArticle(preview)
    }

    form(action = action, method = FormMethod.post) {
        if (target is EditorTarget.Existing) {
            hiddenInput(name = "updated") { value = target.updated.toString() }
            if (target.currentPassword != null) hiddenInput(name = "currentPassword") { value = target.currentPassword }
        }
        label {
            htmlFor = "path"
            +"Pfad"
        }
        textInput(name = "path") {
            id = "path"
            required = true
            maxLength = MAX_PATH_LENGTH.toString()
            pattern = PATH_PATTERN
            value = values.path
        }
        p(classes = "hint") { +"Kleinbuchstaben a bis z, Ziffern und Bindestrich, Abschnitte durch / getrennt." }
        label {
            htmlFor = "title"
            +"Titel"
        }
        textInput(name = "title") {
            id = "title"
            required = true
            maxLength = MAX_TITLE_LENGTH.toString()
            value = values.title
        }
        label {
            htmlFor = "markdown"
            +"Inhalt (Markdown)"
        }
        textArea(rows = "20") {
            id = "markdown"
            name = "markdown"
            // The HTML parser drops one line break right after <textarea>, this one keeps a leading line break of the content
            +"\n${values.markdown}"
        }
        protectionFields(target, values, minPasswordLength)
        div(classes = "actions") {
            submitInput { value = if (target == EditorTarget.New) "Anlegen" else "Speichern" }
            button(type = ButtonType.submit, name = ACTION_FIELD, classes = "secondary") {
                value = PREVIEW_ACTION
                +"Vorschau"
            }
            a(href = ADMIN_PATH) { +"Abbrechen" }
        }
    }
}

private fun FORM.protectionFields(target: EditorTarget, values: EditorValues, minPasswordLength: Int) {
    fieldSet {
        legend { +"Schutz" }
        val choices = when {
            target !is EditorTarget.Existing -> emptyList()
            target.currentPassword != null -> ProtectionChoice.forProtected
            else -> ProtectionChoice.forUnprotected
        }
        choices.forEachIndexed { index, choice ->
            label(classes = "choice") {
                radioInput(name = "protection") {
                    value = choice.formValue
                    checked = if (values.protection == null) index == 0 else values.protection == choice
                }
                +" ${choice.label}"
            }
        }
        val hint = if (target == EditorTarget.New) "Beide Felder leer lassen für ein ungeschütztes Blatt. " else ""
        p(classes = "hint") { +"${hint}Mindestens $minPasswordLength Zeichen. Ohne Passwort ist der Inhalt verloren." }
        label {
            htmlFor = "password"
            +"Passwort"
        }
        passwordInput(name = "password") {
            id = "password"
            minLength = minPasswordLength.toString()
            autoComplete = "new-password"
            value = values.password
        }
        label {
            htmlFor = "passwordRepeat"
            +"Passwort wiederholen"
        }
        passwordInput(name = "passwordRepeat") {
            id = "passwordRepeat"
            minLength = minPasswordLength.toString()
            autoComplete = "new-password"
            value = values.passwordRepeat
        }
    }
}

/** Asks before [sheet] is deleted. The deletion itself is a POST, never a GET. */
fun MAIN.deleteConfirm(sheet: Sheet) {
    h1 { +"Blatt löschen" }
    p {
        +"Blatt "
        code { +sheet.path }
        +" (${sheet.title}) wirklich löschen?"
    }
    if (sheet.isProtected) p { +"Das Blatt ist geschützt. Nach dem Löschen ist der Inhalt endgültig verloren." }
    form(action = "$ADMIN_PATH/delete/${sheet.id}", method = FormMethod.post) {
        div(classes = "actions") {
            submitInput(classes = "danger") { value = "Löschen" }
            a(href = ADMIN_PATH) { +"Abbrechen" }
        }
    }
}
