package bayern.kickner.knote.crypto

import bayern.kickner.knote.TEST_SIGN_KEY
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnlockProofTest {

    private val proof = UnlockProof(TEST_SIGN_KEY.hexToByteArray())
    private val issued = proof.create("id-1", 42, "password-1234")

    @Test
    fun `a proof is valid for exactly the values it was made for`() {
        assertTrue(proof.verify(issued, "id-1", 42, "password-1234"))
        assertFalse(proof.verify(issued, "id-1", 42, "password-1235"))
        assertFalse(proof.verify(issued, "id-2", 42, "password-1234"))
        assertFalse(proof.verify(issued, "id-1", 43, "password-1234"))
    }

    @Test
    fun `missing and malformed proofs are invalid`() {
        listOf(null, "", "zz", issued.dropLast(2), issued + "00").forEach { assertFalse(proof.verify(it, "id-1", 42, "password-1234"), "$it") }
    }

    @Test
    fun `another key makes other proofs`() {
        val other = UnlockProof("ff".repeat(32).hexToByteArray())

        assertFalse(other.verify(issued, "id-1", 42, "password-1234"))
    }
}
