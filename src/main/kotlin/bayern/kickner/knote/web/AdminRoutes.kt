package bayern.kickner.knote.web

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import bayern.kickner.knote.auth.ADMIN_AUTH
import bayern.kickner.knote.auth.AdminSession
import bayern.kickner.knote.config.AppConfig
import bayern.kickner.knote.crypto.CryptoGate
import bayern.kickner.knote.crypto.UnlockError
import bayern.kickner.knote.crypto.UnlockProof
import bayern.kickner.knote.crypto.decryptContent
import bayern.kickner.knote.crypto.encryptContent
import bayern.kickner.knote.i18n.Texts
import bayern.kickner.knote.i18n.texts
import bayern.kickner.knote.markdown.renderMarkdown
import bayern.kickner.knote.storage.Sheet
import bayern.kickner.knote.storage.SheetContent
import bayern.kickner.knote.storage.SheetStore
import bayern.kickner.knote.storage.StoreError
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
import kotlinx.html.MAIN
import kotnexlib.ResultOf2
import java.time.ZoneId

private const val TAG = "AdminRoutes"

/**
 * Everything below [ADMIN_PATH]: overview, create, edit and delete. Requires an admin login, accepts POSTs only from
 * [AppConfig.publicBaseUrl] and sends `Cache-Control: no-store` on every response. Pages address a sheet by its id,
 * so paths with slashes never collide with `/edit` or `/delete`.
 *
 * Editing a protected sheet needs its password (the content cannot be read otherwise). After the unlock the editor
 * keeps it in a hidden field together with an [UnlockProof], and every save checks that proof before encrypting
 * again. The save therefore never tells whether a password is right, only the rate limited unlock does. The
 * preview button posts the same form to the same URL and gets it back with the rendered Markdown, nothing is saved.
 *
 * Duplicating opens the editor for a new sheet, filled with the content of the original, a copy title and a free
 * copy path. Nothing is saved before "create". A protected original needs its password first, and the copy keeps
 * that password unless the fields are cleared.
 *
 * @param unlockProof Issues and checks the proof that the password of a protected sheet is known.
 * @param clock Current time in Unix milliseconds, injectable for tests.
 * @param zone Time zone of the timestamps in the overview.
 */
fun Route.adminRoutes(store: SheetStore, gate: CryptoGate, unlockProof: UnlockProof, config: AppConfig, clock: () -> Long, zone: ZoneId) {
    route(ADMIN_PATH) {
        install(CSRF) { onlyFrom(config.publicBaseUrl) }
        install(NoStore)
        authenticate(ADMIN_AUTH) {
            get {
                val query = call.request.queryParameters["q"].orEmpty().trim()
                val sheets = store.all.filter { it.matches(query) }
                call.respondPage(title = call.texts.sheets) { overview(call.texts, sheets, query, call.principal<AdminSession>()?.name.orEmpty()) { formatTime(it, zone) } }
            }

            get("/new") {
                call.respondEditor(HttpStatusCode.OK, EditorTarget.New, EditorValues(), emptyList(), config)
            }

            post("/new") {
                val form = call.receiveParameters().toSheetForm()
                val values = EditorValues(form.path, form.title, form.markdown, password = form.password, passwordRepeat = form.passwordRepeat)
                if (form.preview) return@post call.respondPreview(EditorTarget.New, values, config)

                val wantsPassword = form.password.isNotEmpty() || form.passwordRepeat.isNotEmpty()
                val t = call.texts
                val issues = validateFields(t, form, config.maxSheetBytes) +
                    (if (wantsPassword) validateNewPassword(t, form.password, form.passwordRepeat, config.minSheetPasswordLength) else emptyList()) +
                    pathTaken(t, store, form.path, ownId = null)
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
                        call.respondEditor(HttpStatusCode.BadRequest, EditorTarget.New, values, listOf(t.pathTaken), config)
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

                    is SheetContent.Encrypted -> call.respondPage(title = sheet.title, narrow = true) { adminUnlockForm(call.texts, sheet) }
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
                            EditorTarget.Existing(sheet.id, sheet.updated, password, unlockProof.create(sheet.id, sheet.updated, password)),
                            EditorValues(sheet.path, sheet.title, result.value),
                            emptyList(),
                            config,
                        )

                        is ResultOf2.Failure -> call.respondUnlockFailure(sheet, result.value, config) {
                            adminUnlockForm(call.texts, sheet, call.texts.wrongPassword)
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
                // Password and proof go back unchecked, the save checks them
                val target = EditorTarget.Existing(
                    sheet.id,
                    sheet.updated,
                    form.currentPassword.takeIf { sheet.isProtected },
                    form.unlockProof.takeIf { sheet.isProtected },
                )
                val values = EditorValues(form.path, form.title, form.markdown, choice, form.password, form.passwordRepeat)
                if (form.preview) return@post call.respondPreview(target, values, config)

                val t = call.texts
                val needsNewPassword = choice == ProtectionChoice.SET || choice == ProtectionChoice.CHANGE
                val issues = validateFields(t, form, config.maxSheetBytes) +
                    listOfNotNull(t.invalidProtection.takeIf { choice == null }) +
                    (if (needsNewPassword) validateNewPassword(t, form.password, form.passwordRepeat, config.minSheetPasswordLength) else emptyList()) +
                    pathTaken(t, store, form.path, ownId = sheet.id)
                if (issues.isNotEmpty() || choice == null) return@post call.respondEditor(HttpStatusCode.BadRequest, target, values, issues, config)

                // The hidden fields come from the browser and may be manipulated
                val proven = unlockProof.verify(form.unlockProof, sheet.id, sheet.updated, form.currentPassword.orEmpty())
                if (sheet.isProtected && proven.not()) return@post call.respondUnlockFailure(sheet, UnlockError.WrongPassword, config) {
                    adminUnlockForm(t, sheet, t.wrongPassword)
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
                        call.respondEditor(HttpStatusCode.BadRequest, target, values, listOf(t.pathTaken), config)
                    }
                }
            }

            get("/duplicate/{id}") {
                val sheet = store.findById(call.parameters["id"].orEmpty()) ?: return@get call.respondNotFound()
                when (val content = sheet.content) {
                    is SheetContent.Plain ->
                        call.respondEditor(HttpStatusCode.OK, EditorTarget.New, copyValues(call.texts, store, sheet, content.markdown, password = ""), emptyList(), config)

                    is SheetContent.Encrypted -> call.respondPage(title = sheet.title, narrow = true) { duplicateUnlockForm(call.texts, sheet) }
                }
            }

            rateLimit(UNLOCK_RATE_LIMIT) {
                post("/duplicate/{id}/unlock") {
                    val sheet = store.findById(call.parameters["id"].orEmpty()) ?: return@post call.respondNotFound()
                    if (sheet.isProtected.not()) return@post call.respondSeeOther("$ADMIN_PATH/duplicate/${sheet.id}")

                    val password = call.receiveParameters()["password"].orEmpty()
                    when (val result = gate.run { decryptContent(sheet.content, password) }) {
                        is ResultOf2.Success ->
                            call.respondEditor(HttpStatusCode.OK, EditorTarget.New, copyValues(call.texts, store, sheet, result.value, password), emptyList(), config)

                        is ResultOf2.Failure -> call.respondUnlockFailure(sheet, result.value, config) {
                            duplicateUnlockForm(call.texts, sheet, call.texts.wrongPassword)
                        }
                    }
                }
            }

            get("/delete/{id}") {
                val sheet = store.findById(call.parameters["id"].orEmpty()) ?: return@get call.respondNotFound()
                call.respondPage(title = call.texts.deleteSheet, narrow = true) { deleteConfirm(call.texts, sheet) }
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
) = respondPage(status, if (target == EditorTarget.New) texts.newSheet else texts.editSheet) {
    editor(texts, target, values, errors, config.minSheetPasswordLength, preview)
}

/** Password form of a protected sheet before it is duplicated, with an optional [error]. */
private fun MAIN.duplicateUnlockForm(t: Texts, sheet: Sheet, error: String? = null) =
    unlockForm(t, t.duplicateUnlockHeading(sheet.title), t.duplicateUnlockHint, "$ADMIN_PATH/duplicate/${sheet.id}/unlock", error)

/**
 * Editor values for a copy of [sheet]: its [markdown], [Texts.copyTitle] and the first free path of the form
 * `path-copy`, `path-copy-2` and so on. [password] fills both password fields, empty for an unprotected original.
 */
private fun copyValues(t: Texts, store: SheetStore, sheet: Sheet, markdown: String, password: String): EditorValues {
    val base = "${sheet.path}-${t.copyPathSuffix}"
    val path = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { store.find(it) == null }
    return EditorValues(path, t.copyTitle(sheet.title), markdown, password = password, passwordRepeat = password)
}

/** Password form of a protected sheet before its editor opens, with an optional [error]. */
private fun MAIN.adminUnlockForm(t: Texts, sheet: Sheet, error: String? = null) =
    unlockForm(t, t.editUnlockHeading(sheet.title), t.editUnlockHint, "$ADMIN_PATH/edit/${sheet.id}/unlock", error)

/**
 * The editor again with [values] and the rendered Markdown above it. Nothing is validated or saved, only the size
 * is checked so an oversized text is not rendered. Costs no PBKDF2 derivation.
 */
private suspend fun ApplicationCall.respondPreview(target: EditorTarget, values: EditorValues, config: AppConfig) {
    val tooLarge = validateMarkdownSize(texts, values.markdown, config.maxSheetBytes)
    val preview = if (tooLarge.isEmpty()) renderMarkdown(values.markdown) else null
    respondEditor(HttpStatusCode.OK, target, values, tooLarge, config, preview)
}

private suspend fun ApplicationCall.respondConflict() =
    respondMessage(HttpStatusCode.Conflict, texts.conflictHeading, texts.conflictText, PageLink(ADMIN_PATH, texts.toOverview))

/** Maps a [StoreError] to its page. [onPathTaken] renders the form again with the message. */
private suspend fun ApplicationCall.respondStoreError(error: StoreError, onPathTaken: suspend () -> Unit) {
    when (error) {
        StoreError.PathTaken -> onPathTaken()
        StoreError.NotFound -> respondNotFound()
        StoreError.Conflict -> respondConflict()
        is StoreError.WriteFailed -> {
            staticLog(KLogger.Level.ERROR, TAG) { "Could not write ${error.reason}" }
            respondMessage(HttpStatusCode.InternalServerError, texts.errorHeading, texts.saveFailedText, PageLink(ADMIN_PATH, texts.toOverview))
        }
    }
}

/** [Texts.pathTaken] if another sheet than [ownId] already uses [path]. */
private fun pathTaken(t: Texts, store: SheetStore, path: String, ownId: String?): List<String> {
    val other = store.find(path)
    return if (other != null && other.id != ownId) listOf(t.pathTaken) else emptyList()
}

/** True if [query] is empty or part of path or title, ignoring case. */
private fun Sheet.matches(query: String): Boolean =
    query.isEmpty() || path.contains(query, ignoreCase = true) || title.contains(query, ignoreCase = true)

/** `sub` of the logged in admin. Only called below [authenticate], where the principal always exists. */
private fun ApplicationCall.adminSub(): String = principal<AdminSession>()?.sub ?: "unknown"
