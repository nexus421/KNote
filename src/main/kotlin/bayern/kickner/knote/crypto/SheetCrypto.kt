@file:OptIn(kotnexlib.ExperimentalKotNexLibAPI::class)

package bayern.kickner.knote.crypto

import bayern.kickner.knote.storage.SheetContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotnexlib.ResultOf2
import kotnexlib.crypto.AES
import javax.crypto.AEADBadTagException

/** Why a sheet could not be unlocked. */
sealed interface UnlockError {
    /** The password does not match. An expected outcome, not an error. */
    data object WrongPassword : UnlockError

    /** The sheet has no password. */
    data object NotProtected : UnlockError

    /** The stored data is damaged or was not written by KNote. [reason] is safe to log. */
    data class Corrupt(val reason: String) : UnlockError
}

/**
 * Encrypts [markdown] with [password] using AES-GCM and a fresh salt and nonce. Costs one PBKDF2 derivation
 * (600000 iterations), so call it through [CryptoGate]. Without compression: short notes would grow.
 */
fun encryptContent(markdown: String, password: String): SheetContent.Encrypted =
    SheetContent.Encrypted(AES.GCM.encryptWithPassword(markdown, password, compress = false).toString())

/**
 * Decrypts [content] with [password]. Costs one PBKDF2 derivation, so call it through [CryptoGate]. A wrong
 * password is an expected outcome and therefore a return value.
 */
fun decryptContent(content: SheetContent, password: String): ResultOf2<String, UnlockError> = when (content) {
    is SheetContent.Plain -> ResultOf2.Failure(UnlockError.NotProtected)
    is SheetContent.Encrypted -> decrypt(content.aesData, password)
}

private fun decrypt(aesData: String, password: String): ResultOf2<String, UnlockError> {
    val data = AES.AESData.restore(aesData).getOrNull()
        ?: return ResultOf2.Failure(UnlockError.Corrupt("stored data cannot be restored"))
    // Only GCM detects a wrong password. Any other mode was not written by KNote.
    if (data.type != AES.AESType.GCM) return ResultOf2.Failure(UnlockError.Corrupt("unexpected mode ${data.type}"))

    val plain = data.decryptAsString(password)
    val error = plain.exceptionOrNull()
    return when (error) {
        null -> ResultOf2.Success(plain.getOrThrow())
        is AEADBadTagException -> ResultOf2.Failure(UnlockError.WrongPassword)
        else -> ResultOf2.Failure(UnlockError.Corrupt(error.javaClass.simpleName))
    }
}

/**
 * Runs at most [parallelism] PBKDF2 derivations at once, off the request threads. One derivation takes about half
 * a second of CPU. Without the limit, requests from many IPs could keep every core busy.
 */
class CryptoGate(parallelism: Int) {
    private val semaphore = Semaphore(parallelism)

    /** Runs [block] on [Dispatchers.Default] as soon as a permit is free. */
    suspend fun <T> run(block: () -> T): T =
        semaphore.withPermit { withContext(Dispatchers.Default) { block() } }
}
