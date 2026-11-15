package com.villamediana.novelo.backup

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The cloud backup's secrecy. Everything starts from one secret of 16 bytes that only she has (on
 * the phone, in her Google account's safe, and as a code she can write down). From it come the key
 * that encrypts, the key that names the pieces, the vault's name on the server and the token that
 * opens it. What leaves the phone is already encrypted (AES-256-GCM): the server keeps files it
 * cannot read.
 */
object CloudCrypto {
    const val SEED_BYTES = 16

    /** The most a piece holds before it is encrypted (the server takes pieces of up to 4 MB and a little). */
    const val CHUNK = 4 * 1024 * 1024

    private const val NONCE = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()
    private val url: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    class Keys(val enc: ByteArray, val id: ByteArray, val vault: String, val token: String)

    fun newSeed(): ByteArray = ByteArray(SEED_BYTES).also(random::nextBytes)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /** HKDF-SHA256, one block (32 bytes) per label. */
    private fun derive(seed: ByteArray, label: String): ByteArray {
        val prk = hmac("novelo-backup-v1".toByteArray(), seed)
        return hmac(prk, label.toByteArray() + 1.toByte())
    }

    fun keys(seed: ByteArray): Keys {
        require(seed.size == SEED_BYTES)
        return Keys(
            enc = derive(seed, "enc"),
            id = derive(seed, "id"),
            vault = url.encodeToString(derive(seed, "vault").copyOf(24)),
            token = url.encodeToString(derive(seed, "auth")),
        )
    }

    /** A piece's name on the server: says nothing of what is in it, and is the same for the same content (sent once). */
    fun blobId(keys: Keys, plain: ByteArray, length: Int = plain.size): String =
        url.encodeToString(Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(keys.id, "HmacSHA256")); update(plain, 0, length) }.doFinal())

    /** [plain] encrypted, bound to [aad] (a piece to its name: one can't be passed for another). */
    fun seal(keys: Keys, plain: ByteArray, aad: ByteArray, length: Int = plain.size): ByteArray {
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.enc, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(aad)
        return nonce + c.doFinal(plain, 0, length)
    }

    /** What [sealed] holds; throws when it was changed, is another piece, or the key is another. */
    fun open(keys: Keys, sealed: ByteArray, aad: ByteArray): ByteArray {
        require(sealed.size > NONCE)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.enc, "AES"), GCMParameterSpec(TAG_BITS, sealed, 0, NONCE))
        c.updateAAD(aad)
        return c.doFinal(sealed, NONCE, sealed.size - NONCE)
    }

    // ——— the recovery code ———

    /** Letters and digits that aren't mistaken for one another when written down (no I, L, O, U). */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /**
     * The secret as a code to write down: 28 characters in groups of four. The last ones are a
     * check, so a code copied wrong is noticed instead of opening nothing.
     */
    fun code(seed: ByteArray): String {
        require(seed.size == SEED_BYTES)
        val bytes = seed + MessageDigest.getInstance("SHA-256").digest(seed)[0]
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return out.chunked(4).joinToString("-")
    }

    /** The secret a code holds; null when the code isn't right (a letter wrong, some missing). */
    fun seed(code: String): ByteArray? {
        val clean = code.uppercase().replace('O', '0').replace('I', '1').replace('L', '1').filter { it in ALPHABET }
        if (clean.length != 28) return null
        val bytes = ArrayList<Byte>()
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            buffer = (buffer shl 5) or ALPHABET.indexOf(ch)
            bits += 5
            if (bits >= 8) {
                bytes += ((buffer shr (bits - 8)) and 0xFF).toByte()
                bits -= 8
            }
            buffer = buffer and 0xFFFF
        }
        if (bytes.size != SEED_BYTES + 1) return null
        val seed = bytes.take(SEED_BYTES).toByteArray()
        return seed.takeIf { MessageDigest.getInstance("SHA-256").digest(it)[0] == bytes[SEED_BYTES] }
    }
}
