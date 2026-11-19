package com.villamediana.novelo.mind

/**
 * "Em tópicos" (1.80): what was understood of a note, in points — kept as one point per line in
 * [com.villamediana.novelo.data.Entry.summary]. Whether a note is worth them is the model's call (it
 * sees the note); the code only cleans what it wrote. The same in novelo-server web/src/core/summary.js.
 */
object Summary {
    /** Only so a model that runs away can't fill the page: no note needs more. */
    const val MAX_POINTS = 30
    const val MAX_POINT = 300

    private val BULLET = Regex("""^(?:[-–—•·*▪◦>]+|\d{1,2}[.)])\s+""")

    /** The model's points, one per line: bullets, numbers and bold marks off; blank and repeated ones out. */
    fun clean(points: List<String>): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (raw in points) for (part in raw.split('\n')) {
            val p = part.trim().replace(BULLET, "").replace("**", "").replace(Regex("""\s+"""), " ").trim().take(MAX_POINT).trim()
            if (p.none { it.isLetterOrDigit() }) continue
            if (seen.add(p.lowercase())) out += p
            if (out.size == MAX_POINTS) return out
        }
        return out
    }

    /** The points as they are kept: one per line. */
    fun write(points: List<String>): String = points.joinToString("\n")

    /** The points kept. */
    fun read(summary: String?): List<String> = summary.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** What the model wrote, as it is kept (empty: none). */
    fun keep(points: List<String>): String = write(clean(points))

    /**
     * A point that starts by what it is about ("Local: a chácara do tio"): that part (without the
     * colon) and the rest; null when it doesn't (no colon near the start, or a time like 10:30).
     */
    fun label(point: String): Pair<String, String>? {
        val at = point.indexOf(':')
        if (at < 1 || at > 32 || at == point.length - 1) return null
        val head = point.substring(0, at).trim()
        val rest = point.substring(at + 1).trim()
        if (head.isEmpty() || head.last().isDigit() || head.any { it in ".;!?" } || rest.isEmpty() || rest.startsWith("/")) return null
        return head to rest
    }
}
