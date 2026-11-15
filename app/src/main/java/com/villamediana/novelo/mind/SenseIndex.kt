package com.villamediana.novelo.mind

import com.villamediana.novelo.data.Vector
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Every vector of one space in memory, side by side (a few MB for thousands of pieces): a search
 * compares its meaning with all of them at once, on the phone, in milliseconds.
 */
class SenseIndex private constructor(
    val space: String,
    val dims: Int,
    private val kinds: ByteArray,
    private val ids: LongArray,
    private val starts: IntArray,
    private val scales: FloatArray,
    private val data: ByteArray,
) {
    val size: Int get() = ids.size

    /** One item found by meaning: how close ([cos], -1 to 1) and where its closest piece starts. */
    data class Near(val id: Long, val cos: Float, val start: Int)

    /**
     * The items of [kind] closest to [q] (a search's meaning, of length 1), among [allowed] (null:
     * all), each by its closest piece: at most [limit], none under [FLOOR], and none much further than
     * the closest one ([SPREAD]) — what is left is far from what she asked.
     */
    fun nearest(q: FloatArray, kind: Int, allowed: Set<Long>? = null, limit: Int = 40): List<Near> {
        if (q.size != dims || size == 0) return emptyList()
        val best = HashMap<Long, Near>()
        for (i in 0 until size) {
            if (kinds[i].toInt() != kind) continue
            val id = ids[i]
            if (allowed != null && id !in allowed) continue
            var s = 0f
            val at = i * dims
            for (d in 0 until dims) s += q[d] * data[at + d]
            val cos = s * scales[i]
            val was = best[id]
            if (was == null || cos > was.cos) best[id] = Near(id, cos, starts[i])
        }
        val top = best.values.maxOfOrNull { it.cos } ?: return emptyList()
        val cut = maxOf(FLOOR, top - SPREAD)
        return best.values.filter { it.cos >= cut }.sortedByDescending { it.cos }.take(limit)
    }

    companion object {
        /** Under this, nothing is near (measured: what a search is about ≥ 0.65; nine in ten of the rest < 0.64). */
        const val FLOOR = 0.62f

        /** Further than this from the closest one found, it is something else. */
        const val SPREAD = 0.07f

        fun of(space: String, dims: Int, rows: List<Vector>): SenseIndex {
            val ok = rows.filter { it.space == space && it.vec.size == dims }
            val data = ByteArray(ok.size * dims)
            ok.forEachIndexed { i, v -> System.arraycopy(v.vec, 0, data, i * dims, dims) }
            return SenseIndex(
                space, dims,
                ByteArray(ok.size) { ok[it].kind.toByte() },
                LongArray(ok.size) { ok[it].itemId },
                IntArray(ok.size) { ok[it].start },
                FloatArray(ok.size) { ok[it].scale },
                data,
            )
        }

        /** [v] in 8 bits: the numbers and the one scale that brings them back (v[i] ≈ q[i] × scale). */
        fun quantize(v: FloatArray): Pair<ByteArray, Float> {
            val max = v.maxOfOrNull { abs(it) } ?: 0f
            if (max == 0f) return ByteArray(v.size) to 0f
            val scale = max / 127f
            return ByteArray(v.size) { (v[it] / scale).roundToInt().coerceIn(-127, 127).toByte() } to scale
        }
    }
}

/**
 * Two orders of the same things made one (reciprocal rank fusion): by her words and by meaning.
 * Found both ways comes first; then each by how high it came in either. The words weigh a little
 * more: what she wrote, found as she wrote it, stays ahead of what only means something close.
 */
object Fuse {
    private const val K = 60.0
    private const val MEANING = 0.9

    fun <T> rrf(words: List<T>, meaning: List<T>): List<T> {
        if (meaning.isEmpty()) return words
        val score = LinkedHashMap<T, Double>()
        words.forEachIndexed { i, t -> score[t] = (score[t] ?: 0.0) + 1.0 / (K + i) }
        meaning.forEachIndexed { i, t -> score[t] = (score[t] ?: 0.0) + MEANING / (K + i) }
        val wordRank = words.withIndex().associate { it.value to it.index }
        return score.entries
            .sortedWith(compareByDescending<Map.Entry<T, Double>> { it.value }.thenBy { wordRank[it.key] ?: Int.MAX_VALUE })
            .map { it.key }
    }
}
