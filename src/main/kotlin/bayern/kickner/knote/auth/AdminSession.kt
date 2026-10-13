package bayern.kickner.knote.auth

import bayern.kickner.knote.config.AppConfig
import bayern.kickner.knote.i18n.texts
import bayern.kickner.knote.web.PageLink
import bayern.kickner.knote.web.respondMessage
import io.ktor.server.application.ApplicationCall
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable

/** Name of the admin login cookie. */
const val ADMIN_COOKIE = "knote_admin"

/** Name of the authentication provider that guards the admin pages. */
const val ADMIN_AUTH = "admin"

/**
 * Admin login, kept in a cookie that is signed, not encrypted: the content is not secret.
 *
 * @property sub OIDC subject of the admin.
 * @property name Display name from the provider for "logged in as", empty if it sent none.
 * @property expiresAt End of the login as Unix milliseconds. Checked on every request, so a stolen cookie expires on
 * the server as well, not only through `Max-Age` in the browser.
 */
@Serializable
data class AdminSession(val sub: String, val expiresAt: Long, val name: String = "") {
    /** True while the login has not expired and [sub] is still an admin (see [isAdmin]). */
    fun isValid(now: Long, allowedSubjects: List<String>): Boolean = expiresAt > now && isAdmin(sub, allowedSubjects)
}

/**
 * True if [sub] may use the admin area. With empty [allowedSubjects] that is every account the OIDC provider lets
 * through, so access is managed at the provider alone. Otherwise only the listed accounts.
 */
fun isAdmin(sub: String, allowedSubjects: List<String>): Boolean = allowedSubjects.isEmpty() || sub in allowedSubjects

/**
 * True if the request carries a valid admin login. For pages outside the admin area that offer admin extras, such as
 * the edit link on a sheet. Grants nothing by itself, every admin route checks the login again.
 */
fun ApplicationCall.hasAdminLogin(config: AppConfig, clock: () -> Long): Boolean =
    sessions.get<AdminSession>()?.isValid(clock(), config.oidc.allowedSubjects) == true

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
                call.texts.sessionExpiredHeading,
                call.texts.sessionExpiredText,
                PageLink(LOGIN_PATH, call.texts.logIn),
            )
        }
    }
}
