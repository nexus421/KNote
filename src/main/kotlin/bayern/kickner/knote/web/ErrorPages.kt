package bayern.kickner.knote.web

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import bayern.kickner.knote.i18n.texts
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.defaultExceptionStatusCode
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import kotlinx.coroutines.CancellationException

private const val TAG = "ErrorPages"

/**
 * Pages in the KNote design for answers that would otherwise have an empty body: an exceeded rate limit (429) and
 * every exception. Exceptions with a known meaning keep their status (413 for a body over the limit, 400 for a
 * broken request), everything else is a 500 and logged with its stack trace.
 */
fun StatusPagesConfig.knoteErrorPages() {
    status(HttpStatusCode.TooManyRequests) { call, status ->
        call.respondMessage(status, call.texts.tooManyRequestsHeading, call.texts.tooManyRequestsText)
    }
    exception<Exception> { call, cause ->
        // A cancelled call (client gone) is no error and gets no answer
        if (cause is CancellationException) throw cause
        val status = defaultExceptionStatusCode(cause) ?: HttpStatusCode.InternalServerError
        if (status == HttpStatusCode.InternalServerError)
            staticLog(KLogger.Level.ERROR, TAG) { "Unexpected error on ${call.request.local.method.value} ${call.request.local.uri.substringBefore('?')}: ${cause.stackTraceToString()}" }
        if (call.response.isCommitted) return@exception
        val t = call.texts
        when (status) {
            HttpStatusCode.PayloadTooLarge -> call.respondMessage(status, t.tooLargeHeading, t.tooLargeText)
            HttpStatusCode.InternalServerError -> call.respondMessage(status, t.errorHeading, t.unexpectedErrorText)
            else -> call.respondMessage(status, t.errorHeading, t.requestFailedText)
        }
    }
}
