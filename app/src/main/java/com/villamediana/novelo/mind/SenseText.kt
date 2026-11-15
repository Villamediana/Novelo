package com.villamediana.novelo.mind

import com.villamediana.novelo.data.Capture
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.Kind
import com.villamediana.novelo.data.Vector
import com.villamediana.novelo.local.Space
import java.security.MessageDigest

/**
 * What of each thing kept goes to the search by meaning, in pieces the model takes whole: the same
 * function decides what to embed and whether what is there is still right ([Piece.hash]).
 */
object SenseText {
    /** One piece: what it is ([kind], [itemId], [chunk]), its title and text for the model, where it starts in the item's text. */
    data class Piece(val kind: Int, val itemId: Long, val chunk: Int, val title: String, val text: String, val start: Int) {
        /** The text as the model gets it, in [space]: what makes a vector right or not. */
        fun hash(space: Space): Long = hashOf(space.id + "\n" + space.document(title, text))
    }

    /** One piece of this size or less goes as it is; a longer text goes in pieces of [PIECE]. */
    const val WHOLE = 1_000
    const val PIECE = 800

    /** A very long text: its first pieces (the rest is found by its words). */
    const val MAX_PIECES = 32

    /** What is written in a photo, at most (a page of a book says enough in its start). */
    const val PHOTO_TEXT = 1_000

    /**
     * An entry's pieces: its title, and its text with its steps (and, in the first piece, its tags
     * and the names it is tied to). A diary's title is its date: none. Something sensitive goes by
     * its title, tags and names only — never its text (a password says nothing by its meaning, and
     * its words still find it). Nothing to say: no pieces.
     */
    fun pieces(e: Entry, topics: List<String> = emptyList()): List<Piece> {
        val title = if (e.kindEnum == Kind.DIARY) "" else e.title.trim()
        val tags = Tags.read(e.tags).map { it.trim() }.filter { it.isNotEmpty() }
        val names = topics.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val about = (tags + names).distinct().joinToString(", ")
        if (e.sensitive) {
            return if (title.isEmpty() && about.isEmpty()) emptyList() else listOf(Piece(Vector.KIND_ENTRY, e.id, 0, title, about, 0))
        }
        val steps = Steps.read(e.steps).map { it.text.trim() }.filter { it.isNotEmpty() }
        val body = e.body.trim()
        val tail = buildString {
            if (steps.isNotEmpty()) append(steps.joinToString("\n"))
            if (about.isNotEmpty()) { if (isNotEmpty()) append('\n'); append(about) }
        }
        val whole = listOf(body, tail).filter { it.isNotEmpty() }.joinToString("\n")
        if (title.isEmpty() && whole.isEmpty()) return emptyList()
        if (title.length + whole.length <= WHOLE) return listOf(Piece(Vector.KIND_ENTRY, e.id, 0, title, whole, 0))
        // Long: the text in pieces from its start, so what is added at the end (a diary's day going
        // on) leaves the pieces before it as they were. The steps, tags and names go with the first.
        val cuts = cut(body)
        return cuts.take(MAX_PIECES).mapIndexed { i, (start, text) ->
            Piece(Vector.KIND_ENTRY, e.id, i, title, if (i == 0 && tail.isNotEmpty()) text + "\n" + tail else text, start)
        }.ifEmpty { listOf(Piece(Vector.KIND_ENTRY, e.id, 0, title, tail, 0)) }
    }

    /** A photo's piece: what it shows and what is written in it. Nothing known of it yet: none. */
    fun pieces(photo: Capture): List<Piece> {
        if (photo.source != "photo" || photo.imagePath == null) return emptyList()
        val text = listOfNotNull(photo.description?.trim(), photo.transcript?.trim()?.take(PHOTO_TEXT))
            .filter { it.isNotEmpty() }.distinct().joinToString("\n")
        return if (text.isEmpty()) emptyList() else listOf(Piece(Vector.KIND_PHOTO, photo.id, 0, "", text, 0))
    }

    /** A file's piece: its name (without the extension) and what it is. */
    fun filePieces(file: Capture): List<Piece> {
        if (file.source != Capture.FILE || file.filePath == null) return emptyList()
        val name = file.fileName.orEmpty().substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim()
        val about = file.description?.trim().orEmpty()
        return if (name.isEmpty() && about.isEmpty()) emptyList() else listOf(Piece(Vector.KIND_FILE, file.id, 0, name, about, 0))
    }

    /**
     * [text] in pieces of [PIECE] characters or less, from its start: whole paragraphs packed
     * together; a paragraph too long by itself is cut at its lines, then at its sentences, then
     * anywhere. Each with where it starts.
     */
    fun cut(text: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        var start = -1
        var end = 0
        fun flush() {
            if (start >= 0) out += start to text.substring(start, end).trim()
            start = -1
        }
        for ((at, unit) in units(text)) {
            if (start >= 0 && at + unit.length - start > PIECE) flush()
            if (start < 0) start = at
            end = at + unit.length
        }
        flush()
        return out.filter { it.second.isNotEmpty() }
    }

    /** The smallest parts [cut] packs, each at most [PIECE] long, with where each starts. */
    private fun units(text: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        fun split(at: Int, s: String, seps: List<String>) {
            if (s.length <= PIECE) { out += at to s; return }
            val sep = seps.firstOrNull { s.contains(it) }
            if (sep == null) {
                var i = 0
                while (i < s.length) { out += (at + i) to s.substring(i, minOf(s.length, i + PIECE)); i += PIECE }
                return
            }
            var i = 0
            while (i < s.length) {
                val j = s.indexOf(sep, i).let { if (it < 0) s.length else it + sep.length }
                split(at + i, s.substring(i, j), seps.drop(seps.indexOf(sep) + 1))
                i = j
            }
        }
        split(0, text, listOf("\n\n", "\n", ". "))
        return out
    }

    /** 64 bits of the SHA-256 of [s]: one text, one hash, on every phone. */
    fun hashOf(s: String): Long {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        var h = 0L
        for (i in 0 until 8) h = (h shl 8) or (d[i].toLong() and 0xff)
        return h
    }
}
