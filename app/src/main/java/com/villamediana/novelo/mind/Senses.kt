package com.villamediana.novelo.mind

import com.villamediana.novelo.data.NoveloDatabase
import com.villamediana.novelo.data.Vector
import com.villamediana.novelo.local.Embedder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What is near a text by meaning (the Mind asks it, to put the right things before the model). */
interface Nearby {
    /** The items of [kind] closest in meaning to [text], the closest first, among [allowed] (null: all); empty when the meaning can't be had now. */
    suspend fun near(text: String, kind: Int, allowed: Set<Long>? = null, limit: Int = 12): List<SenseIndex.Near>

    object NONE : Nearby {
        override suspend fun near(text: String, kind: Int, allowed: Set<Long>?, limit: Int) = emptyList<SenseIndex.Near>()
    }
}

/**
 * The search by meaning (1.74.0): every note, task, diary, photo and file keeps its meaning as
 * numbers ([Vector]), made on the phone ([Embedder]) and kept in the database and the backup. When
 * something changes, a pass a moment later makes what is missing or changed — only that: a text
 * already embedded (the same piece, an item put back) is never embedded again — and lets go what
 * no longer is. The Mind goes first: a pass waits while something said is going through, and stops
 * to let it. A search's meaning is made on the phone too, and compared with all of them in memory
 * ([index]): no network, no cost, nothing she types leaves the phone.
 */
class Senses(
    private val db: NoveloDatabase,
    private val embedder: Embedder,
    private val scope: CoroutineScope,
    /** Something said is going through now: the phone is its first. */
    private val busy: () -> Boolean = { false },
    /** Vectors were made or let go: the backup takes them (once per pass). */
    private val changed: () -> Unit = {},
    /** The quiet after a change before a pass (a talk is many changes). */
    private val quietMs: Long = QUIET_MS,
) : Nearby {
    private val space get() = embedder.space

    private val _index = MutableStateFlow<SenseIndex?>(null)
    /** Every vector, in memory (null before the first look at the database). */
    val index: StateFlow<SenseIndex?> = _index

    /** A pass is making vectors now. */
    @Volatile var working = false
        private set

    private val kicks = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var lastChange = 0L

    /** Pieces the model couldn't embed in this run of the app (not tried again at every change). */
    private val failed = HashSet<Long>()

    /** What was searched (folded) → its meaning: typing back, or searching again, is instant. */
    private val asked = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > ASKED
    }

    private val runner: Job by lazy {
        scope.launch {
            for (kick in kicks) {
                while (true) {
                    val quiet = System.currentTimeMillis() - lastChange
                    if (quiet >= quietMs && !busy()) break
                    delay(if (quiet < quietMs) quietMs - quiet else BUSY_MS)
                }
                runCatching { pass() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            }
        }
    }

    /** Every change in what she keeps makes a pass soon (see [soon]). */
    fun watch() {
        db.invalidationTracker.addObserver(object : androidx.room.InvalidationTracker.Observer(arrayOf("entries", "captures", "entities", "entry_topics")) {
            override fun onInvalidated(tables: Set<String>) = soon()
        })
    }

    /** A pass a moment from now (the model just came, the app opened, something changed). */
    fun soon() {
        lastChange = System.currentTimeMillis()
        runner.start()
        kicks.trySend(Unit)
    }

    /** The vectors in the database, read into memory again (after an import). */
    suspend fun reload() {
        _index.value = withContext(Dispatchers.IO) { SenseIndex.of(space.id, space.dims, db.vectors().all(space.id)) }
    }

    /**
     * Makes what is missing or changed and lets go what no longer is; true when anything changed.
     * Without the model (not here yet) only the letting go: the vectors an import brought stay.
     */
    suspend fun pass(): Boolean = withContext(Dispatchers.IO) {
        val v = db.vectors()
        val s = space
        var any = false
        v.deleteOtherSpaces(s.id)
        val wanted = wanted()
        val have = v.keys().associateBy { Triple(it.kind, it.itemId, it.chunk) }
        val keep = wanted.mapTo(HashSet()) { Triple(it.kind, it.itemId, it.chunk) }
        for (k in have.keys) if (k !in keep) { v.delete(k.first, k.second, k.third); any = true }
        val todo = wanted.filter { p -> have[Triple(p.kind, p.itemId, p.chunk)]?.hash != p.hash(s) }
        if (todo.isNotEmpty()) {
            val ready = embedder.ready()
            working = true
            try {
                val made = ArrayList<Vector>()
                for (p in todo) {
                    if (!currentCoroutineContext().isActive) break
                    // Something said came in: it goes first; the pass goes on after it.
                    if (busy()) { soon(); break }
                    val h = p.hash(s)
                    if (h in failed) continue
                    val again = v.byHash(s.id, h)
                    val numbers = again?.let { it.vec to it.scale }
                        ?: if (ready) embedder.document(p.title, p.text)?.let { SenseIndex.quantize(it) } else null
                    if (numbers == null) {
                        if (!ready) break
                        failed += h
                        continue
                    }
                    made += Vector(p.kind, p.itemId, p.chunk, s.id, h, p.start, numbers.second, numbers.first)
                    if (made.size >= BATCH) { v.put(made); made.clear(); any = true }
                }
                if (made.isNotEmpty()) { v.put(made); any = true }
            } finally {
                working = false
            }
        }
        if (any || _index.value == null) reload()
        if (any) runCatching { changed() }
        any
    }

    /** Everything there is to embed, the newest first: what she searches for is mostly recent. */
    private suspend fun wanted(): List<SenseText.Piece> {
        val names = db.graph().allTopics().associate { it.id to it.name }
        val ties = db.graph().allTies().groupBy({ it.entryId }, { it.topicId })
        val entries = db.entries().all().sortedByDescending { it.updatedAt }
            .flatMap { e -> SenseText.pieces(e, ties[e.id].orEmpty().mapNotNull(names::get)) }
        val captures = db.backup().captures().sortedByDescending { it.createdAt }
            .flatMap { c -> SenseText.pieces(c) + SenseText.filePieces(c) }
        return entries + captures
    }

    /** The meaning of a search ([text] as she typed it); null when it can't be had now (no model) or says too little. */
    suspend fun query(text: String): FloatArray? {
        val key = Words.of(text).joinToString(" ")
        if (key.count { it.isLetter() } < 3) return null
        synchronized(asked) { asked[key] }?.let { return it }
        val q = runCatching { embedder.query(text) }.getOrNull() ?: return null
        synchronized(asked) { asked[key] = q }
        return q
    }

    /** The items of [kind] near [q] (a search's meaning), among [allowed]. */
    fun nearest(q: FloatArray, kind: Int, allowed: Set<Long>? = null, limit: Int = 40): List<SenseIndex.Near> =
        _index.value?.nearest(q, kind, allowed, limit).orEmpty()

    override suspend fun near(text: String, kind: Int, allowed: Set<Long>?, limit: Int): List<SenseIndex.Near> =
        withTimeoutOrNull(ASK_MS) { query(text.take(QUERY_CHARS))?.let { nearest(it, kind, allowed, limit) } }.orEmpty()

    /** Loads the model now (the search opened). */
    suspend fun warm() = runCatching { embedder.warm() }.let { }

    companion object {
        const val QUIET_MS = 2_000L
        const val BUSY_MS = 1_000L

        /** Vectors written at a time (each write is a change the screens hear about). */
        const val BATCH = 16

        const val ASKED = 64

        /** What the Mind waits for a meaning at most (a model that didn't load yet doesn't hold up an answer). */
        const val ASK_MS = 1_500L

        /** A long thing said: its start says what it is about. */
        const val QUERY_CHARS = 1_000
    }
}
