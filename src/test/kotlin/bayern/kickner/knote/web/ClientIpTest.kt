package bayern.kickner.knote.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

private const val PROXY = "10.0.0.2"

class ClientIpTest {

    @Test
    fun `a foreign peer ignores the header`() {
        assertEquals("203.0.113.9", clientIp("203.0.113.9", listOf("1.2.3.4"), PROXY))
    }

    @Test
    fun `the proxy names the client in the last entry`() {
        assertEquals("198.51.100.7", clientIp(PROXY, listOf("198.51.100.7"), PROXY))
    }

    @Test
    fun `entries before the last one come from the client and are ignored`() {
        assertEquals("198.51.100.7", clientIp(PROXY, listOf("1.2.3.4, 5.6.7.8,198.51.100.7"), PROXY))
    }

    @Test
    fun `several headers count as one list`() {
        assertEquals("198.51.100.7", clientIp(PROXY, listOf("1.2.3.4", "198.51.100.7"), PROXY))
    }

    @Test
    fun `the proxy without header is the client`() {
        assertEquals(PROXY, clientIp(PROXY, emptyList(), PROXY))
    }

    @Test
    fun `an empty last entry falls back to the peer`() {
        assertEquals(PROXY, clientIp(PROXY, listOf("1.2.3.4, "), PROXY))
    }

    @Test
    fun `IPv6 clients share one rate limit key per 64 prefix`() {
        assertEquals("2001:db8:1:2:0:0:0:0/64", rateLimitKey("2001:db8:1:2::1"))
        assertEquals(rateLimitKey("2001:db8:1:2::1"), rateLimitKey("2001:0DB8:0001:0002:ffff:ffff:ffff:7"))
        assertNotEquals(rateLimitKey("2001:db8:1:2::1"), rateLimitKey("2001:db8:1:3::1"))
        assertEquals("0:0:0:0:0:0:0:0/64", rateLimitKey("::1"))
    }

    @Test
    fun `IPv4 keeps its address, also IPv4-mapped`() {
        assertEquals("203.0.113.5", rateLimitKey("203.0.113.5"))
        assertEquals("203.0.113.5", rateLimitKey("::ffff:203.0.113.5"))
        assertEquals("203.0.113.5", rateLimitKey("::ffff:cb00:7105"))
    }

    @Test
    fun `anything else stays as it is and never reaches DNS`() {
        listOf("", "unknown", "example.org", "fe80::1%eth0", "2001:db8::zz", "[2001:db8::1]", ":::").forEach {
            assertEquals(it, rateLimitKey(it), it)
        }
    }
}
