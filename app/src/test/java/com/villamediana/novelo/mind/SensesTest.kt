package com.villamediana.novelo.mind

import androidx.test.core.app.ApplicationProvider
import com.villamediana.novelo.data.Capture
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.NoveloDatabase
import com.villamediana.novelo.data.Vector
import com.villamediana.novelo.local.Embedder
import com.villamediana.novelo.local.Space
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.sqrt

/** The meanings kept up to date: only what is missing or changed is embedded, what is gone goes, the Mind goes first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SensesTest {
    private lateinit var db: NoveloDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A model, played: a word's meaning is its group's (a few words mean the same), a text the sum of its words'. */
    private class Fake(var ready: Boolean = true) : Embedder {
        override val space = Space.EG2_512.copy(id = "fake/d512")
        val made = mutableListOf<String>()
        private val same = mapOf("encanador" to "cano", "plumber" to "cano", "reparos" to "cano", "vazamento" to "cano", "praia" to "mar", "beach" to "mar")
        private fun vec(text: String): FloatArray {
            val v = FloatArray(512)
            Words.of(text).filter { it.length >= 3 }.forEach { w -> v[Math.floorMod((same[w] ?: w).hashCode(), 512)] += 1f }
            val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
            return if (n == 0f) v else FloatArray(512) { v[it] / n }
        }
        override suspend fun ready() = ready
        override suspend fun query(text: String): FloatArray? = if (ready) vec(text) else null
        override suspend fun document(title: String, text: String): FloatArray? { if (!ready) return null; made += "$title|$text"; return vec("$title $text") }
    }

    @Before fun open() { db = NoveloDatabase.inMemory(ApplicationProvider.getApplicationContext()) }
    @After fun close() { scope.cancel(); db.close() }

    private suspend fun note(title: String, body: String = "", at: Long = 1) =
        db.entries().insert(Entry(kind = "note", title = title, body = body, createdAt = at, updatedAt = at))

    @Test
    fun onlyWhatIsMissingOrChangedIsEmbedded() = runBlocking {
        val fake = Fake()
        var told = 0
        val senses = Senses(db, fake, scope, changed = { told++ })
        note("Telefone do encanador", "Seu Zé, conserta vazamento", at = 1)
        val beach = note("Dia na praia", at = 2)
        val photo = db.captures().insert(Capture(createdAt = 3, source = "photo", imagePath = "/p.jpg", description = "pôr do sol", state = Capture.DONE))
        assertTrue(senses.pass())
        assertEquals(3, fake.made.size)
        assertEquals(1, told) // one backup for the pass
        assertEquals(3, senses.index.value!!.size)
        // Nothing changed: nothing embedded, nothing told.
        assertFalse(senses.pass())
        assertEquals(3, fake.made.size)
        assertEquals(1, told)
        // Done, a due date: the text is the same, so is its meaning.
        db.entries().update(db.entries().get(beach)!!.copy(status = "done", dueAt = 9, updatedAt = 5))
        assertFalse(senses.pass())
        // Its text changed: only it again.
        db.entries().update(db.entries().get(beach)!!.copy(body = "com a Alma", updatedAt = 6))
        assertTrue(senses.pass())
        assertEquals(4, fake.made.size)
        assertTrue(fake.made.last().startsWith("Dia na praia|com a Alma"))
        // Gone: its meaning goes too.
        db.captures().delete(photo)
        assertTrue(senses.pass())
        assertEquals(2, db.vectors().count(fake.space.id))
        // The same text in another item (a copy): its meaning is taken, not made again.
        val made = fake.made.size
        note("Telefone do encanador", "Seu Zé, conserta vazamento", at = 7)
        assertTrue(senses.pass())
        assertEquals(made, fake.made.size)
        assertEquals(3, db.vectors().count(fake.space.id))
    }

    @Test
    fun aSearchFindsByMeaningNotByWords() = runBlocking {
        val fake = Fake()
        val senses = Senses(db, fake, scope)
        val plumber = note("Telefone do encanador", at = 1)
        note("Comprar ração do Cooper", at = 2)
        senses.pass()
        // No word in common: "reparos" means what "encanador" means (the played model's groups).
        val q = senses.query("reparos")!!
        assertEquals(plumber, senses.nearest(q, Vector.KIND_ENTRY).first().id)
        // The Mind asks the same way.
        assertEquals(plumber, senses.near("plumber", Vector.KIND_ENTRY).first().id)
        // Too little to mean anything: words only.
        assertNull(senses.query("ab"))
    }

    @Test
    fun withoutTheModelTheImportedMeaningsStayAndNothingIsMade() = runBlocking {
        val fake = Fake(ready = false)
        val senses = Senses(db, fake, scope)
        val id = note("Contrato de aluguel")
        // An import brought its meaning (and one of something that isn't here any more).
        val (q, scale) = SenseIndex.quantize(FloatArray(512) { if (it == 3) 1f else 0f })
        val piece = SenseText.pieces(db.entries().get(id)!!).single()
        db.vectors().put(listOf(Vector(Vector.KIND_ENTRY, id, 0, fake.space.id, piece.hash(fake.space), 0, scale, q), Vector(Vector.KIND_ENTRY, 999, 0, fake.space.id, 1, 0, scale, q)))
        senses.pass()
        assertTrue(fake.made.isEmpty())
        assertEquals(listOf(id), db.vectors().keys().map { it.itemId })
        assertEquals(1, senses.index.value!!.size)
        // Another model's vectors are of no use: they go.
        db.vectors().put(listOf(Vector(Vector.KIND_ENTRY, id, 1, "other/d512", 5, 0, scale, q)))
        senses.pass()
        assertTrue(db.vectors().keys().all { it.space == fake.space.id })
    }

    @Test
    fun thePassLetsTheMindGoFirst() = runBlocking {
        val fake = Fake()
        var mindWorking = true
        val senses = Senses(db, fake, scope, busy = { mindWorking })
        note("Um", at = 1); note("Dois", at = 2)
        senses.pass()
        assertTrue(fake.made.isEmpty())
        mindWorking = false
        senses.pass()
        assertEquals(2, fake.made.size)
    }
}
