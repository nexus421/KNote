package bayern.kickner.knote.crypto

import bayern.kickner.knote.tempDir
import kotnexlib.ResultOf2
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionKeyTest {

    @Test
    fun `a generated key is 64 lowercase hex characters and fresh every time`() {
        val key = generateSessionKey()

        assertTrue(Regex("[0-9a-f]{64}").matches(key), key)
        assertNotEquals(key, generateSessionKey())
    }

    @Test
    fun `a missing file is no error`() {
        assertNull(assertIs<ResultOf2.Success<ByteArray?>>(readSessionKey(tempDir().resolve("session.key"))).value)
    }

    @Test
    fun `a created key is readable by the owner only and reads back the same`() {
        val file = tempDir().resolve("data/session.key")

        val created = assertIs<ResultOf2.Success<ByteArray>>(createSessionKey(file)).value
        val read = assertIs<ResultOf2.Success<ByteArray?>>(readSessionKey(file)).value

        assertEquals(32, created.size)
        assertContentEquals(created, read)
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file))
        assertEquals(listOf("session.key"), Files.list(file.parent).map { it.fileName.toString() }.toList())
    }

    @Test
    fun `upper case hex and surrounding whitespace are accepted`() {
        val file = tempDir().resolve("session.key")
        val key = generateSessionKey()
        file.writeText("  ${key.uppercase()}\n\n")

        assertContentEquals(key.hexToByteArray(), assertIs<ResultOf2.Success<ByteArray?>>(readSessionKey(file)).value)
    }

    @Test
    fun `a broken file is an error that never shows its content`() {
        val file = tempDir().resolve("session.key")
        listOf("", "abc", generateSessionKey().dropLast(1) + "g", generateSessionKey() + "00").forEach { content ->
            file.writeText(content)

            val message = assertIs<ResultOf2.Failure<String>>(readSessionKey(file)).value

            assertContains(message, "does not hold a key of 64 hex characters")
            if (content.isNotEmpty()) assertFalse(message.contains(content), message)
        }
        assertTrue(file.readText().isNotEmpty())
    }
}
