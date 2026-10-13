package bayern.kickner.knote.web

import bayern.kickner.knote.BuildConfig
import bayern.kickner.knote.timestampFormat
import bayern.kickner.knote.auth.LOGIN_PATH
import bayern.kickner.knote.auth.LOGOUT_PATH
import bayern.kickner.knote.i18n.Texts
import bayern.kickner.knote.i18n.texts
import bayern.kickner.knote.settings.StartPageList
import bayern.kickner.knote.storage.MAX_PATH_LENGTH
import bayern.kickner.knote.storage.Sheet
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import kotlinx.html.*
import java.time.Instant
import java.time.ZoneId

/**
 * The stylesheet, the logo and the favicon, served from `resources/static`. The version in the query makes browsers
 * load them again after an update.
 */
private const val STATIC_QUERY = "?v=${BuildConfig.VERSION}"
private const val STYLESHEET_PATH = "/_static/knote.css$STATIC_QUERY"
private const val LOGO_PATH = "/_static/logo.jpg$STATIC_QUERY"
private const val FAVICON_PATH = "/_static/favicon.png$STATIC_QUERY"

/** Repository linked from the footer. */
private const val REPOSITORY_URL = "https://github.com/nexus421/KNote"

/**
 * Config values the page frame needs, kept in the application attributes under [PageSettingsKey].
 *
 * @property showPoweredBy Shows the "Powered by KNote" footer.
 * @property publicBaseUrl Builds absolute URLs for link previews.
 */
data class PageSettings(val showPoweredBy: Boolean, val publicBaseUrl: String)

/** Where the application keeps its [PageSettings]. */
val PageSettingsKey = AttributeKey<PageSettings>("KNotePageSettings")

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
     * a hidden field so saving does not ask for it again.
     * @property unlockProof Proof from the unlock that [currentPassword] is right (see `UnlockProof`). Every save of a
     * protected sheet checks it.
     */
    data class Existing(val id: String, val updated: Long, val currentPassword: String?, val unlockProof: String? = null) : EditorTarget
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
 * Responds with a complete page: shared head, a header with logo, [content] inside `<main>` and the optional
 * "Powered by" footer, in the configured language. kotlinx.html escapes every text, [markdownArticle] is the only
 * place with raw HTML.
 *
 * @param narrow Narrow card for short pages such as forms and messages.
 * @param startPage The start page: no header (it shows the logo large itself) and the class `start` on `<main>`,
 * which animates the light bar.
 */
suspend fun ApplicationCall.respondPage(
    status: HttpStatusCode = HttpStatusCode.OK,
    title: String,
    narrow: Boolean = false,
    startPage: Boolean = false,
    content: MAIN.() -> Unit,
) = respondHtml(status) {
    val settings = application.attributes.getOrNull(PageSettingsKey)
    lang = texts.htmlLang
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title(title)
        linkPreview(title, settings?.publicBaseUrl)
        link(rel = "icon", type = "image/png", href = FAVICON_PATH)
        link(rel = "stylesheet", href = STYLESHEET_PATH)
    }
    body {
        if (startPage.not()) header(classes = "site") {
            a(href = "/", classes = "brand") {
                img(src = LOGO_PATH, alt = "") {
                    width = "32"
                    height = "32"
                }
                span { +"KNote" }
            }
        }
        main(classes = listOfNotNull("narrow".takeIf { narrow }, "start".takeIf { startPage }).joinToString(" ").ifEmpty { null }) { content() }
        if (settings?.showPoweredBy != false) footer(classes = "site-footer") {
            +"${texts.poweredBy} "
            a(href = REPOSITORY_URL) { +"KNote" }
        }
    }
}

/**
 * Open Graph tags: messengers such as Signal or Telegram show title and logo when a link is shared. The image
 * needs an absolute URL, so it is left out without [publicBaseUrl].
 */
private fun HEAD.linkPreview(title: String, publicBaseUrl: String?) {
    fun property(name: String, value: String) = meta {
        attributes["property"] = name
        content = value
    }
    property("og:title", title)
    property("og:site_name", "KNote")
    property("og:type", "website")
    if (publicBaseUrl != null) property("og:image", "$publicBaseUrl$LOGO_PATH")
}

/** Responds with a page that only shows [heading], [text] and an optional [link]. Used for every error. */
suspend fun ApplicationCall.respondMessage(status: HttpStatusCode, heading: String, text: String, link: PageLink? = null) =
    respondPage(status, heading, narrow = true) {
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
    respondMessage(HttpStatusCode.NotFound, texts.notFoundHeading, texts.notFoundText)

/**
 * Start page for visitors without admin login.
 *
 * @param listed Sheets to list below the login, as chosen in the settings. Empty shows no list.
 */
fun MAIN.landing(t: Texts, listed: List<Sheet>) {
    div(classes = "hero") {
        img(src = LOGO_PATH, alt = "") {
            width = "112"
            height = "112"
        }
        h1 { +"KNote" }
        p { +t.landingText }
        p { a(href = LOGIN_PATH, classes = "button") { +t.logIn } }
    }
    if (listed.isNotEmpty()) nav(classes = "sheet-list") {
        h2 { +t.sheets }
        ul {
            listed.forEach { sheet ->
                li {
                    a(href = "/${sheet.path}") { +sheet.title }
                    if (sheet.isProtected) span(classes = "badge") { +t.protectedMarker }
                    span(classes = "muted path") { +"/${sheet.path}" }
                }
            }
        }
    }
}

/**
 * The settings page: which sheets the start page lists.
 *
 * @param saved Shows a confirmation after the redirect of a save.
 */
fun MAIN.settingsPage(t: Texts, current: StartPageList, saved: Boolean) {
    h1 { +t.settings }
    if (saved) p(classes = "success") { +t.saved }
    form(action = "$ADMIN_PATH/settings", method = FormMethod.post) {
        fieldSet {
            legend { +t.startPageListLegend }
            StartPageList.entries.forEach { choice ->
                label(classes = "choice") {
                    radioInput(name = "startPageList") {
                        value = choice.name.lowercase()
                        checked = choice == current
                    }
                    +" ${t.startPageList(choice)}"
                }
            }
            p(classes = "hint") { +t.startPageListHint }
        }
        div(classes = "actions") {
            submitInput { value = t.save }
            a(href = ADMIN_PATH) { +t.toOverview }
        }
    }
}

/** How a rendered sheet offers its Markdown as a file. */
sealed interface MarkdownDownload {
    /** A plain link, for an unprotected sheet. */
    data class Link(val href: String) : MarkdownDownload

    /** A form that posts the password again, for an unlocked protected sheet. The page is never cached. */
    data class Form(val action: String, val password: String) : MarkdownDownload
}

/**
 * A rendered sheet with the time of its last change and the Markdown download below it. [html] must come from
 * `renderMarkdown`.
 *
 * @param changed The last change, already formatted with [formatTime].
 * @param editHref Link to the editor, only for a logged in admin.
 */
fun MAIN.sheetView(t: Texts, title: String, html: String, changed: String, download: MarkdownDownload, editHref: String? = null) {
    h1 { +title }
    markdownArticle(html)
    div(classes = "sheet-meta") {
        span(classes = "muted") { +"${t.lastChanged}: $changed" }
        span(classes = "meta-actions") { sheetActions(t, download, editHref) }
    }
}

private fun FlowContent.sheetActions(t: Texts, download: MarkdownDownload, editHref: String?) {
    if (editHref != null) a(href = editHref, classes = "no-print") { +t.edit }
    when (download) {
        is MarkdownDownload.Link -> a(href = download.href, classes = "no-print") { +t.downloadMarkdown }
        is MarkdownDownload.Form -> form(action = download.action, method = FormMethod.post, classes = "no-print") {
            hiddenInput(name = "password") { value = download.password }
            button(type = ButtonType.submit, classes = "link") { +t.downloadMarkdown }
        }
    }
}

/** [millis] as `dd.MM.yyyy HH:mm:ss z` in [zone], e.g. `13.10.2026 16:20:31 CEST`. */
fun formatTime(millis: Long, zone: ZoneId): String = Instant.ofEpochMilli(millis).atZone(zone).format(timestampFormat)

/** Embeds rendered Markdown. [html] must come from `renderMarkdown`, which escapes raw HTML and filters URLs. */
private fun FlowContent.markdownArticle(html: String) {
    article(classes = "markdown") { unsafe { +html } }
}

/**
 * Password form of a protected sheet. Posts the field `password` to [action].
 *
 * @param error Shown above the field, e.g. after a wrong password.
 */
fun MAIN.unlockForm(t: Texts, heading: String, hint: String, action: String, error: String? = null) {
    h1 { +heading }
    p { +hint }
    if (error != null) p(classes = "error") { +error }
    form(action = action, method = FormMethod.post) {
        label {
            htmlFor = "password"
            +t.password
        }
        passwordInput(name = "password") {
            id = "password"
            required = true
            autoFocus = true
            autoComplete = "current-password"
        }
        div(classes = "actions") { submitInput { value = t.unlock } }
    }
}

/**
 * Sort orders of the overview, chosen with the query parameter `sort`.
 *
 * @property param Value of `sort`.
 */
enum class SheetOrder(val param: String) {
    PATH("path"),
    TITLE("title"),
    CHANGED("changed");

    /** [sheets] in this order. Path and title ascending, changed with the newest first. */
    fun sort(sheets: List<Sheet>): List<Sheet> = when (this) {
        PATH -> sheets.sortedBy { it.path }
        TITLE -> sheets.sortedBy { it.title.lowercase() }
        CHANGED -> sheets.sortedByDescending { it.updated }
    }

    companion object {
        /** The order for [param], [PATH] for anything unknown. */
        fun fromParam(param: String?): SheetOrder = entries.firstOrNull { it.param == param } ?: PATH
    }
}

/**
 * The admin overview: the sheets with links to edit and delete, a search form and sortable columns. Search and
 * sorting are plain GET links and forms, the server filters and sorts.
 *
 * @param sheets The sheets that match [query] in [order], all of them for an empty [query].
 * @param adminName Display name of the logged in admin, empty hides "logged in as".
 * @param formatTime Formats [Sheet.updated] for display.
 */
fun MAIN.overview(t: Texts, sheets: List<Sheet>, query: String, order: SheetOrder, adminName: String, formatTime: (Long) -> String) {
    div(classes = "bar") {
        h1 { +t.sheets }
        if (adminName.isNotBlank()) span(classes = "muted who") { +t.loggedInAs(adminName) }
        a(href = "$ADMIN_PATH/new", classes = "button") { +t.newSheet }
        a(href = "$ADMIN_PATH/settings", classes = "button secondary") { +t.settings }
        form(action = LOGOUT_PATH, method = FormMethod.post) { submitInput(classes = "secondary") { value = t.logOut } }
    }
    form(action = ADMIN_PATH, method = FormMethod.get, classes = "search") {
        input(type = InputType.search, name = "q") {
            value = query
            placeholder = t.searchPlaceholder
            attributes["aria-label"] = t.search
        }
        if (order != SheetOrder.PATH) hiddenInput(name = "sort") { value = order.param }
        button(type = ButtonType.submit, classes = "secondary") { +t.search }
        if (query.isNotEmpty()) a(href = overviewHref(order, query = "")) { +t.showAll }
    }
    if (sheets.isEmpty()) {
        p { +if (query.isEmpty()) t.noSheetsYet else t.noMatches }
        return
    }
    div(classes = "table-wrap") { sheetTable(t, sheets, query, order, formatTime) }
}

/** The overview URL with [order] and [query], leaving out what is default. */
internal fun overviewHref(order: SheetOrder, query: String): String {
    val parameters = listOfNotNull(
        "sort=${order.param}".takeIf { order != SheetOrder.PATH },
        "q=${query.encodeURLParameter()}".takeIf { query.isNotEmpty() },
    )
    return if (parameters.isEmpty()) ADMIN_PATH else "$ADMIN_PATH?" + parameters.joinToString("&")
}

/** A column header that sorts by [column], marked with an arrow while it is the current [order]. */
private fun TR.sortHeader(label: String, column: SheetOrder, order: SheetOrder, query: String) {
    th {
        a(href = overviewHref(column, query), classes = if (column == order) "sorted" else null) {
            +label
            if (column == order) +if (column == SheetOrder.CHANGED) " ▼" else " ▲"
        }
    }
}

private fun FlowContent.sheetTable(t: Texts, sheets: List<Sheet>, query: String, order: SheetOrder, formatTime: (Long) -> String) {
    table {
        thead {
            tr {
                sortHeader(t.columnPath, SheetOrder.PATH, order, query)
                sortHeader(t.columnTitle, SheetOrder.TITLE, order, query)
                th { +t.columnProtection }
                sortHeader(t.columnChanged, SheetOrder.CHANGED, order, query)
                th { +t.columnActions }
            }
        }
        tbody {
            sheets.forEach { sheet ->
                tr(classes = if (sheet.enabled) null else "disabled") {
                    td {
                        // A disabled sheet answers 404, a link would lead nowhere
                        if (sheet.enabled) a(href = "/${sheet.path}") { +sheet.path }
                        else {
                            +sheet.path
                            span(classes = "badge off") { +t.disabledMarker }
                        }
                    }
                    td { +sheet.title }
                    td { if (sheet.isProtected) span(classes = "badge") { +t.protectedMarker } }
                    td(classes = "muted") { +formatTime(sheet.updated) }
                    td(classes = "actions") {
                        a(href = "$ADMIN_PATH/edit/${sheet.id}") { +t.edit }
                        a(href = "$ADMIN_PATH/duplicate/${sheet.id}") { +t.duplicate }
                        a(href = "$ADMIN_PATH/qr/${sheet.id}") { +t.qrCode }
                        form(action = "$ADMIN_PATH/toggle/${sheet.id}", method = FormMethod.post) {
                            hiddenInput(name = "back") { value = overviewHref(order, query) }
                            button(type = ButtonType.submit, classes = "link") { +if (sheet.enabled) t.disable else t.enable }
                        }
                        a(href = "$ADMIN_PATH/delete/${sheet.id}", classes = "danger") { +t.delete }
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
fun MAIN.editor(t: Texts, target: EditorTarget, values: EditorValues, errors: List<String>, minPasswordLength: Int, preview: String? = null) {
    val action = when (target) {
        EditorTarget.New -> "$ADMIN_PATH/new"
        is EditorTarget.Existing -> "$ADMIN_PATH/edit/${target.id}"
    }
    h1 { +if (target == EditorTarget.New) t.newSheet else t.editSheet }
    if (errors.isNotEmpty()) ul(classes = "error") { errors.forEach { li { +it } } }
    if (preview != null) section(classes = "preview") {
        h2 { +t.previewHeading }
        markdownArticle(preview)
    }

    form(action = action, method = FormMethod.post) {
        if (target is EditorTarget.Existing) {
            hiddenInput(name = "updated") { value = target.updated.toString() }
            if (target.currentPassword != null) hiddenInput(name = "currentPassword") { value = target.currentPassword }
            if (target.unlockProof != null) hiddenInput(name = "unlockProof") { value = target.unlockProof }
        }
        label {
            htmlFor = "path"
            +t.path
        }
        textInput(name = "path") {
            id = "path"
            required = true
            maxLength = MAX_PATH_LENGTH.toString()
            pattern = PATH_PATTERN
            value = values.path
        }
        p(classes = "hint") { +t.pathHint }
        label {
            htmlFor = "title"
            +t.title
        }
        textInput(name = "title") {
            id = "title"
            required = true
            maxLength = MAX_TITLE_LENGTH.toString()
            value = values.title
        }
        label {
            htmlFor = "markdown"
            +t.content
        }
        textArea(rows = "20") {
            id = "markdown"
            name = "markdown"
            // The HTML parser drops one line break right after <textarea>, this one keeps a leading line break of the content
            +"\n${values.markdown}"
        }
        protectionFields(t, target, values, minPasswordLength)
        div(classes = "actions") {
            submitInput { value = if (target == EditorTarget.New) t.create else t.save }
            button(type = ButtonType.submit, name = ACTION_FIELD, classes = "secondary") {
                value = PREVIEW_ACTION
                +t.preview
            }
            a(href = ADMIN_PATH) { +t.cancel }
        }
    }
}

private fun FORM.protectionFields(t: Texts, target: EditorTarget, values: EditorValues, minPasswordLength: Int) {
    fieldSet {
        legend { +t.protection }
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
                +" ${t.protectionChoice(choice)}"
            }
        }
        val hint = if (target == EditorTarget.New) "${t.newSheetPasswordHint} " else ""
        p(classes = "hint") { +"$hint${t.passwordHint(minPasswordLength)}" }
        label {
            htmlFor = "password"
            +t.password
        }
        passwordInput(name = "password") {
            id = "password"
            minLength = minPasswordLength.toString()
            autoComplete = "new-password"
            value = values.password
        }
        label {
            htmlFor = "passwordRepeat"
            +t.passwordRepeat
        }
        passwordInput(name = "passwordRepeat") {
            id = "passwordRepeat"
            minLength = minPasswordLength.toString()
            autoComplete = "new-password"
            value = values.passwordRepeat
        }
    }
}

/**
 * The QR code of [sheet] with a download. The image is the PNG at `qr.png` below the page.
 *
 * @param url The public address the code points to.
 */
fun MAIN.qrPage(t: Texts, sheet: Sheet, url: String) {
    val image = "$ADMIN_PATH/qr/${sheet.id}/qr.png"
    h1 { +t.qrHeading(sheet.title) }
    div(classes = "qr") {
        img(src = image, alt = url) {
            width = "260"
            height = "260"
        }
    }
    p(classes = "hint") {
        +"${t.qrHint} "
        code { +url }
    }
    div(classes = "actions") {
        a(href = image, classes = "button") {
            attributes["download"] = "${sheet.path.substringAfterLast('/')}-qr.png"
            +t.downloadPng
        }
        a(href = ADMIN_PATH) { +t.toOverview }
    }
}

/** Asks before [sheet] is deleted. The deletion itself is a POST, never a GET. */
fun MAIN.deleteConfirm(t: Texts, sheet: Sheet) {
    h1 { +t.deleteSheet }
    p {
        +t.deleteQuestionStart
        code { +sheet.path }
        +t.deleteQuestionEnd(sheet.title)
    }
    if (sheet.isProtected) p { +t.deleteProtectedWarning }
    form(action = "$ADMIN_PATH/delete/${sheet.id}", method = FormMethod.post) {
        div(classes = "actions") {
            submitInput(classes = "danger") { value = t.delete }
            a(href = ADMIN_PATH) { +t.cancel }
        }
    }
}
