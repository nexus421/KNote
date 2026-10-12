package bayern.kickner.knote.web

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ClientIp"

/** Bounds the memory [untrustedPeerWarning] uses when KNote is reachable directly. */
private const val MAX_REMEMBERED_PEERS = 1000

/**
 * The client IP that rate limits are keyed by. Only the trusted proxy may name the client: Zoraxy appends the peer
 * it saw as the last `X-Forwarded-For` entry, everything before it comes from the client and is ignored. Ktor's
 * `XForwardedHeaders` plugin takes the first entry by default and is therefore not used.
 *
 * @param peer Address of the TCP peer.
 * @param forwardedFor All `X-Forwarded-For` header values in order.
 */
fun clientIp(peer: String, forwardedFor: List<String>, trustedProxyIp: String): String {
    if (peer != trustedProxyIp) return peer
    return forwardedFor.flatMap { it.split(',') }.lastOrNull()?.trim()?.ifEmpty { null } ?: peer
}

/** [clientIp] for this call. `request.local` is the real TCP peer, unlike `request.origin`, which headers can set. */
fun ApplicationCall.clientIp(trustedProxyIp: String): String =
    clientIp(request.local.remoteAddress, request.headers.getAll(HttpHeaders.XForwardedFor).orEmpty(), trustedProxyIp)

/**
 * Logs every peer other than [trustedProxyIp] once on WARN. Behind Zoraxy there must be none, so a line naming the
 * Zoraxy address means `trustedProxyIp` is wrong and all clients share one rate limit.
 */
fun untrustedPeerWarning(trustedProxyIp: String): ApplicationPlugin<Unit> = createApplicationPlugin("UntrustedPeerWarning") {
    val seen = ConcurrentHashMap.newKeySet<String>()
    onCall { call ->
        val peer = call.request.local.remoteAddress
        val firstTime = peer != trustedProxyIp && seen.size < MAX_REMEMBERED_PEERS && seen.add(peer)
        if (firstTime) staticLog(KLogger.Level.WARN, TAG) { "Request from untrusted peer $peer" }
    }
}
