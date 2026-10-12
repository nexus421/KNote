package bayern.kickner.knote.web

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import bayern.kickner.knote.auth.ADMIN_AUTH
import bayern.kickner.knote.auth.AdminSession
import bayern.kickner.knote.config.AppConfig
import bayern.kickner.knote.crypto.CryptoGate
import bayern.kickner.knote.crypto.decryptContent
import bayern.kickner.knote.crypto.encryptContent
import bayern.kickner.knote.markdown.renderMarkdown
import bayern.kickner.knote.storage.Sheet
import bayern.kickner.knote.storage.SheetContent
import bayern.kickner.knote.storage.SheetStore
import bayern.kickner.knote.storage.StoreError
import bayern.kickner.knote.timestampFormat
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.csrf.CSRF
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receiveParameters
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotnexlib.ResultOf2
import java.time.Instant
import java.time.ZoneId

private const val TAG = "AdminRoutes"

private const val PATH_TAKEN = "Pfad bereits vergeben"
private const val INVALID_PROTECTION = "Ungültige Auswahl beim Schutz"
private const val ADMIN_UNLOCK_HINT = "Zum Bearbeiten das Passwort des Blatts eingeben."

/**
 * Everything below [ADMIN_PATH]: overview, create, edit and delete. Requires an admin login, accepts POSTs only from
 * [AppConfig.publicBaseUrl] and sends `Cache-Control: no-store` on every response. Pages address a sheet by its id,
 * so paths with slashes never collide with `/edit` or `/delete`.
 *
 * Editing a protected sheet needs its password (the content cannot be read otherwise). After the unlock the editor
 * keeps it in a hidden field, and every save checks it against the stored content before encrypting again. The
 * preview button posts the same form to the same URL and gets it back with the rendered Markdown, nothing is saved.
 *
 * @param clock Current time in Unix milliseconds, injectable for tests.
 * @param zone Time zone of the timestamps in the overview.
 */
fun Route.adminRoutes(store: SheetStore, gate: CryptoGate, config: AppConfig, clock: () -> Long, zone: ZoneId) {
    val formatTime = { millis: Long -> Instant.ofEpochMilli(millis).atZone(zone).format(timestampFormat) }

    route(ADMIN_PATH) {
        install(CSRF) { onlyFrom(config.publicBaseUrl) }
        install(NoStore)
        authenticate(ADMIN_AUTH) {
            get {
                call.respondPage(title = "Blätter") { overview(store.all, formatTime) }
            }

            get("/new") {
                call.respondEditor(HttpStatusCode.OK, EditorTarget.New, EditorValues(), emptyList(), config)
            }

            post("/new") {
                val form = call.receiveParameters().toSheetForm()
                val values = EditorValues(form.path, form.title, form.markdown, password = form.password, passwordRepeat = form.passwordRepeat)
                if (form.preview) return@post call.respondPreview(EditorTarget.New, values, config)

                val wantsPassword = form.password.isNotEmpty() || form.passwordRepeat.isNotEmpty()
                val issues = validateFields(form, config.maxSheetBytes) +
                    (if (wantsPassword) validateNewPassword(form.password, form.passwordRepeat, config.minSheetPasswordLength) else emptyList()) +
                    pathTaken(store, form.path, ownId = null)
                if (issues.isNotEmpty()) return@post call.respondEditor(HttpStatusCode.BadRequest, EditorTarget.New, values, issues, config)

                val content = if (wantsPassword) gate.run { encryptContent(form.markdown, form.password) } else SheetContent.Plain(form.markdown)
                val now = clock()
                val sheet = Sheet(SheetStore.newId(), form.path, form.title, content, call.adminSub(), now, now)
                when (val saved = store.save(sheet)) {
                    is ResultOf2.Success -> {
                        staticLog(KLogger.Level.INFO, TAG) { "Sheet created id=${sheet.id} path=${sheet.path} protected=${sheet.isProtected}" }
                        call.respondSeeOther(ADMIN_PATH)
                    }

                    is ResultOf2.Failure -> call.respondStoreError(saved.value) {
                        call.respondEditor(HttpStatusCode.BadRequest, EditorTarget.New, values, listOf(PATH_TAKEN), config)
                    }
                }
            }

            get("/edit/{id}") {
                val sheet = store.findById(call.parameters["id"].orEmpty()) ?: return@get call.respondNotFound()
                when (val content = sheet.content) {
                    is SheetContent.Plain -> call.respondEditor(
                        HttpStatusCode.OK,
                        EditorTarget.Existing(sheet.id, sheet.updated, currentPassword = null),
                        EditorValues(sheet.path, sheet.title, content.markdown),
                        emptyList(),
                        config,
                    )

                    is SheetContent.Encrypted -> call.respondPage(title = sheet.title) {
                        unlockForm("Bearbeiten: ${sheet.title}", ADMIN_UNLOCK_HINT, "$ADMIN_PATH/edit/${sheet.id}/unlock")
                    }
                }
            }

            rateLimit(UNLOCK_RATE_LIMIT) {
                post("/edit/{id}/unlock") {
                    val sheet = store.findById(call.parameters["id"].orEmpty()) ?: return@post call.respondNotFound()
                    if (sheet.isProtected.not()) return@post call.respondSeeOther("$ADMIN_PATH/edit/${sheet.id}")

                    val password = call.receiveParameters()["password"].orEmpty()
                    when (val result = gate.run { decryptContent(sheet.content, password) }) {
                        is ResultOf2.Success -> call.respondEditor(
                            HttpStatusCode.OK,
                            EditorTarget.Existing(sheet.id, sheet.updated, password),
                            EditorValues(sheet.path, sheet.title, result.value),
                            emptyList(),
                            config,
                        )

                        is ResultOf2.Failure -> call.respondUnlockFailure(sheet, result.value, config) {
                            unlockForm("Bearbeiten: ${sheet.title}", ADMIN_UNLOCK_HINT, "$ADMIN_PATH/edit/${sheet.id}/unlock", WRONG_PASSWORD)
                        }
                    }
                }
            }

            post("/edit/{id}") {
                val sheet = store.findById(call.parameters["id"].orEmpty()) ?: return@post call.respondNotFound()
                val form = call.receiveParameters().toSheetForm()
                // Cheap check first, the save below repeats it atomically
                if (form.updated != sheet.updated) return@post call.respondConflict()

                val allowedChoices = if (sheet.isProtected) ProtectionChoice.forProtected else ProtectionChoice.forUnprotected
                val choice = form.protection?.takeIf { it in allowedChoices }
                // The hidden current password goes back unchecked, the save checks it
                val target = EditorTarget.Existing(sheet.id, sheet.updated, form.currentPassword.takeIf { sheet.isProtected })
                val values = EditorValues(form.path, form.title, form.markdown, choice, form.password, form.passwordRepeat)
                if (form.preview) return@post call.respondPreview(target, values, config)

                val needsNewPassword = choice == ProtectionChoice.SET || choice == ProtectionChoice.CHANGE
                val issues = validateFields(form, config.maxSheetBytes) +
                    listOfNotNull(INVALID_PROTECTION.takeIf { choice == null }) +
                    (if (needsNewPassword) validateNewPassword(form.password, form.passwordRepeat, config.minSheetPasswordLength) else emptyList()) +
                    pathTaken(store, form.path, ownId = sheet.id)
                if (issues.isNotEmpty() || choice == null) return@post call.respondEditor(HttpStatusCode.BadRequest, target, values, issues, config)

                // The hidden field comes from the browser and may be manipulated
                if (sheet.isProtected) {
                    val check = gate.run { decryptContent(sheet.content, form.currentPassword.orEmpty()) }
                    if (check is ResultOf2.Failure) return@post call.respondUnlockFailure(sheet, check.value, config) {
                        unlockForm("Bearbeiten: ${sheet.title}", ADMIN_UNLOCK_HINT, "$ADMIN_PATH/edit/${sheet.id}/unlock", WRONG_PASSWORD)
                    }
                }
                val content = when (choice) {
                    ProtectionChoice.KEEP -> gate.run { encryptContent(form.markdown, form.currentPassword.orEmpty()) }
                    ProtectionChoice.CHANGE, ProtectionChoice.SET -> gate.run { encryptContent(form.markdown, form.password) }
                    ProtectionChoice.REMOVE, ProtectionChoice.LEAVE_OPEN -> SheetContent.Plain(form.markdown)
                }

                // Strictly increasing, so an editor opened within the same millisecond still counts as outdated
                val updated = sheet.copy(path = form.path, title = form.title, content = content, updated = maxOf(clock(), sheet.updated + 1))
                when (val saved = store.save(updated, expectedUpdated = sheet.updated)) {
                    is ResultOf2.Success -> {
                        staticLog(KLogger.Level.INFO, TAG) { "Sheet updated id=${updated.id} path=${updated.path} protected=${updated.isProtected}" }
                        call.respondSeeOther(ADMIN_PATH)
                    }

                    is ResultOf2.Failure -> call.respondStoreError(saved.value) {
                        call.respondEditor(HttpStatusCode.BadRequest, target, values, listOf(PATH_TAKEN), config)
                    }
                }
            }

            get("/delete/{id}") {
                val sheet = store.findById(call.parameters["id"].orEmpty()) ?: return@get call.respondNotFound()
                call.respondPage(title = "Blatt löschen") { deleteConfirm(sheet) }
            }

            post("/delete/{id}") {
                when (val deleted = store.delete(call.parameters["id"].orEmpty())) {
                    is ResultOf2.Success -> {
                        val sheet = deleted.value
                        staticLog(KLogger.Level.INFO, TAG) { "Sheet deleted id=${sheet.id} path=${sheet.path} protected=${sheet.isProtected}" }
                        call.respondSeeOther(ADMIN_PATH)
                    }

                    is ResultOf2.Failure -> call.respondStoreError(deleted.value) { call.respondConflict() }
                }
            }
        }
    }
}

private suspend fun ApplicationCall.respondEditor(
    status: HttpStatusCode,
    target: EditorTarget,
    values: EditorValues,
    errors: List<String>,
    config: AppConfig,
    preview: String? = null,
) = respondPage(status, if (target == EditorTarget.New) "Neues Blatt" else "Blatt bearbeiten") {
    editor(target, values, errors, config.minSheetPasswordLength, preview)
}

/**
 * The editor again with [values] and the rendered Markdown above it. Nothing is validated or saved, only the size
 * is checked so an oversized text is not rendered. Costs no PBKDF2 derivation.
 */
private suspend fun ApplicationCall.respondPreview(target: EditorTarget, values: EditorValues, config: AppConfig) {
    val tooLarge = validateMarkdownSize(values.markdown, config.maxSheetBytes)
    val preview = if (tooLarge.isEmpty()) renderMarkdown(values.markdown) else null
    respondEditor(HttpStatusCode.OK, target, values, tooLarge, config, preview)
}

private suspend fun ApplicationCall.respondConflict() = respondMessage(
    HttpStatusCode.Conflict,
    "Blatt wurde geändert",
    "Das Blatt wurde zwischenzeitlich geändert. Bitte erneut öffnen und die Änderung wiederholen.",
    PageLink(ADMIN_PATH, "Zur Übersicht"),
)

/** Maps a [StoreError] to its page. [onPathTaken] renders the form again with the message. */
private suspend fun ApplicationCall.respondStoreError(error: StoreError, onPathTaken: suspend () -> Unit) {
    when (error) {
        StoreError.PathTaken -> onPathTaken()
        StoreError.NotFound -> respondNotFound()
        StoreError.Conflict -> respondConflict()
        is StoreError.WriteFailed -> {
            staticLog(KLogger.Level.ERROR, TAG) { "Could not write ${error.reason}" }
            respondMessage(HttpStatusCode.InternalServerError, "Fehler", "Die Änderung konnte nicht gespeichert werden.", PageLink(ADMIN_PATH, "Zur Übersicht"))
        }
    }
}

/** [PATH_TAKEN] if another sheet than [ownId] already uses [path]. */
private fun pathTaken(store: SheetStore, path: String, ownId: String?): List<String> {
    val other = store.find(path)
    return if (other != null && other.id != ownId) listOf(PATH_TAKEN) else emptyList()
}

/** `sub` of the logged in admin. Only called below [authenticate], where the principal always exists. */
private fun ApplicationCall.adminSub(): String = principal<AdminSession>()?.sub ?: "unknown"
