package com.villamediana.novelo.server

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The phone's own key for the Novelo's server: there is no login, the phone is who it says it is
 * because it signs each request with a key that only it has. The key is made in the phone's secure
 * hardware and never leaves it; Android itself attests where it lives and which app made it.
 */
interface KeyBox {
    /** A key is here. */
    val exists: Boolean

    /**
     * A new key in place of the one there (if any), attesting [challenge] when the phone can.
     * Its public half (SPKI, DER).
     */
    fun create(challenge: ByteArray): ByteArray

    /** SHA256withECDSA of [data] (DER). */
    fun sign(data: ByteArray): ByteArray

    /** The certificates that prove where the key lives, leaf first (DER); none when the phone gives none. */
    fun chain(): List<ByteArray>
}

/** The key in the AndroidKeyStore (P-256, signing only). */
class KeystoreBox(private val alias: String = "novelo-device") : KeyBox {
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override val exists: Boolean get() = runCatching { store().containsAlias(alias) }.getOrDefault(false)

    override fun create(challenge: ByteArray): ByteArray {
        runCatching { store().deleteEntry(alias) }
        fun make(attest: Boolean): KeyPair {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .apply { if (attest) setAttestationChallenge(challenge) }
                .build()
            return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        }
        // A phone that can't attest still gets a key (the server decides whether that is enough).
        val pair = runCatching { make(attest = true) }.getOrElse {
            runCatching { store().deleteEntry(alias) }
            make(attest = false)
        }
        return pair.public.encoded
    }

    override fun sign(data: ByteArray): ByteArray {
        val key = store().getKey(alias, null) as? PrivateKey ?: error("no device key")
        return Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(data) }.sign()
    }

    override fun chain(): List<ByteArray> =
        runCatching { store().getCertificateChain(alias)?.map { it.encoded } }.getOrNull()?.takeIf { it.size > 1 }.orEmpty()
}

/** A key in memory: the tests (no AndroidKeyStore there). */
class MemoryBox : KeyBox {
    private var pair: KeyPair? = null
    override val exists: Boolean get() = pair != null

    override fun create(challenge: ByteArray): ByteArray {
        val made = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        pair = made
        return made.public.encoded
    }

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").apply { initSign(pair?.private ?: error("no device key")); update(data) }.sign()

    override fun chain(): List<ByteArray> = emptyList()
}

/**
 * Where the Novelo's server is: the address the build was made with — except in the unit tests,
 * which run with none (app/build.gradle.kts sets "novelo.server" empty for them), so no test ever
 * talks to the real server or registers a phone there.
 */
object ServerAddress {
    val base: String by lazy { System.getProperty("novelo.server") ?: com.villamediana.novelo.BuildConfig.NOVELO_SERVER }
}

object Install {
    /**
     * The phone itself, for the server: the same when the app is installed again (so a trial goes
     * on, it doesn't start over), another on another phone. A hash of Android's id for this app —
     * the id itself never leaves the phone.
     */
    fun id(context: Context): String? = runCatching {
        val android = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        if (android.isNullOrBlank()) null else hash(android)
    }.getOrNull()

    fun hash(androidId: String): String =
        MessageDigest.getInstance("SHA-256").digest("novelo-install|$androidId".toByteArray()).joinToString("") { "%02x".format(it) }.take(40)
}
