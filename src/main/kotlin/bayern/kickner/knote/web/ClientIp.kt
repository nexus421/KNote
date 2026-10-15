package bayern.kickner.knote.web

import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ClientIp"

/** Bounds the memory [untrustedPeerWarning] uses when KNote is reachable directly. */
private const val MAX_REMEMBERED_PEERS = 1000

/** Only hex digits, colons and dots: [InetAddress] parses such a string as a literal and never asks DNS. */
private val IPV6_LITERAL = Regex("^[0-9a-fA-F:.]+$")

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

/**
 * The key of the rate limits for [ip]. IPv4 as is, IPv6 by its /64 prefix: a provider hands every customer at least
 * a /64, so the full address would give an attacker 2^64 fresh keys. An IPv4-mapped IPv6 address (`::ffff:a.b.c.d`)
 * counts as its IPv4 address. Anything that is no IP literal stays as it is.
 */
fun rateLimitKey(ip: String): String {
    if (':' !in ip || IPV6_LITERAL.matches(ip).not()) return ip
    val address = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return ip
    if (address is Inet4Address) return address.hostAddress
    val prefix = address.address.also { it.fill(0, fromIndex = 8) }
    return "${InetAddress.getByAddress(prefix).hostAddress}/64"
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
