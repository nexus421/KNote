package bayern.kickner.knote.web

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import bayern.kickner.knote.auth.AdminSession
import bayern.kickner.knote.config.AppConfig
import bayern.kickner.knote.crypto.CryptoGate
import bayern.kickner.knote.crypto.UnlockError
import bayern.kickner.knote.crypto.decryptContent
import bayern.kickner.knote.i18n.texts
import bayern.kickner.knote.markdown.renderMarkdown
import bayern.kickner.knote.storage.Sheet
import bayern.kickner.knote.storage.SheetContent
import bayern.kickner.knote.storage.SheetStore
import io.ktor.http.HttpHeaders
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
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import kotlinx.html.MAIN
import kotnexlib.ResultOf2
import java.time.ZoneId

private const val TAG = "PublicRoutes"

/** Liveness check for monitoring. Below `_`, so it can never collide with a sheet. */
const val HEALTH_PATH = "/_health"

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
        val loggedIn = call.sessions.get<AdminSession>()?.isValid(clock(), config.oidc.allowedSubjects) == true
        if (loggedIn) call.respondRedirect(ADMIN_PATH)
        else call.respondPage(title = "KNote", narrow = true, header = false) { landing(call.texts) }
    }
}

/**
 * `GET /{path...}` shows a sheet, or its password form if it is protected. `POST /{path...}` unlocks it: the
 * response is the rendered sheet itself, there is no session (a reload asks for the password again). Unknown and
 * invalid paths both answer 404, the lookup only ever goes through [store].
 *
 * @param zone Time zone of the "last changed" line.
 */
fun Route.sheetRoutes(store: SheetStore, gate: CryptoGate, config: AppConfig, zone: ZoneId) {
    get("/{path...}") {
        val sheet = store.find(call.sheetPath()) ?: return@get call.respondNotFound()
        when (val content = sheet.content) {
            is SheetContent.Plain -> call.respondPage(title = sheet.title) { sheetView(call.texts, sheet.title, renderMarkdown(content.markdown), formatTime(sheet.updated, zone)) }
            is SheetContent.Encrypted -> call.respondPage(title = sheet.title, narrow = true) { unlockForm(call.texts, sheet.title, call.texts.lockedHint, "/${sheet.path}") }
        }
    }

    rateLimit(UNLOCK_RATE_LIMIT) {
        post("/{path...}") {
            val sheet = store.find(call.sheetPath()) ?: return@post call.respondNotFound()
            if (sheet.isProtected.not()) return@post call.respondSeeOther("/${sheet.path}")

            val password = call.receiveParameters()["password"].orEmpty()
            when (val result = gate.run { decryptContent(sheet.content, password) }) {
                is ResultOf2.Success -> {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.respondPage(title = sheet.title) { sheetView(call.texts, sheet.title, renderMarkdown(result.value), formatTime(sheet.updated, zone)) }
                }

                is ResultOf2.Failure -> call.respondUnlockFailure(sheet, result.value, config) {
                    unlockForm(call.texts, sheet.title, call.texts.lockedHint, "/${sheet.path}", call.texts.wrongPassword)
                }
            }
        }
    }
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

/** The sheet path from the tailcard, segments joined with `/`. Empty for `/`. */
private fun ApplicationCall.sheetPath(): String = parameters.getAll("path").orEmpty().joinToString("/")
