package com.villamediana.novelo.mind

import com.villamediana.novelo.backup.VectorsFile
import com.villamediana.novelo.data.Vector
import com.villamediana.novelo.local.SenseEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Finding by meaning with real vectors: the made-up notes and searches of tools/sense-check, as
 * EmbeddingGemma 2 made them on the computer with the app's own code (test resources).
 */
class SenseIndexTest {
    private val space = "eg2-udq4kxl/d512/p1"

    private val fixtures: Map<String, Map<Int, FloatArray>> by lazy {
        javaClass.getResourceAsStream("/sense/fixtures.txt")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split(' ') }
            .groupBy({ it[0] }, { it[1].toInt() to FloatArray(it.size - 2) { i -> it[i + 2].toFloat() } })
            .mapValues { (_, v) -> v.toMap() }
    }
    private val notes get() = fixtures.getValue("note")
    private val searches get() = fixtures.getValue("search")

    private fun row(kind: Int, id: Long, v: FloatArray, chunk: Int = 0, start: Int = 0): Vector {
        val (q, scale) = SenseIndex.quantize(v)
        return Vector(kind, id, chunk, space, id * 31 + chunk, start, scale, q)
    }

    private val index by lazy { SenseIndex.of(space, 512, notes.map { (i, v) -> row(Vector.KIND_ENTRY, i.toLong(), v) }) }

    @Test
    fun eachSearchFindsItsNoteFirstInAnyLanguage() {
        // tools/sense-check: search → the note it is about.
        val want = listOf(0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        want.forEachIndexed { s, n ->
            val found = index.nearest(searches.getValue(s), Vector.KIND_ENTRY)
            assertEquals("search $s", n.toLong(), found.first().id)
        }
        // "hay algún número de reparos" (Spanish, no word in common) → "Telefone do encanador".
        assertEquals(0L, index.nearest(searches.getValue(0), Vector.KIND_ENTRY).first().id)
    }

    @Test
    fun whatIsFarIsLeftOutAndOnlyTheAllowedOnesCount() {
        val q = searches.getValue(9) // "dia na praia"
        val found = index.nearest(q, Vector.KIND_ENTRY)
        assertTrue(found.all { it.cos >= SenseIndex.FLOOR && it.cos >= found.first().cos - SenseIndex.SPREAD })
        assertTrue("$found", found.size < notes.size)
        // Among some only (the photos in the gallery, the files out of sensitive things).
        assertTrue(index.nearest(q, Vector.KIND_ENTRY, allowed = setOf(1L, 2L)).all { it.id in setOf(1L, 2L) })
        // Another kind: nothing.
        assertTrue(index.nearest(q, Vector.KIND_PHOTO).isEmpty())
    }

    @Test
    fun anItemCountsByItsClosestPieceAndSaysWhereItStarts() {
        val rows = listOf(
            row(Vector.KIND_ENTRY, 100, notes.getValue(2), chunk = 0, start = 0),   // ração
            row(Vector.KIND_ENTRY, 100, notes.getValue(8), chunk = 1, start = 812), // a praia
            row(Vector.KIND_ENTRY, 200, notes.getValue(7)),
        )
        val near = SenseIndex.of(space, 512, rows).nearest(searches.getValue(9), Vector.KIND_ENTRY).first()
        assertEquals(100L, near.id)
        assertEquals(812, near.start)
    }

    @Test
    fun eightBitsKeepTheMeaning() {
        val a = searches.getValue(1)
        val b = notes.getValue(0)
        val exact = a.indices.sumOf { (a[it] * b[it]).toDouble() }
        val (q, scale) = SenseIndex.quantize(b)
        val approx = a.indices.sumOf { (a[it] * q[it]).toDouble() } * scale
        assertEquals(exact, approx, 0.005)
        assertEquals(1.0, SenseEngine.cut(FloatArray(768) { 1f }, 512)!!.sumOf { (it * it).toDouble() }, 1e-5)
        assertNull(SenseEngine.cut(FloatArray(768), 512))
    }

    @Test
    fun foundBothWaysComesFirstAndHerWordsLeadATie() {
        assertEquals(listOf("b", "a", "c", "d"), Fuse.rrf(listOf("a", "b", "c"), listOf("b", "d")))
        assertEquals(listOf("a", "x"), Fuse.rrf(listOf("a"), listOf("x")))
        assertEquals(listOf("a", "b"), Fuse.rrf(listOf("a", "b"), emptyList()))
        assertEquals(listOf("x", "y"), Fuse.rrf(emptyList(), listOf("x", "y")))
    }

    @Test
    fun theVectorsFileGoesAndComesBackWhole() {
        val rows = notes.map { (i, v) -> row(if (i % 2 == 0) Vector.KIND_ENTRY else Vector.KIND_PHOTO, i.toLong(), v, chunk = i % 3, start = i * 10) } +
            row(Vector.KIND_FILE, 99, notes.getValue(0)).let { Vector(it.kind, it.itemId, it.chunk, "outro", it.hash, it.start, it.scale, it.vec) }
        val out = ByteArrayOutputStream()
        VectorsFile.write(out, space, 512, rows)
        val back = VectorsFile.read(ByteArrayInputStream(out.toByteArray()))!!
        assertEquals(space, back.space)
        assertEquals(512, back.dims)
        // Only this space's.
        assertEquals(notes.size, back.rows.size)
        back.rows.zip(rows).forEach { (b, a) ->
            assertEquals(listOf(a.kind, a.itemId, a.chunk, a.start, a.hash), listOf(b.kind, b.itemId, b.chunk, b.start, b.hash))
            assertEquals(a.scale, b.scale, 0f)
            assertTrue(a.vec.contentEquals(b.vec))
        }
        // Not this file, or cut short: nothing.
        assertNull(VectorsFile.read(ByteArrayInputStream("PK\u0003\u0004".toByteArray())))
        assertNull(VectorsFile.read(ByteArrayInputStream(out.toByteArray().copyOf(out.size() - 100))))
    }
}
