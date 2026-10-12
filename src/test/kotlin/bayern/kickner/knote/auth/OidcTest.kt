package bayern.kickner.knote.auth

import bayern.kickner.knote.ADMIN_SUB
import bayern.kickner.knote.PROVIDER
import bayern.kickner.knote.PUBLIC_BASE_URL
import bayern.kickner.knote.TEST_SIGN_KEY
import bayern.kickner.knote.TestClock
import bayern.kickner.knote.adminGet
import bayern.kickner.knote.adminPost
import bayern.kickner.knote.knoteApp
import bayern.kickner.knote.login
import bayern.kickner.knote.startLogin
import bayern.kickner.knote.testConfig
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.parseServerSetCookieHeader
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OidcTest {

    @Test
    fun `state matches only an equal non empty cookie`() {
        assertTrue(stateMatches("abc123", "abc123"))
        assertFalse(stateMatches("abc123", "abc124"))
        assertFalse(stateMatches(null, "abc123"))
        assertFalse(stateMatches("abc123", null))
        assertFalse(stateMatches("", ""))
    }

    @Test
    fun `userinfo decides by sub`() {
        val allowed = listOf("sub-1")

        assertEquals(LoginDecision.Allowed("sub-1"), evaluateUserinfo("""{"sub": "sub-1", "name": "A"}""", allowed))
        assertEquals(LoginDecision.Denied("sub-2"), evaluateUserinfo("""{"sub": "sub-2"}""", allowed))
        listOf("""{"name": "A"}""", """{"sub": null}""", """{"sub": ""}""", """{"sub": {}}""", "not json", "[]").forEach { body ->
            assertEquals(LoginDecision.Failed("userinfo response has no sub"), evaluateUserinfo(body, allowed), body)
        }
    }

    @Test
    fun `a session is valid until it expires and while its sub is configured`() {
        val session = AdminSession("sub-1", expiresAt = 1000)

        assertTrue(session.isValid(999, listOf("sub-1")))
        assertFalse(session.isValid(1000, listOf("sub-1")))
        assertFalse(session.isValid(999, listOf("sub-2")))
    }

    @Test
    fun `the sign key is read case insensitive`() {
        assertContentEquals(TEST_SIGN_KEY.hexToByteArray(), TEST_SIGN_KEY.uppercase().hexToByteArray())
    }

    @Test
    fun `login redirects to the provider and sets the state cookie`() = testApplication {
        val client = knoteApp()

        val response = client.get(LOGIN_PATH)

        assertEquals(HttpStatusCode.Found, response.status)
        val redirect = Url(response.headers[HttpHeaders.Location].orEmpty())
        assertEquals("$PROVIDER/authorize", response.headers[HttpHeaders.Location].orEmpty().substringBefore("?"))
        assertEquals("knote-test", redirect.parameters["client_id"])
        assertEquals("$PUBLIC_BASE_URL$CALLBACK_PATH", redirect.parameters["redirect_uri"])
        assertEquals("openid", redirect.parameters["scope"])
        assertEquals("code", redirect.parameters["response_type"])
        val cookie = parseServerSetCookieHeader(response.headers.getAll(HttpHeaders.SetCookie).orEmpty().single { it.startsWith("$STATE_COOKIE=") })
        assertEquals(redirect.parameters["state"], cookie.value)
        assertEquals(600, cookie.maxAge)
        assertEquals(CALLBACK_PATH, cookie.path)
        assertTrue(cookie.secure)
        assertTrue(cookie.httpOnly)
        assertEquals("Lax", cookie.extensions["SameSite"])
    }

    @Test
    fun `a full login sets a signed admin cookie and clears the state`() = testApplication {
        val client = knoteApp()
        val state = client.startLogin()

        val response = client.get("$CALLBACK_PATH?code=good-code&state=$state") { header(HttpHeaders.Cookie, "$STATE_COOKIE=$state") }

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/_admin", response.headers[HttpHeaders.Location])
        val cookies = response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        assertContains(cookies.single { it.startsWith("$STATE_COOKIE=") }, "Max-Age=0")
        val admin = parseServerSetCookieHeader(cookies.single { it.startsWith("$ADMIN_COOKIE=") })
        assertEquals("/", admin.path)
        assertEquals(43200, admin.maxAge)
        assertTrue(admin.secure)
        assertTrue(admin.httpOnly)
        assertEquals("Lax", admin.extensions["SameSite"])
        assertContains(admin.value, "\"sub\":\"$ADMIN_SUB\"")
    }

    @Test
    fun `a state that does not match the cookie is rejected before the token request`() = testApplication {
        val client = knoteApp()
        val state = client.startLogin()

        val response = client.get("$CALLBACK_PATH?code=good-code&state=$state") { header(HttpHeaders.Cookie, "$STATE_COOKIE=other") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertContains(response.bodyAsText(), "Anmeldung fehlgeschlagen")
        assertFalse(response.headers.getAll(HttpHeaders.SetCookie).orEmpty().any { it.startsWith("$ADMIN_COOKIE=") })
    }

    @Test
    fun `a callback without state cookie is rejected`() = testApplication {
        val client = knoteApp()
        val state = client.startLogin()

        assertEquals(HttpStatusCode.Forbidden, client.get("$CALLBACK_PATH?code=good-code&state=$state").status)
    }

    @Test
    fun `a code the provider refuses is rejected`() = testApplication {
        val client = knoteApp()
        val state = client.startLogin()

        val response = client.get("$CALLBACK_PATH?code=bad-code&state=$state") { header(HttpHeaders.Cookie, "$STATE_COOKIE=$state") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `an error from the provider is rejected`() = testApplication {
        val client = knoteApp()

        assertEquals(HttpStatusCode.Forbidden, client.get("$CALLBACK_PATH?error=access_denied&state=x").status)
    }

    @Test
    fun `a sub that is not configured gets no access`() = testApplication {
        val client = knoteApp(userinfoBody = """{"sub": "someone-else"}""")
        val state = client.startLogin()

        val response = client.get("$CALLBACK_PATH?code=good-code&state=$state") { header(HttpHeaders.Cookie, "$STATE_COOKIE=$state") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertContains(response.bodyAsText(), "Kein Zugriff")
        assertFalse(response.headers.getAll(HttpHeaders.SetCookie).orEmpty().any { it.startsWith("$ADMIN_COOKIE=") })
    }

    @Test
    fun `userinfo without sub fails the login`() = testApplication {
        val client = knoteApp(userinfoBody = """{"name": "A"}""")
        val state = client.startLogin()

        val response = client.get("$CALLBACK_PATH?code=good-code&state=$state") { header(HttpHeaders.Cookie, "$STATE_COOKIE=$state") }

        assertEquals(HttpStatusCode.BadGateway, response.status)
    }

    @Test
    fun `the admin cookie expires on the server`() = testApplication {
        val clock = TestClock()
        val client = knoteApp(clock = clock)
        val cookie = client.login()
        assertEquals(HttpStatusCode.OK, client.adminGet("/_admin", cookie).status)

        clock.now += 12 * 3_600_000L

        val response = client.adminGet("/_admin", cookie)
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals(LOGIN_PATH, response.headers[HttpHeaders.Location])
    }

    @Test
    fun `a forged admin cookie is ignored`() = testApplication {
        val client = knoteApp()
        val cookie = client.login()
        val forged = cookie.replace(ADMIN_SUB, "other-sub")

        assertEquals(HttpStatusCode.Found, client.adminGet("/_admin", forged).status)
    }

    @Test
    fun `the start page sends a logged in admin to the overview`() = testApplication {
        val client = knoteApp()
        val cookie = client.login()

        val response = client.adminGet("/", cookie)

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/_admin", response.headers[HttpHeaders.Location])
    }

    @Test
    fun `logout clears the cookie and lands on the start page`() = testApplication {
        val client = knoteApp()
        val cookie = client.login()

        val response = client.adminPost(LOGOUT_PATH, cookie)

        assertEquals(HttpStatusCode.SeeOther, response.status)
        assertEquals("/", response.headers[HttpHeaders.Location])
        assertContains(response.headers.getAll(HttpHeaders.SetCookie).orEmpty().single { it.startsWith("$ADMIN_COOKIE=") }, "Max-Age=0")
    }

    @Test
    fun `logout from a foreign origin is rejected`() = testApplication {
        val client = knoteApp()
        val cookie = client.login()

        val response = client.submitForm(LOGOUT_PATH) {
            header(HttpHeaders.Cookie, cookie)
            header(HttpHeaders.Origin, "https://evil.example")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertFalse(response.headers.getAll(HttpHeaders.SetCookie).orEmpty().any { it.startsWith("$ADMIN_COOKIE=") })
    }

    @Test
    fun `logout is a post, a link cannot trigger it`() = testApplication {
        val client = knoteApp()
        val cookie = client.login()

        assertEquals(HttpStatusCode.NotFound, client.adminGet(LOGOUT_PATH, cookie).status)
    }

    @Test
    fun `login requests are rate limited`() = testApplication {
        val client = knoteApp(config = testConfig(loginAttemptsPerMinute = 2))

        val statuses = (1..3).map { client.get(LOGIN_PATH).status }

        assertEquals(listOf(HttpStatusCode.Found, HttpStatusCode.Found, HttpStatusCode.TooManyRequests), statuses)
    }
}
