package bayern.kickner.knote.auth

import bayern.kickner.knote.config.AppConfig
import bayern.kickner.knote.web.PageLink
import bayern.kickner.knote.web.respondMessage
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.session
import io.ktor.server.response.respondRedirect
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.SessionsConfig
import io.ktor.server.sessions.cookie
import kotlinx.serialization.Serializable

/** Name of the admin login cookie. */
const val ADMIN_COOKIE = "knote_admin"

/** Name of the authentication provider that guards the admin pages. */
const val ADMIN_AUTH = "admin"

/**
 * Admin login, kept in a cookie that is signed, not encrypted: the content is not secret.
 *
 * @property sub OIDC subject of the admin.
 * @property expiresAt End of the login as Unix milliseconds. Checked on every request, so a stolen cookie expires on
 * the server as well, not only through `Max-Age` in the browser.
 */
@Serializable
data class AdminSession(val sub: String, val expiresAt: Long) {
    /** True while the login has not expired and [sub] is still configured as admin. */
    fun isValid(now: Long, allowedSubjects: List<String>): Boolean = expiresAt > now && sub in allowedSubjects
}

/**
 * Registers the admin cookie: HttpOnly, Secure and `SameSite=Lax`, signed with HMAC-SHA256. Ktor does not check
 * whether the connection is HTTPS when it sets a Secure cookie, so it works behind Zoraxy, which speaks HTTPS to
 * the browser and HTTP to KNote.
 */
fun SessionsConfig.adminCookie(config: AppConfig) {
    cookie<AdminSession>(ADMIN_COOKIE) {
        cookie.path = "/"
        cookie.httpOnly = true
        cookie.secure = true
        cookie.maxAgeInSeconds = config.sessionHours * 3600L
        cookie.extensions["SameSite"] = "Lax"
        transform(SessionTransportTransformerMessageAuthentication(config.sessionSignKey.hexToByteArray()))
    }
}

/**
 * Registers [ADMIN_AUTH]: a valid [AdminSession] is required. Without one, a GET is redirected to the login. Any
 * other method gets a 401 page: a redirect after a form post would end at the OIDC provider, which the
 * `form-action` rule of the Content Security Policy blocks.
 *
 * @param clock Current time in Unix milliseconds, injectable for tests.
 */
fun AuthenticationConfig.adminSession(config: AppConfig, clock: () -> Long) {
    session<AdminSession>(ADMIN_AUTH) {
        validate { session -> session.takeIf { it.isValid(clock(), config.oidc.allowedSubjects) } }
        challenge {
            if (call.request.local.method == HttpMethod.Get) call.respondRedirect(LOGIN_PATH)
            else call.respondMessage(
                HttpStatusCode.Unauthorized,
                "Sitzung abgelaufen",
                "Bitte neu anmelden. Die Eingaben wurden nicht gespeichert.",
                PageLink(LOGIN_PATH, "Anmelden"),
            )
        }
    }
}
