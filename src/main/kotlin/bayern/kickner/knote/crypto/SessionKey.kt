package bayern.kickner.knote.crypto

import kotnexlib.ResultOf2
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import kotlin.io.path.readText

/** File in `dataDir` that holds the session key. */
const val SESSION_KEY_FILE = "session.key"

/** 256 bits for the HMAC key. */
private const val SESSION_KEY_BYTES = 32

private val HEX_KEY_REGEX = Regex("^[0-9a-fA-F]{64}$")

/**
 * A fresh session key: [SESSION_KEY_BYTES] random bytes as 64 lowercase hex characters.
 *
 * @param random Source of randomness, injectable for tests.
 */
fun generateSessionKey(random: SecureRandom = SecureRandom()): String =
    ByteArray(SESSION_KEY_BYTES).also(random::nextBytes).toHexString()

/**
 * Reads the session key from [file]. The key signs the admin cookie and the unlock proofs. It lives in `dataDir`
 * instead of the config, so nobody has to create and copy it: the first start writes it (see [createSessionKey]),
 * and deleting the file and restarting logs every admin out.
 *
 * @return the key, null if [file] does not exist yet, or a problem that is safe to log (it never contains the key).
 */
fun readSessionKey(file: Path): ResultOf2<ByteArray?, String> {
    if (Files.notExists(file)) return ResultOf2.Success(null)
    val text = runCatching { file.readText().trim() }
        .getOrElse { return ResultOf2.Failure("$file cannot be read: ${it.message}") }
    if (HEX_KEY_REGEX.matches(text).not())
        return ResultOf2.Failure("$file does not hold a key of 64 hex characters. Delete it, the next start creates a new one and every admin logs in again")
    return ResultOf2.Success(text.hexToByteArray())
}

/**
 * Writes a fresh key to [file] and returns it. The temp file is readable by the owner only (that is how
 * [Files.createTempFile] creates it) and moved into place atomically, so a crash never leaves a half written key.
 * Only for a missing [file], [readSessionKey] tells.
 *
 * @return the key, or a problem that is safe to log.
 */
fun createSessionKey(file: Path, random: SecureRandom = SecureRandom()): ResultOf2<ByteArray, String> {
    val key = generateSessionKey(random)
    return runCatching {
        val dir = file.toAbsolutePath().parent
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, "session", ".tmp")
        runCatching {
            Files.writeString(tmp, key + "\n")
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { runCatching { Files.deleteIfExists(tmp) } }.getOrThrow()
    }.fold({ ResultOf2.Success(key.hexToByteArray()) }, { ResultOf2.Failure("$file cannot be created: ${it.message}") })
}
