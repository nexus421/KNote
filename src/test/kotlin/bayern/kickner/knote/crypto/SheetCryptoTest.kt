package bayern.kickner.knote.crypto

import bayern.kickner.knote.storage.SheetContent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotnexlib.ExperimentalKotNexLibAPI
import kotnexlib.ResultOf2
import kotnexlib.crypto.AES
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val MARKDOWN = "# Einkauf\n\n- Äpfel\n- Brot\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n😀 emoji"

class SheetCryptoTest {

    @Test
    fun `round trip with the right password`() {
        val content = encryptContent(MARKDOWN, "password-1234")

        assertEquals(MARKDOWN, assertIs<ResultOf2.Success<String>>(decryptContent(content, "password-1234")).value)
    }

    @Test
    fun `a wrong password is an expected outcome`() {
        val content = encryptContent(MARKDOWN, "password-1234")

        assertEquals(UnlockError.WrongPassword, assertIs<ResultOf2.Failure<UnlockError>>(decryptContent(content, "password-12345")).value)
    }

    @Test
    fun `empty text survives`() {
        val content = encryptContent("", "password-1234")

        assertEquals("", assertIs<ResultOf2.Success<String>>(decryptContent(content, "password-1234")).value)
    }

    @Test
    fun `every encryption uses a fresh salt and nonce`() {
        assertNotEquals(encryptContent(MARKDOWN, "password-1234"), encryptContent(MARKDOWN, "password-1234"))
    }

    @Test
    fun `the plain text is not part of the stored value`() {
        val stored = encryptContent("findme-in-plain-text", "password-1234").aesData

        assertTrue(stored.contains("findme").not())
    }

    @Test
    fun `a plain sheet is not protected`() {
        assertEquals(UnlockError.NotProtected, assertIs<ResultOf2.Failure<UnlockError>>(decryptContent(SheetContent.Plain("x"), "pw")).value)
    }

    @Test
    fun `damaged data is corrupt, not a wrong password`() {
        val result = decryptContent(SheetContent.Encrypted("kein-base64-!!"), "password-1234")

        assertIs<UnlockError.Corrupt>(assertIs<ResultOf2.Failure<UnlockError>>(result).value)
    }

    @OptIn(ExperimentalKotNexLibAPI::class)
    @Test
    fun `data in another mode is corrupt`() {
        val cbc = assertNotNull(AES.CBC.encryptWithPassword("x", "password-1234")).toString()

        val result = decryptContent(SheetContent.Encrypted(cbc), "password-1234")

        assertIs<UnlockError.Corrupt>(assertIs<ResultOf2.Failure<UnlockError>>(result).value)
    }

    @Test
    fun `the gate runs one derivation at a time`() = runBlocking {
        val gate = CryptoGate(1)
        val running = AtomicInteger(0)
        val maxRunning = AtomicInteger(0)
        val content = encryptContent("geheim", "password-1234")

        val results = (1..4).map { i ->
            async {
                gate.run {
                    maxRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                    decryptContent(content, if (i == 1) "password-1234" else "wrong-$i").also { running.decrementAndGet() }
                }
            }
        }.awaitAll()

        assertEquals(1, maxRunning.get())
        assertEquals(1, results.count { it is ResultOf2.Success })
    }

    @Test
    fun `the gate leaves the calling thread`() = runBlocking {
        val caller = Thread.currentThread()

        val worker = CryptoGate(2).run { Thread.currentThread() }

        assertNotEquals(caller, worker)
    }
}
