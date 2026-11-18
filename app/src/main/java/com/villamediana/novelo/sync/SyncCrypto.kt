package com.villamediana.novelo.sync

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The Novelo Web's locks, the same as the page's (novelo-server: web/js/crypto.js, docs/WEB.md "Cifra"):
 * what goes to the server is opened only here and on the computers this phone linked.
 *
 * From the space's 32 bytes (made here, kept sealed by the AndroidKeyStore): HKDF-SHA256 with salt
 * "novelo-sync-v1" gives "enc" (AES-256-GCM of the records), "key" (HMAC-SHA256 that names them, so
 * the server doesn't see what a record is), "blob" (AES-256-GCM of the files) and "check" (16 bytes
 * that say which key a space is in without being it). Pure JVM: it runs in the tests as it does here.
 */
class SyncCrypto(master: ByteArray) {
    init {
        require(master.size == 32) { "space key: 32 bytes" }
    }

    private val enc = hkdf(master, SALT, "enc".toByteArray(), 32)
    private val blob = hkdf(master, SALT, "blob".toByteArray(), 32)
    private val mac = hkdf(master, SALT, "key".toByteArray(), 32)

    /** Which key the space is in (base64url of 16 bytes). */
    val check: String = b64u(hkdf(master, SALT, "check".toByteArray(), 16))

    /** A record's name on the server: HMAC("<table>:<id>"), base64url (43 letters). */
    fun recordKey(table: String, id: String): String = b64u(hmac(mac, "$table:$id".toByteArray()))

    /** A record's bytes (base64): IV + AES-GCM of its JSON, bound to its name. */
    fun seal(k: String, json: String): String = Base64.getEncoder().encodeToString(gcm(Cipher.ENCRYPT_MODE, enc, null, json.toByteArray(Charsets.UTF_8), k.toByteArray()))

    /** A record back: its JSON. Throws when it isn't this space's or was changed on the way. */
    fun open(k: String, data: String): String = String(gcm(Cipher.DECRYPT_MODE, enc, null, Base64.getDecoder().decode(data), k.toByteArray()), Charsets.UTF_8)

    fun sealBlob(id: String, bytes: ByteArray): ByteArray = gcm(Cipher.ENCRYPT_MODE, blob, null, bytes, id.toByteArray())

    fun openBlob(id: String, bytes: ByteArray): ByteArray = gcm(Cipher.DECRYPT_MODE, blob, null, bytes, id.toByteArray())

    companion object {
        private val SALT = "novelo-sync-v1".toByteArray()
        private val LINK_INFO = "novelo-link-v1".toByteArray()
        private const val IV = 12
        private val random = SecureRandom()

        fun newMaster(): ByteArray = ByteArray(32).also(random::nextBytes)

        /** A new name for a file: 18 random bytes, base64url. */
        fun newId(): String = b64u(ByteArray(18).also(random::nextBytes))

        fun b64u(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun unb64u(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

        fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

        /** HKDF-SHA256 (RFC 5869), up to 32 bytes: one block is all it takes here. */
        fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            require(length in 1..32)
            val prk = hmac(salt, ikm)
            return hmac(prk, info + byteArrayOf(1)).copyOf(length)
        }

        /**
         * AES-256-GCM: encrypting, IV (12 random bytes) + ciphertext; opening, the same back. [aad] binds
         * the bytes to their name (a record moved under another name doesn't open).
         */
        private fun gcm(mode: Int, key: ByteArray, iv: ByteArray?, input: ByteArray, aad: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            return if (mode == Cipher.ENCRYPT_MODE) {
                val nonce = iv ?: ByteArray(IV).also(random::nextBytes)
                c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                c.updateAAD(aad)
                nonce + c.doFinal(input)
            } else {
                c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, input, 0, IV))
                c.updateAAD(aad)
                c.doFinal(input, IV, input.size - IV)
            }
        }

        // ------------------------------------------------------------------ linking a computer

        private val P256: ECParameterSpec by lazy {
            AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        }

        /** A P-256 public key raw (0x04 ‖ X ‖ Y, 65 bytes): what the computer's QR code carries. */
        private fun publicKey(raw: ByteArray): ECPublicKey {
            require(raw.size == 65 && raw[0] == 4.toByte()) { "P-256 raw key: 65 bytes" }
            val x = BigInteger(1, raw.copyOfRange(1, 33))
            val y = BigInteger(1, raw.copyOfRange(33, 65))
            return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), P256)) as ECPublicKey
        }

        private fun raw(key: ECPublicKey): ByteArray {
            fun fixed(n: BigInteger) = n.toByteArray().let { b -> if (b.size == 32) b else if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else ByteArray(32 - b.size) + b }
            return byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)
        }

        /**
         * The answer to a computer's code: the space ([space], its key [master], whose phone it is) encrypted
         * so only the computer whose public key ([webPub], base64url) is in the code opens it. A key pair
         * made only for this answer; the secret is HKDF(ECDH, salt = the code's id, "novelo-link-v1").
         */
        fun wrapLink(webPub: String, linkId: String, space: String, master: ByteArray, owner: String): Wrapped {
            val peer = publicKey(unb64u(webPub))
            val mine = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()
            val shared = KeyAgreement.getInstance("ECDH").apply {
                init(mine.private)
                doPhase(peer, true)
            }.generateSecret()
            val lock = hkdf(shared, linkId.toByteArray(), LINK_INFO, 32)
            val iv = ByteArray(IV).also(random::nextBytes)
            val text = kotlinx.serialization.json.buildJsonObject {
                put("space", kotlinx.serialization.json.JsonPrimitive(space))
                put("key", kotlinx.serialization.json.JsonPrimitive(b64u(master)))
                put("owner", kotlinx.serialization.json.JsonPrimitive(owner))
            }.toString()
            val sealed = gcm(Cipher.ENCRYPT_MODE, lock, iv, text.toByteArray(Charsets.UTF_8), linkId.toByteArray())
            return Wrapped(b64u(raw(mine.public as ECPublicKey)), b64u(iv), b64u(sealed.copyOfRange(IV, sealed.size)))
        }
    }

    /** The phone's one-time public key, the IV and the ciphertext (all base64url). */
    data class Wrapped(val pub: String, val iv: String, val ct: String)
}
