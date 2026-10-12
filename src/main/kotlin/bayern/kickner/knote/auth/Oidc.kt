package bayern.kickner.knote.auth

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import bayern.kickner.knote.config.AppConfig
import bayern.kickner.knote.web.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.csrf.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.util.date.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotnexlib.ResultOf2
import java.security.MessageDigest

private const val TAG = "Oidc"

/** Name of the authentication provider for the OIDC code flow. */
const val OIDC_AUTH = "oidc"

/** Starts the login: redirects to the OIDC provider. */
const val LOGIN_PATH = "/_auth/login"

/** The OIDC provider redirects back here with `code` and `state`. */
const val CALLBACK_PATH = "/_auth/callback"

/** Ends the admin login (POST only). */
const val LOGOUT_PATH = "/_auth/logout"

/** Binds the OAuth state to the browser that started the login. */
const val STATE_COOKIE = "knote_oauth_state"

/** Rate limit of the two login endpoints, keyed by client IP. */
val LOGIN_RATE_LIMIT = RateLimitName("login")

/** Ten minutes are plenty for a login at the provider. */
private const val STATE_MAX_AGE_SECONDS = 600

/**
 * Outcome of a login after the provider answered the userinfo request.
 */
sealed interface LoginDecision {
    /** [sub] is configured as admin. */
    data class Allowed(val sub: String) : LoginDecision

    /** [sub] logged in at the provider but is not in the configured `allowedSubjects`. */
    data class Denied(val sub: String) : LoginDecision

    /** The provider did not answer as expected. [reason] is safe to log. */
    data class Failed(val reason: String) : LoginDecision
}

/**
 * The state cookie set when a login starts. It is only sent back to [CALLBACK_PATH]. `SameSite=Lax` instead of
 * `Strict`, because the return from the provider is a cross site navigation, for which `Strict` would hold it back.
 */
fun stateCookie(state: String): Cookie = Cookie(
    name = STATE_COOKIE,
    value = state,
    maxAge = STATE_MAX_AGE_SECONDS,
    path = CALLBACK_PATH,
    secure = true,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Lax"),
)

/** Deletes the state cookie after the callback, it is single use. */
fun expiredStateCookie(): Cookie = Cookie(
    name = STATE_COOKIE,
    value = "",
    maxAge = 0,
    expires = GMTDate.START,
    path = CALLBACK_PATH,
    secure = true,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Lax"),
)

/**
 * True if the [state] the provider sent back equals the one in the state cookie. Compared in constant time. A
 * missing value never matches. Without this check Ktor accepts any state (its default nonce manager is meant
 * for tests only), which allows login CSRF.
 */
fun stateMatches(cookieValue: String?, state: String?): Boolean {
    if (cookieValue.isNullOrEmpty() || state.isNullOrEmpty()) return false
    return MessageDigest.isEqual(cookieValue.toByteArray(), state.toByteArray())
}

/** Reads `sub` from the userinfo response [body] and checks it with [isAdmin]. */
fun evaluateUserinfo(body: String, allowedSubjects: List<String>): LoginDecision {
    val sub = runCatching { Json.parseToJsonElement(body).jsonObject["sub"]?.jsonPrimitive?.contentOrNull }.getOrNull()
    return when {
        sub.isNullOrBlank() -> LoginDecision.Failed("userinfo response has no sub")
        isAdmin(sub, allowedSubjects) -> LoginDecision.Allowed(sub)
        else -> LoginDecision.Denied(sub)
    }
}

/**
 * Calls the userinfo endpoint with the access token. The provider checks the token there, so KNote does not have
 * to validate an ID token (issuer, audience, expiry) itself.
 *
 * @return the response body, or a reason that is safe to log.
 */
suspend fun fetchUserinfo(client: HttpClient, userinfoUrl: String, accessToken: String): ResultOf2<String, String> {
    val response = runCatching { client.get(userinfoUrl) { bearerAuth(accessToken) } }
        .getOrElse { return ResultOf2.Failure("userinfo request failed: ${it.message}") }
    if (response.status.isSuccess().not()) return ResultOf2.Failure("userinfo answered ${response.status.value}")
    return runCatching { response.bodyAsText() }
        .fold({ ResultOf2.Success(it) }, { ResultOf2.Failure("userinfo response unreadable: ${it.message}") })
}

/**
 * Registers [OIDC_AUTH]: the authorization code flow as confidential client (client secret, `client_secret_post`).
 * The token request must be a POST (RFC 6749 section 3.2), Ktor's default is GET. Every failure of the flow, a
 * state mismatch included, ends on a 403 page.
 */
fun AuthenticationConfig.oidc(config: AppConfig, httpClient: HttpClient) {
    oauth(OIDC_AUTH) {
        client = httpClient
        urlProvider = { "${config.publicBaseUrl}$CALLBACK_PATH" }
        settings = OAuthServerSettings.OAuth2ServerSettings(
            name = "pocket-id",
            authorizeUrl = config.oidc.authorizeUrl,
            accessTokenUrl = config.oidc.tokenUrl,
            requestMethod = HttpMethod.Post,
            clientId = config.oidc.clientId,
            clientSecret = config.oidc.clientSecret,
            defaultScopes = listOf("openid"),
            onStateCreated = { call, state -> call.response.cookies.append(stateCookie(state)) },
            verifyState = { call, state -> stateMatches(call.request.cookies[STATE_COOKIE], state) },
        )
        fallback = { cause ->
            staticLog(KLogger.Level.WARN, TAG) { "Login failed: ${cause.message.toLogLine()}" }
            respondMessage(HttpStatusCode.Forbidden, "Anmeldung fehlgeschlagen", "Die Anmeldung wurde abgebrochen oder ist abgelaufen.", PageLink(LOGIN_PATH, "Erneut anmelden"))
        }
    }
}

/**
 * Login, callback and logout. The first two share the rate limit [LOGIN_RATE_LIMIT]. Logout is a POST behind the
 * CSRF check and lands on the start page, which does not log in again on its own.
 *
 * @param clock Current time in Unix milliseconds, injectable for tests.
 */
fun Route.authRoutes(config: AppConfig, httpClient: HttpClient, clock: () -> Long) {
    rateLimit(LOGIN_RATE_LIMIT) {
        authenticate(OIDC_AUTH) {
            // Without code and state the provider's challenge answers with the redirect, this handler only runs after a successful flow
            get(LOGIN_PATH) { call.respondRedirect(ADMIN_PATH) }
            get(CALLBACK_PATH) { call.completeLogin(config, httpClient, clock) }
        }
    }
    route(LOGOUT_PATH) {
        install(CSRF) { onlyFrom(config.publicBaseUrl) }
        post {
            call.sessions.clear<AdminSession>()
            call.respondSeeOther("/")
        }
    }
}

private suspend fun ApplicationCall.completeLogin(config: AppConfig, httpClient: HttpClient, clock: () -> Long) {
    response.cookies.append(expiredStateCookie())
    val token = principal<OAuthAccessTokenResponse.OAuth2>()
    val decision = when {
        token == null -> LoginDecision.Failed("no access token")
        else -> when (val userinfo = fetchUserinfo(httpClient, config.oidc.userinfoUrl, token.accessToken)) {
            is ResultOf2.Success -> evaluateUserinfo(userinfo.value, config.oidc.allowedSubjects)
            is ResultOf2.Failure -> LoginDecision.Failed(userinfo.value)
        }
    }

    when (decision) {
        is LoginDecision.Allowed -> {
            sessions.set(AdminSession(decision.sub, clock() + config.sessionHours * 3_600_000L))
            staticLog(KLogger.Level.INFO, TAG) { "Admin login sub=${decision.sub.toLogLine()}" }
            respondRedirect(ADMIN_PATH)
        }

        is LoginDecision.Denied -> {
            staticLog(KLogger.Level.WARN, TAG) { "Login denied for sub=${decision.sub.toLogLine()}" }
            respondMessage(HttpStatusCode.Forbidden, "Kein Zugriff", "Dieses Konto hat keinen Zugriff auf KNote.")
        }

        is LoginDecision.Failed -> {
            staticLog(KLogger.Level.WARN, TAG) { "Login failed: ${decision.reason.toLogLine()}" }
            respondMessage(HttpStatusCode.BadGateway, "Anmeldung fehlgeschlagen", "Der Anmeldedienst hat nicht wie erwartet geantwortet.", PageLink(LOGIN_PATH, "Erneut anmelden"))
        }
    }
}

/** Parts of these messages come from the request or the provider. Control characters could forge log lines. */
private fun String?.toLogLine(): String = orEmpty().map { if (it.isISOControl()) '?' else it }.joinToString("")
