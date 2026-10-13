package bayern.kickner.knote

import bayern.kickner.knote.auth.ADMIN_COOKIE
import bayern.kickner.knote.auth.STATE_COOKIE
import bayern.kickner.knote.config.AppConfig
import bayern.kickner.knote.config.OidcConfig
import bayern.kickner.knote.crypto.CryptoGate
import bayern.kickner.knote.storage.Sheet
import bayern.kickner.knote.storage.SheetContent
import bayern.kickner.knote.storage.SheetStore
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.parameters
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import kotnexlib.ResultOf2
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId

internal const val ADMIN_SUB = "admin-sub"
internal const val PUBLIC_BASE_URL = "https://notes.example.org"
internal const val PROVIDER = "https://id.example.org"
internal const val CLIENT_ID = "knote-test"
internal const val CLIENT_SECRET = "client-secret-value"
internal const val TEST_SIGN_KEY = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

/** 13.10.2026 12:00:00 UTC, the fixed "now" of route tests. */
internal const val FIXED_NOW = 1_791_892_800_000L

internal fun testConfig(
    dataDir: String = "data",
    trustedProxyIp: String = "10.0.0.2",
    unlockAttemptsPerMinute: Int = 100,
    loginAttemptsPerMinute: Int = 100,
    allowedSubjects: List<String> = listOf(ADMIN_SUB),
    sessionHours: Int = 12,
    maxSheetBytes: Int = 1_048_576,
    language: String = "en",
) = AppConfig(
    publicBaseUrl = PUBLIC_BASE_URL,
    dataDir = dataDir,
    trustedProxyIp = trustedProxyIp,
    sessionSignKey = TEST_SIGN_KEY,
    sessionHours = sessionHours,
    unlockAttemptsPerMinute = unlockAttemptsPerMinute,
    loginAttemptsPerMinute = loginAttemptsPerMinute,
    maxSheetBytes = maxSheetBytes,
    language = language,
    oidc = OidcConfig(
        authorizeUrl = "$PROVIDER/authorize",
        tokenUrl = "$PROVIDER/api/oidc/token",
        userinfoUrl = "$PROVIDER/api/oidc/userinfo",
        clientId = CLIENT_ID,
        clientSecret = CLIENT_SECRET,
        allowedSubjects = allowedSubjects,
    ),
)

internal fun tempDir(): Path = Files.createTempDirectory("knote-test").also { it.toFile().deleteOnExit() }

internal fun emptyStore(dir: Path = tempDir()): SheetStore = (SheetStore.load(dir) as ResultOf2.Success).value

internal fun plainSheet(path: String, markdown: String = "# $path", title: String = path, updated: Long = FIXED_NOW) =
    Sheet(SheetStore.newId(), path, title, SheetContent.Plain(markdown), ADMIN_SUB, FIXED_NOW, updated)

/** Mutable time for route tests, read by the app through its clock. */
internal class TestClock(var now: Long = FIXED_NOW) : () -> Long {
    override fun invoke() = now
}

/**
 * Hosts KNote with a fake OIDC provider at [PROVIDER]. The token endpoint only accepts a POST with the right client
 * secret and code, the userinfo endpoint answers [userinfoBody] for the issued token.
 *
 * @return a client that does not follow redirects, so tests can inspect them.
 */
internal fun ApplicationTestBuilder.knoteApp(
    store: SheetStore = emptyStore(),
    config: AppConfig = testConfig(),
    clock: TestClock = TestClock(),
    userinfoBody: String = """{"sub": "$ADMIN_SUB"}""",
): HttpClient {
    externalServices {
        hosts(PROVIDER) {
            routing {
                post("/api/oidc/token") {
                    val form = call.receiveParameters()
                    val valid = form["client_id"] == CLIENT_ID && form["client_secret"] == CLIENT_SECRET &&
                        form["code"] == "good-code" && form["grant_type"] == "authorization_code"
                    if (valid) call.respondText("""{"access_token": "token-1", "token_type": "Bearer", "expires_in": 3600}""", ContentType.Application.Json)
                    else call.respondText("""{"error": "invalid_grant"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                }
                get("/api/oidc/userinfo") {
                    val authorized = call.request.headers[HttpHeaders.Authorization] == "Bearer token-1"
                    if (authorized) call.respondText(userinfoBody, ContentType.Application.Json)
                    else call.respond(HttpStatusCode.Unauthorized)
                }
            }
        }
    }
    val providerClient = createClient { }
    application { knote(config, store, providerClient, CryptoGate(1), clock, ZoneId.of("Europe/Berlin")) }
    return createClient { followRedirects = false }
}

/** Starts a login and returns the state from the redirect to the provider. */
internal suspend fun HttpClient.startLogin(): String {
    val redirect = get("/_auth/login").headers[HttpHeaders.Location] ?: error("login did not redirect")
    return Url(redirect).parameters["state"] ?: error("no state in $redirect")
}

/** Runs the whole login against the fake provider and returns the admin cookie as `name=value`. */
internal suspend fun HttpClient.login(): String {
    val state = startLogin()
    val callback = get("/_auth/callback?code=good-code&state=$state") { header(HttpHeaders.Cookie, "$STATE_COOKIE=$state") }
    return callback.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        .firstOrNull { it.startsWith("$ADMIN_COOKIE=") }?.substringBefore(';')
        ?: error("no admin cookie, status ${callback.status}")
}

/** POSTs a form like a browser on KNote's own pages would: with the admin cookie and KNote's origin. */
internal suspend fun HttpClient.adminPost(url: String, cookie: String?, vararg fields: Pair<String, String>): HttpResponse =
    submitForm(url, parameters { fields.forEach { (name, value) -> append(name, value) } }) {
        if (cookie != null) header(HttpHeaders.Cookie, cookie)
        header(HttpHeaders.Origin, PUBLIC_BASE_URL)
    }

/** GET with the admin cookie. */
internal suspend fun HttpClient.adminGet(url: String, cookie: String): HttpResponse = get(url) { header(HttpHeaders.Cookie, cookie) }
