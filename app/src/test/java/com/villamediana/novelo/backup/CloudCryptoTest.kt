package com.villamediana.novelo.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CloudCryptoTest {
    private val seed = ByteArray(16) { (it * 7 + 3).toByte() }

    @Test
    fun everythingComesFromTheSecretAndOnlyFromIt() {
        val a = CloudCrypto.keys(seed)
        val b = CloudCrypto.keys(seed.copyOf())
        assertEquals(a.vault, b.vault)
        assertEquals(a.token, b.token)
        assertArrayEquals(a.enc, b.enc)
        // What the server takes: a vault of 32 characters, a token of 32 bytes.
        assertTrue(a.vault, Regex("[A-Za-z0-9_-]{32}").matches(a.vault))
        assertEquals(32, java.util.Base64.getUrlDecoder().decode(a.token).size)
        // Each thing is its own: knowing the vault or the token says nothing of the key that encrypts.
        assertFalse(a.enc.contentEquals(a.id))
        assertNotEquals(a.vault, a.token.take(32))
        val other = CloudCrypto.keys(CloudCrypto.newSeed())
        assertNotEquals(a.vault, other.vault)
        assertNotEquals(a.token, other.token)
    }

    @Test
    fun aPieceIsEncryptedBoundToItsNameAndNoticedWhenChanged() {
        val keys = CloudCrypto.keys(seed)
        val plain = "O diário de hoje: fui à praia com a Alma.".toByteArray()
        val id = CloudCrypto.blobId(keys, plain)
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(id))
        assertEquals(id, CloudCrypto.blobId(keys, plain))
        val sealed = CloudCrypto.seal(keys, plain, id.toByteArray())
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("praia"))
        // Encrypted twice it isn't the same bytes (nothing tells that two versions hold the same thing).
        assertFalse(sealed.contentEquals(CloudCrypto.seal(keys, plain, id.toByteArray())))
        assertArrayEquals(plain, CloudCrypto.open(keys, sealed, id.toByteArray()))
        // Part of a buffer.
        val buffer = plain + ByteArray(10)
        assertEquals(id, CloudCrypto.blobId(keys, buffer, plain.size))
        assertArrayEquals(plain, CloudCrypto.open(keys, CloudCrypto.seal(keys, buffer, id.toByteArray(), plain.size), id.toByteArray()))
        for (broken in listOf<() -> ByteArray>(
            { CloudCrypto.open(keys, sealed.copyOf().also { it[20] = (it[20] + 1).toByte() }, id.toByteArray()) },
            { CloudCrypto.open(keys, sealed, "another".toByteArray()) },
            { CloudCrypto.open(CloudCrypto.keys(CloudCrypto.newSeed()), sealed, id.toByteArray()) },
        )) {
            try {
                broken()
                fail("it opened")
            } catch (e: Exception) {
                // as it should
            }
        }
    }

    @Test
    fun theRecoveryCodeBringsTheSecretBackAndACopyingMistakeIsNoticed() {
        val code = CloudCrypto.code(seed)
        assertTrue(code, Regex("([0-9A-HJKMNP-TV-Z]{4}-){6}[0-9A-HJKMNP-TV-Z]{4}").matches(code))
        assertArrayEquals(seed, CloudCrypto.seed(code))
        // Typed as people type: small letters, spaces, no dashes, O for 0, l for 1.
        val typed = code.lowercase().replace("-", " ").replace('0', 'o').replace('1', 'l')
        assertArrayEquals(seed, CloudCrypto.seed(typed))
        // One character wrong, one missing, nothing at all.
        val wrong = code.toCharArray().also { it[0] = if (it[0] == 'A') 'B' else 'A' }.concatToString()
        assertNull(CloudCrypto.seed(wrong))
        assertNull(CloudCrypto.seed(code.dropLast(1)))
        assertNull(CloudCrypto.seed(""))
        repeat(200) {
            val s = CloudCrypto.newSeed()
            assertArrayEquals(s, CloudCrypto.seed(CloudCrypto.code(s)))
        }
    }
}
