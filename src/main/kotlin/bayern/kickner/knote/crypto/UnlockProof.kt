package bayern.kickner.knote.crypto

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Separates these MACs from the admin cookie MACs made with the same key. */
private const val CONTEXT = "knote-unlock-proof\n"

/**
 * Proves to a later save that a password unlocked a protected sheet. The editor of a protected sheet keeps the
 * password in a hidden field, and a save must not trust it blindly. Checking it by decrypting again would turn the
 * save into a password oracle without the unlock rate limit. Instead the unlock issues an HMAC over sheet id,
 * version and password, and the save only checks that HMAC: in constant time, without PBKDF2, and a guessed password
 * reveals nothing because the browser cannot create a matching proof.
 *
 * @param key HMAC key, the session key (see `readSessionKey`).
 */
class UnlockProof(key: ByteArray) {
    private val keySpec = SecretKeySpec(key, "HmacSHA256")

    /** The proof that [password] unlocked the sheet [id] in the version [updated], as hex. */
    fun create(id: String, updated: Long, password: String): String = mac(id, updated, password).toHexString()

    /** True if [proof] was created by [create] for exactly these values. A missing or malformed proof is false. */
    fun verify(proof: String?, id: String, updated: Long, password: String): Boolean {
        val presented = runCatching { proof?.hexToByteArray() }.getOrNull() ?: return false
        return MessageDigest.isEqual(presented, mac(id, updated, password))
    }

    private fun mac(id: String, updated: Long, password: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(keySpec) }.doFinal("$CONTEXT$id\n$updated\n$password".encodeToByteArray())
}
