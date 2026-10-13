package bayern.kickner.knote.web

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import bayern.kickner.knote.i18n.texts
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.plugins.csrf.CSRFConfig
import io.ktor.server.response.header

private const val TAG = "SecurityHeaders"

/**
 * No scripts at all, styles only from KNote, images from KNote or any HTTPS host (Markdown may embed external
 * images, their server then sees who views the sheet). Forms may only post to KNote itself. The separators between
 * the directives are part of the header syntax.
 */
const val CONTENT_SECURITY_POLICY =
    "default-src 'none'; style-src 'self'; img-src 'self' https:; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"

/**
 * `same-origin` keeps sheet paths away from other sites just like `no-referrer` would. `no-referrer` itself is not
 * an option: browsers then send `Origin: null` with every form post, and the CSRF check rejects all admin forms.
 */
const val REFERRER_POLICY = "same-origin"

/** Sets the security headers on every response, including errors and redirects. */
val SecurityHeaders = createApplicationPlugin("SecurityHeaders") {
    onCall { call ->
        call.response.header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", REFERRER_POLICY)
        call.response.header("X-Robots-Tag", "noindex, nofollow")
    }
}

/** `Cache-Control: no-store` for every response below the route it is installed on. */
val NoStore = createRouteScopedPlugin("NoStore") {
    onCall { call -> call.response.header(HttpHeaders.CacheControl, "no-store") }
}

/**
 * Accepts POSTs only when `Origin` (or, without it, `Referer`) is [publicBaseUrl]. Compared with the configured URL
 * instead of the `Host` header, because `Host` depends on the proxy. GET, HEAD and OPTIONS pass unchecked.
 */
fun CSRFConfig.onlyFrom(publicBaseUrl: String) {
    allowOrigin(publicBaseUrl)
    onFailure {
        staticLog(KLogger.Level.WARN, TAG) { "Rejected cross site ${request.local.method.value} to ${request.local.uri.substringBefore('?')}" }
        respondMessage(HttpStatusCode.Forbidden, texts.crossSiteHeading, texts.crossSiteText)
    }
}
