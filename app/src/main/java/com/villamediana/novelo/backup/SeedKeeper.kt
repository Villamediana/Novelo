package com.villamediana.novelo.backup

import android.content.Context
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.DeleteBytesRequest
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Where the cloud backup's secret is kept besides the app itself, so the backup can come back when
 * the app is installed again or the phone is another: her Google account's safe on the phone.
 */
interface SeedKeeper {
    suspend fun read(): ByteArray?

    /** True when it was kept. */
    suspend fun write(seed: ByteArray): Boolean
    suspend fun clear()
}

/** Nowhere else (the tests; a phone without Google's services): the recovery code is the way back. */
object NoKeeper : SeedKeeper {
    override suspend fun read(): ByteArray? = null
    override suspend fun write(seed: ByteArray): Boolean = false
    override suspend fun clear() = Unit
}

/**
 * Google's Block Store: it stays on the phone when the app is uninstalled, and goes with her
 * Google backup (encrypted with her screen lock) to a new phone. Nothing here ever throws: a phone
 * without it simply has no safe, and the recovery code is the way back.
 */
class GoogleKeeper(context: Context) : SeedKeeper {
    private val app = context.applicationContext

    override suspend fun read(): ByteArray? = quietly {
        Blockstore.getClient(app).retrieveBytes(RetrieveBytesRequest.Builder().setKeys(listOf(KEY)).build()).await()
            ?.blockstoreDataMap?.get(KEY)?.bytes?.takeIf { it.size == CloudCrypto.SEED_BYTES }
    }

    override suspend fun write(seed: ByteArray): Boolean = quietly {
        Blockstore.getClient(app).storeBytes(StoreBytesData.Builder().setBytes(seed).setKey(KEY).setShouldBackupToCloud(true).build()).await() != null
    } ?: false

    override suspend fun clear() {
        quietly { Blockstore.getClient(app).deleteBytes(DeleteBytesRequest.Builder().setKeys(listOf(KEY)).build()).await() }
    }

    private suspend fun <T> quietly(block: suspend () -> T?): T? = try {
        withTimeoutOrNull(TIMEOUT_MS) { block() }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

    private suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task -> if (cont.isActive) cont.resume(if (task.isSuccessful) task.result else null) }
    }

    private companion object {
        const val KEY = "novelo.backup.seed"
        const val TIMEOUT_MS = 8_000L
    }
}
