package bayern.kickner.knote.web

import bayern.kickner.knote.crypto.encryptContent
import bayern.kickner.knote.emptyStore
import bayern.kickner.knote.knoteApp
import bayern.kickner.knote.plainSheet
import bayern.kickner.knote.storage.SheetContent
import bayern.kickner.knote.storage.SheetStore
import bayern.kickner.knote.testConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

private const val PASSWORD = "password-1234"

private fun storeWithSheets(): SheetStore = emptyStore().apply {
    save(plainSheet("einkauf", markdown = "# Liste\n- Brot", title = "Einkauf <b>"))
    save(plainSheet("privat/notiz", title = "Privat").copy(content = encryptContent("geheimer Inhalt", PASSWORD)))
}

private suspend fun HttpClient.unlock(path: String, password: String, forwardedFor: String? = null): HttpResponse =
    submitForm("/$path", parameters { append("password", password) }) {
        if (forwardedFor != null) header(HttpHeaders.XForwardedFor, forwardedFor)
    }

class PublicRoutesTest {

    @Test
    fun `unknown and invalid paths answer 404`() = testApplication {
        val client = knoteApp(storeWithSheets())

        listOf("/gibt-es-nicht", "/Einkauf", "/einkauf/", "/_nope", "/a/../einkauf", "/einkauf.json").forEach { path ->
            val response = client.get(path)
            assertEquals(HttpStatusCode.NotFound, response.status, path)
            assertContains(response.bodyAsText(), "Not found")
        }
    }

    @Test
    fun `a plain sheet is rendered with escaped title`() = testApplication {
        val client = knoteApp(storeWithSheets())

        val response = client.get("/einkauf")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertContains(body, "<title>Einkauf &lt;b&gt;</title>")
        assertContains(body, "<h1>Liste</h1>")
        assertContains(body, "<li>Brot</li>")
        assertContains(body, "Last changed: 13.10.2026 14:00:00 CEST")
    }

    @Test
    fun `a protected sheet shows only the password form`() = testApplication {
        val client = knoteApp(storeWithSheets())

        val body = client.get("/privat/notiz").bodyAsText()

        assertContains(body, "<form action=\"/privat/notiz\" method=\"post\">")
        assertContains(body, "name=\"password\"")
        assertFalse(body.contains("geheimer Inhalt"))
    }

    @Test
    fun `the right password shows the content and is not cached`() = testApplication {
        val client = knoteApp(storeWithSheets())

        val response = client.unlock("privat/notiz", PASSWORD)

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertContains(response.bodyAsText(), "<p>geheimer Inhalt</p>")
        assertContains(response.bodyAsText(), "Last changed: 13.10.2026 14:00:00 CEST")
    }

    @Test
    fun `a wrong password answers 403 with the form`() = testApplication {
        val client = knoteApp(storeWithSheets())

        val response = client.unlock("privat/notiz", "wrong-password")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val body = response.bodyAsText()
        assertContains(body, "Wrong password")
        assertFalse(body.contains("geheimer Inhalt"))
    }

    @Test
    fun `a post to a plain sheet redirects to it`() = testApplication {
        val client = knoteApp(storeWithSheets())

        val response = client.unlock("einkauf", "anything")

        assertEquals(HttpStatusCode.SeeOther, response.status)
        assertEquals("/einkauf", response.headers[HttpHeaders.Location])
    }

    @Test
    fun `a post to an unknown sheet answers 404`() = testApplication {
        val client = knoteApp(storeWithSheets())

        assertEquals(HttpStatusCode.NotFound, client.unlock("gibt-es-nicht", "x").status)
    }

    @Test
    fun `damaged encrypted data answers 500`() = testApplication {
        val store = emptyStore().apply { save(plainSheet("kaputt").copy(content = SheetContent.Encrypted("broken"))) }
        val client = knoteApp(store)

        assertEquals(HttpStatusCode.InternalServerError, client.unlock("kaputt", PASSWORD).status)
    }

    @Test
    fun `password attempts are limited per client and a forged header does not help`() = testApplication {
        val client = knoteApp(storeWithSheets(), testConfig(unlockAttemptsPerMinute = 3))

        val statuses = (1..5).map { client.unlock("privat/notiz", "wrong-$it", forwardedFor = "1.2.3.$it").status }

        assertEquals(List(3) { HttpStatusCode.Forbidden } + List(2) { HttpStatusCode.TooManyRequests }, statuses)
    }

    @Test
    fun `behind the trusted proxy every client has its own limit`() = testApplication {
        // The test engine reports "localhost" as peer, which stands in for Zoraxy here
        val client = knoteApp(storeWithSheets(), testConfig(trustedProxyIp = "localhost", unlockAttemptsPerMinute = 1))

        assertEquals(HttpStatusCode.Forbidden, client.unlock("privat/notiz", "wrong", forwardedFor = "198.51.100.1").status)
        assertEquals(HttpStatusCode.TooManyRequests, client.unlock("privat/notiz", "wrong", forwardedFor = "9.9.9.9, 198.51.100.1").status)
        assertEquals(HttpStatusCode.Forbidden, client.unlock("privat/notiz", "wrong", forwardedFor = "198.51.100.2").status)
    }

    @Test
    fun `pages are english by default`() = testApplication {
        val client = knoteApp(storeWithSheets())

        val body = client.get("/privat/notiz").bodyAsText()

        assertContains(body, "<html lang=\"en\">")
        assertContains(body, "This sheet is protected with a password.")
        assertContains(body, "value=\"Unlock\"")
    }

    @Test
    fun `pages follow the configured language`() = testApplication {
        val client = knoteApp(storeWithSheets(), testConfig(language = "de"))

        val notFound = client.get("/gibt-es-nicht").bodyAsText()
        val wrong = client.unlock("privat/notiz", "wrong-password").bodyAsText()

        assertContains(notFound, "<html lang=\"de\">")
        assertContains(notFound, "Nicht gefunden")
        assertContains(wrong, "Falsches Passwort")
        assertContains(wrong, "value=\"Entsperren\"")
    }

    @Test
    fun `every page links to the repository in the footer by default`() = testApplication {
        val client = knoteApp(storeWithSheets())

        listOf("/", "/einkauf", "/gibt-es-nicht").forEach { path ->
            assertContains(client.get(path).bodyAsText(), "Powered by <a href=\"https://github.com/nexus421/KNote\">KNote</a>", message = path)
        }
    }

    @Test
    fun `the footer can be switched off`() = testApplication {
        val client = knoteApp(storeWithSheets(), testConfig(showPoweredBy = false))

        assertFalse(client.get("/einkauf").bodyAsText().contains("Powered by"))
    }

    @Test
    fun `the start page offers the login`() = testApplication {
        val client = knoteApp()

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "href=\"/_auth/login\"")
        assertContains(response.bodyAsText(), "<main class=\"narrow start\">")
    }

    @Test
    fun `a plain sheet links to its markdown file and the file is the raw markdown`() = testApplication {
        val client = knoteApp(storeWithSheets())

        val page = client.get("/einkauf").bodyAsText()
        val file = client.get("/einkauf.md")

        assertContains(page, "href=\"/einkauf.md\"")
        assertEquals(HttpStatusCode.OK, file.status)
        assertEquals("# Liste\n- Brot", file.bodyAsText())
        assertEquals("text/markdown; charset=UTF-8", file.headers[HttpHeaders.ContentType])
        assertEquals("attachment; filename=einkauf.md", file.headers[HttpHeaders.ContentDisposition])
    }

    @Test
    fun `the markdown file of a protected sheet needs the password`() = testApplication {
        val client = knoteApp(storeWithSheets())

        val form = client.get("/privat/notiz.md").bodyAsText()
        val wrong = client.unlock("privat/notiz.md", "wrong-password")
        val file = client.unlock("privat/notiz.md", PASSWORD)

        assertContains(form, "<form action=\"/privat/notiz.md\" method=\"post\">")
        assertFalse(form.contains("geheimer Inhalt"))
        assertEquals(HttpStatusCode.Forbidden, wrong.status)
        assertEquals("geheimer Inhalt", file.bodyAsText())
        assertEquals("attachment; filename=notiz.md", file.headers[HttpHeaders.ContentDisposition])
        assertEquals("no-store", file.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `an unlocked sheet offers the download with the password`() = testApplication {
        val client = knoteApp(storeWithSheets())

        val page = client.unlock("privat/notiz", PASSWORD).bodyAsText()

        assertContains(page, "<form action=\"/privat/notiz.md\" method=\"post\" class=\"no-print\">")
        assertContains(page, "name=\"password\" value=\"$PASSWORD\"")
    }

    @Test
    fun `markdown downloads count against the password limit`() = testApplication {
        val client = knoteApp(storeWithSheets(), testConfig(unlockAttemptsPerMinute = 1))

        assertEquals(HttpStatusCode.Forbidden, client.unlock("privat/notiz.md", "wrong").status)
        assertEquals(HttpStatusCode.TooManyRequests, client.unlock("privat/notiz.md", PASSWORD).status)
    }

    @Test
    fun `unknown markdown files answer 404`() = testApplication {
        val client = knoteApp(storeWithSheets())

        listOf("/gibt-es-nicht.md", "/einkauf.md.md", "/.md").forEach { assertEquals(HttpStatusCode.NotFound, client.get(it).status, it) }
    }

    @Test
    fun `an exceeded limit shows a page instead of an empty answer`() = testApplication {
        val client = knoteApp(storeWithSheets(), testConfig(unlockAttemptsPerMinute = 1))
        client.unlock("privat/notiz", "wrong")

        val response = client.unlock("privat/notiz", "wrong")

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertContains(response.bodyAsText(), "<h1>Too many attempts</h1>")
    }

    @Test
    fun `a body over the limit shows a page`() = testApplication {
        val client = knoteApp(storeWithSheets(), testConfig(maxSheetBytes = 1024))

        val response = client.unlock("privat/notiz", "x".repeat(200_000))

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertContains(response.bodyAsText(), "<h1>Too large</h1>")
    }

    @Test
    fun `an unexpected error shows a page`() = testApplication {
        val client = knoteApp()
        application { routing { get("/_boom") { error("boom") } } }

        val response = client.get("/_boom")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertContains(response.bodyAsText(), "Something went wrong.")
        assertFalse(response.bodyAsText().contains("boom"))
    }

    @Test
    fun `the health check answers ok without login`() = testApplication {
        val client = knoteApp()

        val response = client.get("/_health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
    }

    @Test
    fun `the stylesheet is served`() = testApplication {
        val client = knoteApp()

        val response = client.get("/_static/knote.css")

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "--accent")
    }

    @Test
    fun `security headers are on every response`() = testApplication {
        val client = knoteApp(storeWithSheets(), testConfig(unlockAttemptsPerMinute = 1))
        val responses = listOf(
            client.get("/einkauf"),
            client.get("/gibt-es-nicht"),
            client.get("/_admin"),
            client.unlock("privat/notiz", "wrong"),
            client.unlock("privat/notiz", "wrong"),
            client.submitForm("/_admin/new", parameters { append("path", "x") }),
        )
        assertEquals(
            listOf(200, 404, 302, 403, 429, 403),
            responses.map { it.status.value },
        )

        responses.forEach { response ->
            assertEquals(CONTENT_SECURITY_POLICY, response.headers["Content-Security-Policy"])
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertEquals("same-origin", response.headers["Referrer-Policy"])
            assertEquals("noindex, nofollow", response.headers["X-Robots-Tag"])
        }
        assertNull(responses.first().headers[HttpHeaders.CacheControl])
    }
}
