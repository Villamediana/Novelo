package com.villamediana.novelo.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

/**
 * The space a meaning is written in: the model, its file, how many numbers are kept and how the
 * texts are put to it. Vectors of different spaces never meet: a new [id] (another model, size or
 * prompt) means every text is embedded again.
 */
data class Space(
    val id: String,
    val dims: Int,
    /** What a search is put after (the model's prompt for a query). */
    val query: String,
    /** A note, photo or file: its title (or "none") and its text, in the model's prompt for a document. */
    val document: (title: String, text: String) -> String,
) {
    companion object {
        /** EmbeddingGemma 2 (Google, Apache 2.0) in 4 bits, its first 512 numbers. */
        val EG2_512 = Space(
            id = "eg2-udq4kxl/d512/p1",
            dims = 512,
            query = "task: search result | query: ",
            document = { title, text -> "title: " + title.ifBlank { "none" } + " | text: " + text },
        )
    }
}

/** A text's meaning, as numbers (see [Space]); null when there is nobody to say it now. */
interface Embedder {
    val space: Space

    /** It can embed now (the model is here and loads). */
    suspend fun ready(): Boolean

    /** A search's meaning (cut to [Space.dims] and of length 1). */
    suspend fun query(text: String): FloatArray?

    /** A note's, a photo's or a file's meaning: [title] (blank: none) and [text]. */
    suspend fun document(title: String, text: String): FloatArray?

    /** Loads the model now (the search opened), so the first answer comes sooner. */
    suspend fun warm() {}

    /** Lets the model go if it wasn't used for [idleMs]. */
    suspend fun releaseIfIdle(idleMs: Long) {}
}

/** Nobody embeds (a build without the engine, the tests): the search finds by words. */
object NoEmbedder : Embedder {
    override val space = Space.EG2_512
    override suspend fun ready() = false
    override suspend fun query(text: String): FloatArray? = null
    override suspend fun document(title: String, text: String): FloatArray? = null
}

/**
 * EmbeddingGemma 2 on the phone (llama.cpp, the same library as Omni, novelo_sense.h): its own
 * handle and lock, so it never sends Omni away nor waits for it. ~190 MB of RAM while loaded, let
 * go after a while unused. A model that won't load is not tried again until the app opens again.
 */
class SenseEngine(private val model: () -> File?, override val space: Space = Space.EG2_512) : Embedder {
    private val lock = Mutex()
    @Volatile private var handle = 0L
    @Volatile private var lastUse = 0L
    @Volatile private var broken = false

    override suspend fun ready(): Boolean = LocalEngine.available && !broken && model() != null

    override suspend fun query(text: String): FloatArray? = embed(space.query + text.trim())

    override suspend fun document(title: String, text: String): FloatArray? = embed(space.document(title.trim(), text.trim()))

    override suspend fun warm() {
        if (ready()) lock.withLock { withContext(Dispatchers.Default) { loaded() } }
    }

    override suspend fun releaseIfIdle(idleMs: Long) {
        if (handle == 0L || System.currentTimeMillis() - lastUse < idleMs) return
        lock.withLock {
            if (handle != 0L) nativeFree(handle)
            handle = 0L
        }
    }

    private suspend fun embed(prompt: String): FloatArray? {
        if (!ready()) return null
        return lock.withLock {
            withContext(Dispatchers.Default) {
                val h = loaded() ?: return@withContext null
                lastUse = System.currentTimeMillis()
                nativeEmbed(h, prompt.toByteArray())?.let { cut(it, space.dims) }
            }
        }
    }

    /** The model in memory (loaded now if it wasn't); null when it can't be. */
    private fun loaded(): Long? {
        if (handle != 0L) return handle
        val file = model() ?: return null
        val h = nativeLoad(file.path, LocalEngine.threads(), CONTEXT)
        if (h == 0L) {
            broken = true
            return null
        }
        handle = h
        lastUse = System.currentTimeMillis()
        return h
    }

    private external fun nativeLoad(model: String, threads: Int, nCtx: Int): Long
    private external fun nativeEmbed(handle: Long, text: ByteArray): FloatArray?
    private external fun nativeFree(handle: Long)

    companion object {
        /** Tokens per text at most (the pieces are cut well under it). */
        const val CONTEXT = 512

        /** The first [dims] numbers of [v], made of length 1 (the model is trained to be cut there). */
        fun cut(v: FloatArray, dims: Int): FloatArray? {
            val out = v.copyOf(minOf(dims, v.size))
            var n = 0.0
            for (x in out) n += x * x
            n = sqrt(n)
            if (n == 0.0 || n.isNaN()) return null
            for (i in out.indices) out[i] = (out[i] / n).toFloat()
            return out
        }
    }
}
