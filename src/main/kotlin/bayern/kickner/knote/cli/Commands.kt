package bayern.kickner.knote.cli

import java.security.SecureRandom

/** 256 bits for the HMAC key of the admin cookie. */
private const val SESSION_KEY_BYTES = 32

/**
 * A fresh `sessionSignKey`: [SESSION_KEY_BYTES] random bytes as 64 lowercase hex characters.
 *
 * @param random Source of randomness, injectable for tests.
 */
fun generateSessionKey(random: SecureRandom = SecureRandom()): String =
    ByteArray(SESSION_KEY_BYTES).also(random::nextBytes).toHexString()
