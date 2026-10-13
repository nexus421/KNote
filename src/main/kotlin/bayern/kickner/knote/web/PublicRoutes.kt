package bayern.kickner.knote.web

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import bayern.kickner.knote.auth.hasAdminLogin
import bayern.kickner.knote.config.AppConfig
import bayern.kickner.knote.crypto.CryptoGate
import bayern.kickner.knote.crypto.UnlockError
import bayern.kickner.knote.crypto.decryptContent
import bayern.kickner.knote.i18n.texts
import bayern.kickner.knote.markdown.renderMarkdown
import bayern.kickner.knote.storage.Sheet
import bayern.kickner.knote.storage.SheetContent
import bayern.kickner.knote.storage.SheetStore
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.withCharset
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.MAIN
import kotnexlib.ResultOf2
import java.time.ZoneId

private const val TAG = "PublicRoutes"

/** Liveness check for monitoring. Below `_`, so it can never collide with a sheet. */
const val HEALTH_PATH = "/_health"

/** Appended to a sheet path, it asks for the raw Markdown as a file. Sheet paths never contain a dot. */
const val MARKDOWN_SUFFIX = ".md"

/** Rate limit of every password attempt, public and admin, keyed by client IP. */
val UNLOCK_RATE_LIMIT = RateLimitName("unlock")

/** `GET /_health`: answers `ok` as long as the process runs. No login, no data, meant for monitoring tools. */
fun Route.healthRoute() {
    get(HEALTH_PATH) { call.respondText("ok") }
}

/**
 * `GET /`: an admin with a valid login goes straight to the overview, everyone else sees the start page with the
 * login button. The start page never redirects to the provider on its own, so a logout really ends at it.
 *
 * @param clock Current time in Unix milliseconds, injectable for tests.
 */
fun Route.startPage(config: AppConfig, clock: () -> Long) {
    get("/") {
        if (call.hasAdminLogin(config, clock)) call.respondRedirect(ADMIN_PATH)
        else call.respondPage(title = "KNote", narrow = true, startPage = true) { landing(call.texts) }
    }
}

/**
 * `GET /{path...}` shows a sheet, or its password form if it is protected. `POST /{path...}` unlocks it: the
 * response is the rendered sheet itself, there is no session (a reload asks for the password again). With
 * [MARKDOWN_SUFFIX] appended, both answer the raw Markdown as a file instead. Unknown, invalid and disabled paths
 * answer 404, the lookup only ever goes through [store].
 *
 * A logged in admin also gets a link to the editor. Such a page is never cached, it differs from the public one.
 *
 * @param zone Time zone of the "last changed" line.
 * @param clock Current time in Unix milliseconds, checks the admin login.
 */
fun Route.sheetRoutes(store: SheetStore, gate: CryptoGate, config: AppConfig, zone: ZoneId, clock: () -> Long) {
    get("/{path...}") {
        val request = call.sheetRequest()
        val sheet = store.find(request.path)?.takeIf { it.enabled } ?: return@get call.respondNotFound()
        val t = call.texts
        when (val content = sheet.content) {
            is SheetContent.Plain ->
                if (request.download) call.respondMarkdownFile(sheet, content.markdown)
                else {
                    val editHref = call.editHref(sheet, config, clock)
                    call.respondPage(title = sheet.title) {
                        sheetView(t, sheet.title, renderMarkdown(content.markdown), formatTime(sheet.updated, zone), MarkdownDownload.Link(sheet.url(download = true)), editHref)
                    }
                }

            is SheetContent.Encrypted -> call.respondPage(title = sheet.title, narrow = true) {
                unlockForm(t, sheet.title, t.lockedHint, sheet.url(request.download))
            }
        }
    }

    rateLimit(UNLOCK_RATE_LIMIT) {
        post("/{path...}") {
            val request = call.sheetRequest()
            val sheet = store.find(request.path)?.takeIf { it.enabled } ?: return@post call.respondNotFound()
            if (sheet.isProtected.not()) return@post call.respondSeeOther(sheet.url(request.download))

            val t = call.texts
            val password = call.receiveParameters()["password"].orEmpty()
            when (val result = gate.run { decryptContent(sheet.content, password) }) {
                is ResultOf2.Success -> {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    if (request.download) call.respondMarkdownFile(sheet, result.value)
                    else {
                        val editHref = call.editHref(sheet, config, clock)
                        call.respondPage(title = sheet.title) {
                            val download = MarkdownDownload.Form(sheet.url(download = true), password)
                            sheetView(t, sheet.title, renderMarkdown(result.value), formatTime(sheet.updated, zone), download, editHref)
                        }
                    }
                }

                is ResultOf2.Failure -> call.respondUnlockFailure(sheet, result.value, config) {
                    unlockForm(t, sheet.title, t.lockedHint, sheet.url(request.download), t.wrongPassword)
                }
            }
        }
    }
}

/** The editor link for a logged in admin, null for everyone else. Marks the response as not cacheable. */
private fun ApplicationCall.editHref(sheet: Sheet, config: AppConfig, clock: () -> Long): String? {
    if (hasAdminLogin(config, clock).not()) return null
    response.header(HttpHeaders.CacheControl, "no-store")
    return "$ADMIN_PATH/edit/${sheet.id}"
}

/** A requested sheet path and whether the raw Markdown file was asked for. */
private data class SheetRequest(val path: String, val download: Boolean)

/** The sheet path from the tailcard, segments joined with `/`, without [MARKDOWN_SUFFIX]. Empty for `/`. */
private fun ApplicationCall.sheetRequest(): SheetRequest {
    val requested = parameters.getAll("path").orEmpty().joinToString("/")
    return SheetRequest(requested.removeSuffix(MARKDOWN_SUFFIX), requested.endsWith(MARKDOWN_SUFFIX))
}

/** Address of the sheet page, or of its Markdown file with [download]. */
private fun Sheet.url(download: Boolean): String = "/$path" + if (download) MARKDOWN_SUFFIX else ""

/** Answers [markdown] as a file named after the last path segment, e.g. `knote.md` for `projects/knote`. */
private suspend fun ApplicationCall.respondMarkdownFile(sheet: Sheet, markdown: String) {
    val fileName = sheet.path.substringAfterLast('/') + MARKDOWN_SUFFIX
    response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, fileName).toString())
    respondText(markdown, ContentType("text", "markdown").withCharset(Charsets.UTF_8))
}

/**
 * Answers a failed unlock: 403 with [passwordForm] for a wrong password, 303 back to the sheet if it has no
 * password, 500 if the stored data is damaged. Wrong passwords are logged with path and client IP, never with the
 * password.
 */
internal suspend fun ApplicationCall.respondUnlockFailure(sheet: Sheet, error: UnlockError, config: AppConfig, passwordForm: MAIN.() -> Unit) {
    when (error) {
        UnlockError.WrongPassword -> {
            staticLog(KLogger.Level.WARN, TAG) { "Wrong password for sheet path=${sheet.path} ip=${clientIp(config.trustedProxyIp)}" }
            respondPage(HttpStatusCode.Forbidden, sheet.title, narrow = true, content = passwordForm)
        }

        UnlockError.NotProtected -> respondSeeOther("/${sheet.path}")

        is UnlockError.Corrupt -> {
            staticLog(KLogger.Level.ERROR, TAG) { "Sheet id=${sheet.id} cannot be decrypted: ${error.reason}" }
            respondMessage(HttpStatusCode.InternalServerError, texts.damagedHeading, texts.damagedText)
        }
    }
}


