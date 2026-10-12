package bayern.kickner.knote.web

import bayern.kickner.knote.ADMIN_SUB
import bayern.kickner.knote.FIXED_NOW
import bayern.kickner.knote.adminGet
import bayern.kickner.knote.adminPost
import bayern.kickner.knote.crypto.UnlockError
import bayern.kickner.knote.crypto.decryptContent
import bayern.kickner.knote.crypto.encryptContent
import bayern.kickner.knote.emptyStore
import bayern.kickner.knote.knoteApp
import bayern.kickner.knote.login
import bayern.kickner.knote.plainSheet
import bayern.kickner.knote.storage.Sheet
import bayern.kickner.knote.storage.SheetContent
import bayern.kickner.knote.storage.SheetStore
import bayern.kickner.knote.tempDir
import bayern.kickner.knote.testConfig
import bayern.kickner.knote.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotnexlib.ResultOf2
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val OLD_PASSWORD = "old-password-1"
private const val NEW_PASSWORD = "new-password-2"

private fun SheetStore.content(path: String): SheetContent = assertNotNull(find(path), "no sheet at $path").content

private fun SheetStore.decrypt(path: String, password: String): ResultOf2<String, UnlockError> = decryptContent(content(path), password)

/** A logged in admin client and the store behind it. */
private class Admin(val client: HttpClient, val cookie: String, val store: SheetStore, val dir: Path) {
    suspend fun get(url: String) = client.adminGet(url, cookie)
    suspend fun post(url: String, vararg fields: Pair<String, String>) = client.adminPost(url, cookie, *fields)

    /** Saves [sheet] through the editor form with [fields] on top of its current values. */
    suspend fun save(sheet: Sheet, vararg fields: Pair<String, String>): HttpResponse {
        val defaults = mapOf("path" to sheet.path, "title" to sheet.title, "markdown" to "new content", "updated" to sheet.updated.toString())
        return post("/_admin/edit/${sheet.id}", *(defaults + fields.toMap()).toList().toTypedArray())
    }
}

private suspend fun ApplicationTestBuilder.admin(vararg sheets: Sheet, config: AppConfig = testConfig()): Admin {
    val dir = tempDir()
    val store = emptyStore(dir).apply { sheets.forEach { save(it) } }
    val client = knoteApp(store, config)
    return Admin(client, client.login(), store, dir)
}

private fun protectedSheet(path: String = "privat") = plainSheet(path).copy(content = encryptContent("old content", OLD_PASSWORD))

class AdminRoutesTest {

    @Test
    fun `without login a get redirects and a post is refused`() = testApplication {
        val client = knoteApp()

        val get = client.adminGet("/_admin/new", cookie = "")
        val post = client.adminPost("/_admin/new", cookie = null, "path" to "x", "title" to "x")

        assertEquals(HttpStatusCode.Found, get.status)
        assertEquals("/_auth/login", get.headers[HttpHeaders.Location])
        assertEquals(HttpStatusCode.Unauthorized, post.status)
        assertContains(post.bodyAsText(), "Sitzung abgelaufen")
    }

    @Test
    fun `the overview lists all sheets sorted by path and is not cached`() = testApplication {
        val admin = admin(plainSheet("zeta", title = "Z <i>"), protectedSheet("alpha"))

        val response = admin.get("/_admin")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val body = response.bodyAsText()
        assertTrue(body.indexOf("href=\"/alpha\"") < body.indexOf("href=\"/zeta\""), body)
        assertContains(body, "Z &lt;i&gt;")
        assertContains(body, "geschützt")
        assertContains(body, "13.10.2026 14:00:00 CEST")
    }

    @Test
    fun `a plain sheet is created`() = testApplication {
        val admin = admin()

        val response = admin.post("/_admin/new", "path" to "projekte/knote", "title" to " KNote ", "markdown" to "# Hallo\r\nWelt")

        assertEquals(HttpStatusCode.SeeOther, response.status)
        assertEquals("/_admin", response.headers[HttpHeaders.Location])
        val sheet = assertNotNull(admin.store.find("projekte/knote"))
        assertEquals("KNote", sheet.title)
        assertEquals(SheetContent.Plain("# Hallo\nWelt"), sheet.content)
        assertEquals(ADMIN_SUB, sheet.createdBy)
        assertEquals(FIXED_NOW, sheet.created)
        assertTrue(admin.dir.resolve("${sheet.id}.json").exists())
    }

    @Test
    fun `a protected sheet is created encrypted`() = testApplication {
        val admin = admin()

        admin.post("/_admin/new", "path" to "privat", "title" to "Privat", "markdown" to "geheim", "password" to NEW_PASSWORD, "passwordRepeat" to NEW_PASSWORD)

        assertIs<SheetContent.Encrypted>(admin.store.content("privat"))
        assertEquals("geheim", assertIs<ResultOf2.Success<String>>(admin.store.decrypt("privat", NEW_PASSWORD)).value)
    }

    @Test
    fun `invalid input is shown in the form and nothing is saved`() = testApplication {
        val admin = admin(plainSheet("einkauf"))

        val invalid = admin.post("/_admin/new", "path" to "Bad Path", "title" to "", "markdown" to "x", "password" to "short", "passwordRepeat" to "other")
        val taken = admin.post("/_admin/new", "path" to "einkauf", "title" to "Doppelt", "markdown" to "x")

        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        val body = invalid.bodyAsText()
        listOf("Pfad ungültig", "Titel 1 bis 100 Zeichen", "Passwort mindestens 12 Zeichen", "Passwörter stimmen nicht überein").forEach { assertContains(body, it) }
        assertContains(body, "value=\"Bad Path\"")
        assertEquals(HttpStatusCode.BadRequest, taken.status)
        assertContains(taken.bodyAsText(), "Pfad bereits vergeben")
        assertEquals(1, admin.store.size)
    }

    @Test
    fun `the preview of a new sheet renders the markdown and saves nothing`() = testApplication {
        val admin = admin()

        val response = admin.post(
            "/_admin/new",
            "path" to "neu", "title" to "Neu", "markdown" to "# Kopf\r\n\n~~weg~~ <script>x</script>",
            "password" to NEW_PASSWORD, "passwordRepeat" to NEW_PASSWORD, ACTION_FIELD to PREVIEW_ACTION,
        )

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val body = response.bodyAsText()
        val preview = body.substringAfter("<section class=\"preview\">").substringBefore("</section>")
        assertContains(preview, "<h1>Kopf</h1>")
        assertContains(preview, "<del>weg</del>")
        assertContains(preview, "&lt;script&gt;")
        assertFalse(body.contains("<script>"))
        assertContains(body, "name=\"path\" id=\"path\" required=\"required\" maxlength=\"128\" pattern=")
        assertContains(body, "value=\"neu\"")
        assertContains(body, "# Kopf\n\n~~weg~~ &lt;script&gt;x&lt;/script&gt;</textarea>")
        assertContains(body, "name=\"password\" id=\"password\" minlength=\"12\" autocomplete=\"new-password\" value=\"$NEW_PASSWORD\"")
        assertEquals(0, admin.store.size)
    }

    @Test
    fun `a new sheet is saved protected after a preview`() = testApplication {
        val admin = admin()
        val fields = arrayOf("path" to "neu", "title" to "Neu", "markdown" to "geheim", "password" to NEW_PASSWORD, "passwordRepeat" to NEW_PASSWORD)
        val preview = admin.post("/_admin/new", *fields, ACTION_FIELD to PREVIEW_ACTION).bodyAsText()
        // The browser sends back what the preview page holds
        val sentAgain = Regex("name=\"(password|passwordRepeat)\"[^>]*value=\"([^\"]*)\"").findAll(preview).associate { it.groupValues[1] to it.groupValues[2] }

        admin.post("/_admin/new", "path" to "neu", "title" to "Neu", "markdown" to "geheim", *sentAgain.toList().toTypedArray())

        assertEquals("geheim", assertIs<ResultOf2.Success<String>>(admin.store.decrypt("neu", NEW_PASSWORD)).value)
    }

    @Test
    fun `the preview of a protected sheet keeps lock, password and choice and saves nothing`() = testApplication {
        val sheet = protectedSheet()
        val admin = admin(sheet)

        val response = admin.save(sheet, "protection" to "remove", "currentPassword" to OLD_PASSWORD, "markdown" to "**fett**", ACTION_FIELD to PREVIEW_ACTION)

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertContains(body, "<strong>fett</strong>")
        assertContains(body, "name=\"updated\" value=\"${sheet.updated}\"")
        assertContains(body, "name=\"currentPassword\" value=\"$OLD_PASSWORD\"")
        assertContains(body, "value=\"remove\" checked")
        assertEquals(sheet, admin.store.find("privat"))
    }

    @Test
    fun `the preview of an outdated editor is a conflict`() = testApplication {
        val sheet = plainSheet("einkauf")
        val admin = admin(sheet)
        admin.save(sheet, "protection" to "none")

        assertEquals(HttpStatusCode.Conflict, admin.save(sheet, "protection" to "none", ACTION_FIELD to PREVIEW_ACTION).status)
    }

    @Test
    fun `an oversized text is not rendered as preview`() = testApplication {
        val admin = admin(config = testConfig(maxSheetBytes = 1024))

        val body = admin.post("/_admin/new", "path" to "neu", "title" to "Neu", "markdown" to "a".repeat(1025), ACTION_FIELD to PREVIEW_ACTION).bodyAsText()

        assertContains(body, "Inhalt zu groß")
        assertFalse(body.contains("class=\"preview\""))
    }

    @Test
    fun `enter in a text field saves, because the save button comes first`() = testApplication {
        val admin = admin()

        val body = admin.get("/_admin/new").bodyAsText()

        assertTrue(body.indexOf("type=\"submit\" value=\"Anlegen\"") in 0 until body.indexOf("value=\"preview\""), body)
    }

    @Test
    fun `a validation error keeps the password fields filled`() = testApplication {
        val admin = admin()

        val response = admin.post("/_admin/new", "path" to "neu", "title" to "", "markdown" to "x", "password" to NEW_PASSWORD, "passwordRepeat" to NEW_PASSWORD)

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertContains(body, "id=\"password\" minlength=\"12\" autocomplete=\"new-password\" value=\"$NEW_PASSWORD\"")
        assertContains(body, "id=\"passwordRepeat\" minlength=\"12\" autocomplete=\"new-password\" value=\"$NEW_PASSWORD\"")
    }

    @Test
    fun `the editor of a plain sheet keeps the content exactly`() = testApplication {
        val sheet = plainSheet("einkauf", markdown = "\nfirst line was empty <b>")
        val admin = admin(sheet)

        val body = admin.get("/_admin/edit/${sheet.id}").bodyAsText()

        assertContains(body, "<textarea rows=\"20\" id=\"markdown\" name=\"markdown\">\n\nfirst line was empty &lt;b&gt;</textarea>")
        assertContains(body, "name=\"updated\" value=\"${sheet.updated}\"")
        assertFalse(body.contains("currentPassword"))
    }

    @Test
    fun `an unprotected sheet stays unprotected`() = testApplication {
        val sheet = plainSheet("einkauf")
        val admin = admin(sheet)

        val response = admin.save(sheet, "protection" to "none", "title" to "Neu")

        assertEquals(HttpStatusCode.SeeOther, response.status)
        val saved = assertNotNull(admin.store.find("einkauf"))
        assertEquals(SheetContent.Plain("new content"), saved.content)
        assertEquals("Neu", saved.title)
        assertTrue(saved.updated > sheet.updated)
    }

    @Test
    fun `an unprotected sheet gets a password`() = testApplication {
        val sheet = plainSheet("einkauf")
        val admin = admin(sheet)

        admin.save(sheet, "protection" to "set", "password" to NEW_PASSWORD, "passwordRepeat" to NEW_PASSWORD)

        assertEquals("new content", assertIs<ResultOf2.Success<String>>(admin.store.decrypt("einkauf", NEW_PASSWORD)).value)
    }

    @Test
    fun `a protected sheet asks for its password before the editor opens`() = testApplication {
        val sheet = protectedSheet()
        val admin = admin(sheet)

        val form = admin.get("/_admin/edit/${sheet.id}").bodyAsText()
        val wrong = admin.post("/_admin/edit/${sheet.id}/unlock", "password" to "wrong-password")
        val right = admin.post("/_admin/edit/${sheet.id}/unlock", "password" to OLD_PASSWORD)

        assertContains(form, "action=\"/_admin/edit/${sheet.id}/unlock\"")
        assertFalse(form.contains("old content"))
        assertEquals(HttpStatusCode.Forbidden, wrong.status)
        assertEquals(HttpStatusCode.OK, right.status)
        val editor = right.bodyAsText()
        assertContains(editor, "old content</textarea>")
        assertContains(editor, "name=\"currentPassword\" value=\"$OLD_PASSWORD\"")
        assertContains(editor, "value=\"keep\" checked")
    }

    @Test
    fun `a protected sheet keeps its password`() = testApplication {
        val sheet = protectedSheet()
        val admin = admin(sheet)

        admin.save(sheet, "protection" to "keep", "currentPassword" to OLD_PASSWORD)

        assertEquals("new content", assertIs<ResultOf2.Success<String>>(admin.store.decrypt("privat", OLD_PASSWORD)).value)
    }

    @Test
    fun `a protected sheet gets a new password`() = testApplication {
        val sheet = protectedSheet()
        val admin = admin(sheet)

        admin.save(sheet, "protection" to "change", "currentPassword" to OLD_PASSWORD, "password" to NEW_PASSWORD, "passwordRepeat" to NEW_PASSWORD)

        assertEquals("new content", assertIs<ResultOf2.Success<String>>(admin.store.decrypt("privat", NEW_PASSWORD)).value)
        assertIs<ResultOf2.Failure<UnlockError>>(admin.store.decrypt("privat", OLD_PASSWORD))
    }

    @Test
    fun `a protected sheet loses its protection`() = testApplication {
        val sheet = protectedSheet()
        val admin = admin(sheet)

        admin.save(sheet, "protection" to "remove", "currentPassword" to OLD_PASSWORD)

        assertEquals(SheetContent.Plain("new content"), admin.store.content("privat"))
    }

    @Test
    fun `a manipulated current password changes nothing`() = testApplication {
        val sheet = protectedSheet()
        val admin = admin(sheet)

        val response = admin.save(sheet, "protection" to "remove", "currentPassword" to "guessed-password")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(sheet, admin.store.find("privat"))
    }

    @Test
    fun `a choice that does not fit the sheet is rejected`() = testApplication {
        val sheet = plainSheet("einkauf")
        val admin = admin(sheet)

        val response = admin.save(sheet, "protection" to "keep")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "Ungültige Auswahl beim Schutz")
        assertEquals(sheet, admin.store.find("einkauf"))
    }

    @Test
    fun `an outdated editor is a conflict`() = testApplication {
        val sheet = plainSheet("einkauf")
        val admin = admin(sheet)
        admin.save(sheet, "protection" to "none")

        val response = admin.save(sheet, "protection" to "none", "markdown" to "second tab")

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(SheetContent.Plain("new content"), admin.store.content("einkauf"))
    }

    @Test
    fun `a new path renames the sheet`() = testApplication {
        val sheet = plainSheet("einkauf")
        val admin = admin(sheet, plainSheet("belegt"))

        val taken = admin.save(sheet, "protection" to "none", "path" to "belegt")
        val renamed = admin.save(sheet, "protection" to "none", "path" to "einkauf/neu")

        assertEquals(HttpStatusCode.BadRequest, taken.status)
        assertContains(taken.bodyAsText(), "Pfad bereits vergeben")
        assertEquals(HttpStatusCode.SeeOther, renamed.status)
        assertNull(admin.store.find("einkauf"))
        assertEquals(sheet.id, admin.store.find("einkauf/neu")?.id)
    }

    @Test
    fun `a sheet is deleted after confirmation`() = testApplication {
        val sheet = protectedSheet()
        val admin = admin(sheet)

        val confirm = admin.get("/_admin/delete/${sheet.id}")
        val delete = admin.post("/_admin/delete/${sheet.id}")
        val again = admin.post("/_admin/delete/${sheet.id}")

        assertContains(confirm.bodyAsText(), "wirklich löschen?")
        assertEquals(HttpStatusCode.SeeOther, delete.status)
        assertNull(admin.store.find("privat"))
        assertTrue(admin.dir.listDirectoryEntries().isEmpty())
        assertEquals(HttpStatusCode.NotFound, again.status)
    }

    @Test
    fun `unknown ids answer 404`() = testApplication {
        val admin = admin()

        listOf("/_admin/edit/nope", "/_admin/delete/nope").forEach { assertEquals(HttpStatusCode.NotFound, admin.get(it).status, it) }
    }

    @Test
    fun `posts from a foreign origin or without origin are rejected`() = testApplication {
        val admin = admin(plainSheet("einkauf"))
        val sheet = assertNotNull(admin.store.find("einkauf"))

        val foreign = admin.client.submitForm("/_admin/delete/${sheet.id}") {
            header(HttpHeaders.Cookie, admin.cookie)
            header(HttpHeaders.Origin, "https://evil.example")
        }
        val missing = admin.client.submitForm("/_admin/delete/${sheet.id}") { header(HttpHeaders.Cookie, admin.cookie) }
        val nullOrigin = admin.client.submitForm("/_admin/delete/${sheet.id}", parameters { }) {
            header(HttpHeaders.Cookie, admin.cookie)
            header(HttpHeaders.Origin, "null")
        }

        listOf(foreign, missing, nullOrigin).forEach { assertEquals(HttpStatusCode.Forbidden, it.status) }
        assertNotNull(admin.store.find("einkauf"))
    }
}
