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
    fun `the start page offers the login`() = testApplication {
        val client = knoteApp()

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "href=\"/_auth/login\"")
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
