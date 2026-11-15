package com.villamediana.novelo.backup

import com.villamediana.novelo.R
import com.villamediana.novelo.server.NoveloServer
import com.villamediana.novelo.server.ServerException
import com.villamediana.novelo.settings.Settings
import com.villamediana.novelo.settings.t
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Base64

/**
 * The backup in the cloud (Novelo+): the same backup the folder gets — backup.json, the photos,
 * the sounds, the files — but encrypted on the phone and sent in pieces to the Novelo's server.
 * A piece already there isn't sent again, so after the first time only what is new goes; a small
 * encrypted index says which pieces make each version. The server keeps the last versions and
 * can read none of it (see [CloudCrypto]).
 */
class CloudBackup(
    private val server: NoveloServer,
    private val core: Backup,
    private val settings: Settings,
    private val keeper: SeedKeeper,
    /** What was last sent, and the pieces of each file already named (a photo isn't read again to know them). */
    private val stateFile: File,
    private val workDir: File,
    private val version: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private data class Index(
        val v: Int = 1,
        val madeAt: Long = 0,
        val app: String = "",
        val entries: Int = 0,
        val photos: Int = 0,
        val sounds: Int = 0,
        val files: Int = 0,
        /** backup.json, in pieces. */
        val json: List<String> = emptyList(),
        val items: List<Item> = emptyList(),
    )

    @Serializable
    private data class Item(val path: String, val size: Long, val chunks: List<String>)

    @Serializable
    private data class Known(val size: Long, val modified: Long, val chunks: List<String>)

    @Serializable
    private data class State(val vault: String = "", val last: String = "", val known: Map<String, Known> = emptyMap())

    /** A backup found in the cloud: when it was made and what it holds. */
    data class Found(val at: Long, val entries: Int, val photos: Int, val sounds: Int, val files: Int)

    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The cloud takes this phone's backup (the server said so). */
    val offered: Boolean get() = server.available && server.plan.value?.backup == true

    /** The secret already hers: in the app, or in her Google account's safe (the app installed again, a new phone). */
    suspend fun known(): ByteArray? = settings.cloudSeed() ?: keeper.read()

    /** The code that opens the backup anywhere, to write down; null while the cloud backup was never on. */
    suspend fun code(): String? = settings.cloudSeed()?.let(CloudCrypto::code)

    /**
     * On: the secret is made (the one already hers is kept), stored in her Google account's safe,
     * and the backups start going. True when the safe took it (when not, the code is the only way back).
     */
    suspend fun turnOn(): Boolean {
        val seed = known() ?: CloudCrypto.newSeed()
        settings.setCloudSeed(seed)
        val safe = keeper.write(seed)
        settings.setCloud(true)
        return safe
    }

    /** Off: nothing more goes. [erase]: what is in the cloud is erased too, and the secret forgotten. */
    suspend fun turnOff(erase: Boolean) = lock.withLock {
        if (erase) settings.cloudSeed()?.let { seed ->
            // Erased first: if that can't be done now (no network), it stays on and she is told —
            // never "off" with her backup still up there.
            val keys = CloudCrypto.keys(seed)
            val reply = server.call("DELETE", "/v1/backup/${keys.vault}", headers = vault(keys))
            // Nothing there to erase is as good as erased.
            if (reply.status != 404) server.parse(reply)
            settings.setCloudSeed(null)
            keeper.clear()
            stateFile.delete()
        }
        settings.setCloud(false)
    }

    private fun vault(keys: CloudCrypto.Keys) = mapOf("X-Novelo-Vault" to keys.token)

    private fun readState(vault: String): State =
        runCatching { json.decodeFromString(State.serializer(), stateFile.readText()) }.getOrNull()?.takeIf { it.vault == vault } ?: State(vault)

    /**
     * One backup to the cloud, its result kept for Ajustes. Only what changed goes; nothing changed,
     * nothing goes. False when it is off or went wrong (it goes again by itself: what was sent stays sent).
     */
    suspend fun save(): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!settings.current().cloud) return@withContext false
            // The secret gone from the app but still in her Google safe: taken from there.
            val seed = settings.cloudSeed() ?: keeper.read()?.also { settings.setCloudSeed(it) }
            if (seed == null) {
                settings.setCloudFailed(t(R.string.cloud_later))
                return@withContext false
            }
            try {
                val keys = CloudCrypto.keys(seed)
                val parts = core.parts()
                if (parts == null) {
                    settings.setCloudDone(clock(), t(R.string.backup_nothing_yet))
                    return@withContext true
                }
                val state = readState(keys.vault)
                if (state.last == parts.same && !settings.current().cloudFailed) return@withContext true
                send(keys, parts, state)
                settings.setCloudDone(clock(), Backups.note(parts.counted.entries, parts.counted.photos, parts.counted.sounds, parts.counted.files))
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                settings.setCloudFailed(said(e))
                false
            }
        }
    }

    private suspend fun send(keys: CloudCrypto.Keys, parts: Backup.Parts, state: State) {
        val at = "/v1/backup/${keys.vault}"
        val head = vault(keys)
        // 1. The name of every piece (a file not changed since it was last named isn't read again).
        val jsonIds = parts.json.asList().chunked(CloudCrypto.CHUNK).map { it.toByteArray() }.map { CloudCrypto.blobId(keys, it) to it }
        val known = HashMap<String, Known>()
        val items = parts.files.map { (rel, file) ->
            val k = state.known[file.path]?.takeIf { it.size == file.length() && it.modified == file.lastModified() }
                ?: Known(file.length(), file.lastModified(), names(keys, file))
            known[file.path] = k
            Item(rel, k.size, k.chunks) to file
        }
        val all = (jsonIds.map { it.first } + items.flatMap { it.first.chunks }).distinct()

        // 2. Which the server doesn't have yet.
        val missing = HashSet<String>()
        for (batch in all.chunked(2_000)) {
            val body = buildJsonObject { putJsonArray("ids") { batch.forEach { add(JsonPrimitive(it)) } } }.toString().toByteArray()
            val out = server.parse(server.call("POST", "$at/have", body, JSON, head))
            out["missing"]?.jsonArray?.forEach { missing += it.jsonPrimitive.content }
        }

        // 3. Those go, encrypted.
        suspend fun put(id: String, plain: ByteArray, length: Int) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val sealed = CloudCrypto.seal(keys, plain, id.toByteArray(), length)
            patiently { server.parse(server.call("PUT", "$at/blobs/$id", sealed, BYTES, head)) }
            missing -= id
        }
        for ((id, bytes) in jsonIds) if (id in missing) put(id, bytes, bytes.size)
        val buffer = ByteArray(CloudCrypto.CHUNK)
        for ((item, file) in items) {
            if (item.chunks.none { it in missing }) continue
            RandomAccessFile(file, "r").use { raf ->
                for ((i, id) in item.chunks.withIndex()) {
                    if (id !in missing) continue
                    raf.seek(i.toLong() * CloudCrypto.CHUNK)
                    val n = fill(raf, buffer)
                    // The file changed since its pieces were named: the next backup names them again.
                    if (n <= 0 || CloudCrypto.blobId(keys, buffer, n) != id) throw IOException(t(R.string.cloud_changed_meanwhile))
                    put(id, buffer, n)
                }
            }
        }

        // 4. The index of this version: what the backup holds and the pieces it is made of.
        val index = Index(
            madeAt = parts.madeAt, app = version,
            entries = parts.counted.entries, photos = parts.counted.photos, sounds = parts.counted.sounds, files = parts.counted.files,
            json = jsonIds.map { it.first }, items = items.map { it.first },
        )
        val sealed = CloudCrypto.seal(keys, json.encodeToString(Index.serializer(), index).toByteArray(), INDEX_AAD)
        val body = buildJsonObject {
            put("data", Base64.getEncoder().encodeToString(sealed))
            putJsonArray("refs") { all.forEach { add(JsonPrimitive(it)) } }
        }.toString().toByteArray()
        server.parse(server.call("PUT", "$at/manifest", body, JSON, head))
        runCatching { stateFile.writeText(json.encodeToString(State.serializer(), State(keys.vault, parts.same, known))) }
    }

    /**
     * A first backup or a restore is thousands of pieces in a row: told "too many at once" (or
     * "busy"), it waits what the server says and goes on from where it was, a few times.
     */
    private suspend fun <T> patiently(block: suspend () -> T): T {
        var tries = 0
        while (true) {
            try {
                return block()
            } catch (e: ServerException) {
                if ((e.code != "rate_limited" && e.code != "busy") || ++tries > 5) throw e
                kotlinx.coroutines.delay(e.retryAfter.coerceIn(2, 60) * 1000L)
            }
        }
    }

    private fun fill(raf: RandomAccessFile, buffer: ByteArray): Int {
        var n = 0
        while (n < buffer.size) {
            val r = raf.read(buffer, n, buffer.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    /** The names of a file's pieces, in order. */
    private fun names(keys: CloudCrypto.Keys, file: File): List<String> {
        val out = ArrayList<String>()
        val buffer = ByteArray(CloudCrypto.CHUNK)
        RandomAccessFile(file, "r").use { raf ->
            while (true) {
                val n = fill(raf, buffer)
                if (n <= 0) break
                out += CloudCrypto.blobId(keys, buffer, n)
                if (n < buffer.size) break
            }
        }
        return out
    }

    private suspend fun index(keys: CloudCrypto.Keys): Index? {
        val reply = server.call("GET", "/v1/backup/${keys.vault}/manifest", headers = vault(keys))
        if (reply.status == 404) return null
        if (reply.status != 200) server.parse(reply)
        val plain = try {
            CloudCrypto.open(keys, reply.bytes, INDEX_AAD)
        } catch (e: Exception) {
            throw IOException(t(R.string.backup_broken), e)
        }
        return json.decodeFromString(Index.serializer(), plain.toString(Charsets.UTF_8))
    }

    /** The backup [seed] opens, when there is one in the cloud. */
    suspend fun find(seed: ByteArray): Found? = withContext(Dispatchers.IO) {
        index(CloudCrypto.keys(seed))?.let { Found(it.madeAt, it.entries, it.photos, it.sounds, it.files) }
    }

    /**
     * The backup [seed] opens, brought back in place of what the app has; the cloud backup is on
     * from then, with that secret. Everything is fetched and checked first: if a piece fails,
     * nothing in the app has changed.
     */
    suspend fun restore(seed: ByteArray): Backup.Restored = lock.withLock {
        withContext(Dispatchers.IO) {
            val keys = CloudCrypto.keys(seed)
            val index = index(keys) ?: throw IOException(t(R.string.backup_not_found))
            val dir = File(workDir, "cloud-restore").apply { deleteRecursively(); mkdirs() }
            try {
                val base = dir.canonicalFile
                suspend fun fetch(id: String): ByteArray {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val reply = patiently {
                        server.call("GET", "/v1/backup/${keys.vault}/blobs/$id", headers = vault(keys)).also { if (it.status != 200) server.parse(it) }
                    }
                    val plain = try {
                        CloudCrypto.open(keys, reply.bytes, id.toByteArray())
                    } catch (e: Exception) {
                        throw IOException(t(R.string.backup_broken), e)
                    }
                    if (CloudCrypto.blobId(keys, plain) != id) throw IOException(t(R.string.backup_broken))
                    return plain
                }
                File(base, Backup.INSIDE).outputStream().use { out -> for (id in index.json) out.write(fetch(id)) }
                for (item in index.items) {
                    val f = File(base, item.path).canonicalFile
                    // Nothing goes outside the folder, whatever the index says.
                    if (!f.path.startsWith(base.path + File.separator)) continue
                    f.parentFile?.mkdirs()
                    f.outputStream().use { out -> for (id in item.chunks) out.write(fetch(id)) }
                }
                val restored = core.restoreFolder(base)
                settings.setCloudSeed(seed)
                keeper.write(seed)
                settings.setCloud(true)
                // What the cloud has is what the app has now: named again from the files as they came back.
                stateFile.delete()
                restored
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    /** What went wrong, in her words. */
    fun said(e: Throwable): String = when (e) {
        is ServerException -> when (e.code) {
            "plan" -> t(R.string.cloud_not_plus)
            "backup_full" -> t(R.string.cloud_full)
            "vault_key" -> t(R.string.cloud_wrong_code)
            else -> t(R.string.cloud_later)
        }
        is IOException -> e.message?.takeIf { it == t(R.string.backup_broken) || it == t(R.string.backup_not_found) || it == t(R.string.cloud_changed_meanwhile) } ?: t(R.string.cloud_offline)
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    private companion object {
        const val JSON = "application/json"
        const val BYTES = "application/octet-stream"
        val INDEX_AAD = "novelo-backup-index-v1".toByteArray()
    }
}
