package com.villamediana.novelo.sync

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.InvalidationTracker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.data.NoveloDatabase
import com.villamediana.novelo.server.ServerException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Novelo on the computer, from the phone's side: linking a computer (its QR code), the
 * computers linked, and keeping everything in step — at once while the app is on the screen (a
 * change here goes up in a moment, and the server is kept waiting for news), every 15 minutes
 * otherwise. Nothing goes anywhere until the first computer is linked.
 */
class NoveloWeb(
    private val context: Context,
    private val db: NoveloDatabase,
    val engine: SyncEngine,
    private val transport: SyncTransport,
    private val scope: CoroutineScope,
    private val space: SyncSpace,
) {
    private val _computers = MutableStateFlow<List<SyncTransport.Computer>?>(null)

    /** The computers linked (null: not asked yet). */
    val computers: StateFlow<List<SyncTransport.Computer>?> = _computers

    val on: Boolean get() = space.on

    private var loop: Job? = null
    private var soon: Job? = null
    private var watching = false

    /** Starts what keeps things in step (the app opened, or the first computer linked). */
    fun watch() {
        if (!space.on) return
        schedule(context)
        if (watching) {
            live()
            return
        }
        watching = true
        db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(SyncTables.ALL.map { it.name }.toTypedArray()) {
            override fun onInvalidated(tables: Set<String>) = changedHere()
        })
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) = live()
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                loop?.cancel()
                loop = null
            }
        })
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) live()
    }

    /** Something changed here: it goes up in a moment (several changes together, in one go). */
    private fun changedHere() {
        if (!space.on) return
        soon?.cancel()
        soon = scope.launch {
            delay(1_500)
            runCatching { engine.sync() }
            runCatching { engine.moveFiles() }
        }
    }

    /** While the app is on the screen: the server is kept waiting for news, which come in at once. */
    private fun live() {
        if (!space.on || loop?.isActive == true) return
        loop = scope.launch {
            var failures = 0
            while (isActive) {
                try {
                    engine.sync(waitSeconds = 25)
                    engine.moveFiles()
                    failures = 0
                } catch (e: ServerException) {
                    // The space is gone from the server (it was stopped on another device): off here too.
                    if (e.status == 401 || e.code == "no_space") return@launch
                    failures++
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    failures++
                }
                if (failures > 0) delay((5_000L * failures).coerceAtMost(60_000))
            }
        }
    }

    // ------------------------------------------------------------------ linking

    /** What a computer's code says: its link id, its public key and the code's secret. */
    data class Code(val linkId: String, val webKey: String, val secret: String)

    /**
     * Links the computer whose code was read: the space starts (the first time), its key goes to the
     * computer encrypted to the computer's own key, and everything starts going up. Returns its name.
     */
    suspend fun link(code: Code): String {
        val (spaceId, master) = engine.start()
        val wrapped = SyncCrypto.wrapLink(code.webKey, code.linkId, code.secret, spaceId, master, owner())
        val name = transport.approve(code.linkId, wrapped, owner())
        watch()
        scope.launch {
            runCatching { engine.sync() }
            runCatching { engine.moveFiles(keep = true) }
        }
        refresh()
        return name
    }

    suspend fun refresh() {
        if (!space.on) {
            _computers.value = emptyList()
            return
        }
        _computers.value = runCatching { transport.computers() }.getOrNull() ?: _computers.value
    }

    suspend fun unlink(id: String) {
        transport.unlink(id)
        refresh()
    }

    /** Stops for good: the computers are unlinked and the server's copy erased. */
    suspend fun stop() {
        loop?.cancel()
        engine.stop()
        _computers.value = emptyList()
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    private fun owner(): String = listOfNotNull(Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, Build.MODEL).distinct().joinToString(" ").trim().take(60)

    companion object {
        const val WORK = "novelo-web-sync"
        private val CODE = Regex("""^https://([A-Za-z0-9.-]+(?::\d+)?)/web/link/?#([A-Za-z0-9_-]{22,64})\.([A-Za-z0-9_-]{80,100})\.([A-Za-z0-9_-]{22})$""")

        /**
         * The address in a computer's QR code (also what opens the app from the phone's camera): only one
         * of this phone's own server ([server], its address), with a link, a key and a secret that are one.
         */
        fun code(text: String?, server: String = com.villamediana.novelo.server.ServerAddress.base): Code? {
            val m = CODE.matchEntire(text?.trim() ?: return null) ?: return null
            val host = runCatching { java.net.URI(server.trim()).let { u -> u.host + if (u.port > 0) ":${u.port}" else "" } }.getOrNull()
            if (host.isNullOrEmpty() || !m.groupValues[1].equals(host, ignoreCase = true)) return null
            val key = m.groupValues[3]
            if (runCatching { SyncCrypto.unb64u(key) }.getOrNull()?.size != 65) return null
            if (runCatching { SyncCrypto.unb64u(m.groupValues[4]) }.getOrNull()?.size != 16) return null
            return Code(m.groupValues[2], key, m.groupValues[4])
        }

        /** Every 15 minutes with a network: what came while the app was closed (a reminder set on the computer rings). */
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

/** The 15-minute sync with the app closed. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val web = NoveloApplication.graph.web
        if (!web.on) return Result.success()
        return try {
            web.engine.sync()
            // Once a day the server is told which files are still in use.
            val prefs = applicationContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
            val keep = System.currentTimeMillis() - prefs.getLong("kept", 0) > 24 * 3600_000L
            web.engine.moveFiles(keep = keep)
            if (keep) prefs.edit().putLong("kept", System.currentTimeMillis()).apply()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

/** The space's place on this phone: SharedPreferences "sync", the key sealed by the AndroidKeyStore. */
class PrefsSpace(context: Context) : SyncSpace {
    private val prefs: SharedPreferences = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    override val on: Boolean get() = prefs.getString(KEY, null) != null
    override var cursor: Long
        get() = prefs.getLong(CURSOR, 0)
        set(v) = prefs.edit().putLong(CURSOR, v).apply()
    override var spaceId: String?
        get() = prefs.getString(SPACE, null)
        set(v) = prefs.edit().putString(SPACE, v).apply()

    private var cached: ByteArray? = null

    override fun master(): ByteArray? {
        cached?.let { return it }
        val sealed = prefs.getString(KEY, null) ?: return null
        val key = open(sealed) ?: return null
        // Kept in the clear (only where there is no keystore: the tests) on a phone that has one: sealed now.
        if (sealed.startsWith("p1:") && !NO_KEYSTORE) prefs.edit().putString(KEY, seal(key)).apply()
        return key.also { cached = it }
    }

    override fun start(master: ByteArray) {
        val sealed = seal(master) // first: a keystore that fails leaves nothing behind
        prefs.edit().putString(KEY, sealed).putLong(CURSOR, 0).remove(SPACE).apply()
        cached = master
    }

    override fun stop() {
        cached = null
        prefs.edit().clear().apply()
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /**
     * The space's key sealed by the phone's keystore. Where there is none at all (the unit tests) it is
     * kept as it is; on a phone a keystore that fails fails the link — the key never sits in the clear.
     */
    private fun seal(plain: ByteArray): String = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keystoreKey())
        "k1:" + Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(plain), Base64.NO_WRAP)
    } catch (e: Exception) {
        if (!NO_KEYSTORE) throw e
        "p1:" + Base64.encodeToString(plain, Base64.NO_WRAP)
    }

    private fun open(sealed: String): ByteArray? = runCatching {
        val parts = sealed.split(':')
        when (parts.firstOrNull()) {
            "k1" -> Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)))
                doFinal(Base64.decode(parts[2], Base64.NO_WRAP))
            }
            "p1" -> Base64.decode(parts[1], Base64.NO_WRAP)
            else -> null
        }
    }.getOrNull()

    private companion object {
        const val KEY = "key"
        const val CURSOR = "cursor"
        const val SPACE = "space"
        const val ALIAS = "novelo-sync"

        /** The unit tests (Robolectric): no keystore at all. */
        val NO_KEYSTORE = Build.FINGERPRINT == "robolectric"
    }
}

/** The phone's files for the sync: thumbnails, where a file that came goes, and whether the network costs. */
class PhoneMedia(private val context: Context, private val captures: File) : SyncMedia {
    override fun metered(): Boolean = runCatching {
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
    }.getOrDefault(true)

    override fun thumb(captureId: Long, image: File, turn: Int): File? {
        // Upright by the photo's own orientation only: the turn given by hand goes in the record, and the page applies it.
        val bmp = com.villamediana.novelo.mind.uprightBitmap(image, 480) ?: return null
        val out = File(context.filesDir, "sync-thumbs/$captureId.jpg").apply { parentFile?.mkdirs() }
        return runCatching { out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 78, it) }; out }.getOrNull()
    }

    override fun place(captureId: Long, kind: String, mime: String?, name: String?, at: Long): File = when (kind) {
        "image" -> File(captures, "photo-$at-c$captureId.${if (mime == "image/png") "png" else "jpg"}")
        "audio" -> File(captures, "voice-$at-c$captureId.wav")
        else -> File(File(captures, "files/$at-c$captureId"), safeName(name))
    }

    companion object {
        /** A file's name from elsewhere as a name here: one name (no folders, no "..", no control characters), never empty. */
        fun safeName(name: String?): String {
            val n = (name ?: "").replace('/', '-').replace('\\', '-').filter { !it.isISOControl() && it.code !in 0x202A..0x202E && it.code !in 0x2066..0x2069 }.trim().take(120)
            return if (n.isEmpty() || n.all { it == '.' }) "arquivo" else n
        }
    }
}
