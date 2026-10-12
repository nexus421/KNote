package bayern.kickner.knote.web

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
