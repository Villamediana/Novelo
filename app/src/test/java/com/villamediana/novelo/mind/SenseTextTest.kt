package com.villamediana.novelo.mind

import com.villamediana.novelo.data.Capture
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.Step
import com.villamediana.novelo.data.Vector
import com.villamediana.novelo.local.Space
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What of each thing kept goes to the search by meaning, and when a vector is still right. */
class SenseTextTest {
    private val space = Space.EG2_512
    private fun entry(kind: String = "note", title: String = "", body: String = "", sensitive: Boolean = false, steps: List<String> = emptyList(), tags: List<String> = emptyList()) =
        Entry(id = 7, kind = kind, title = title, body = body, sensitive = sensitive, steps = Steps.write(steps.map { Step(it) }), tags = Tags.write(tags), createdAt = 1, updatedAt = 1)

    @Test
    fun aNoteIsItsTitleTextStepsTagsAndNames() {
        val p = SenseText.pieces(entry("task", "Ligar pro encanador", "o cano da pia vaza", steps = listOf("achar o número"), tags = listOf("casa")), listOf("Seu Zé")).single()
        assertEquals(Vector.KIND_ENTRY, p.kind)
        assertEquals("Ligar pro encanador", p.title)
        assertEquals("o cano da pia vaza\nachar o número\ncasa, Seu Zé", p.text)
        assertEquals("title: Ligar pro encanador | text: o cano da pia vaza\nachar o número\ncasa, Seu Zé", space.document(p.title, p.text))
        // A diary's title is only its date: none.
        assertEquals("", SenseText.pieces(entry("diary", "Quarta-feira, 11 de novembro", "Fui à praia")).single().title)
        assertEquals("title: none | text: Fui à praia", space.document("", "Fui à praia"))
        // Nothing to say: nothing.
        assertTrue(SenseText.pieces(entry("diary", "", "")).isEmpty())
    }

    @Test
    fun somethingSensitiveGoesByItsTitleOnly() {
        val p = SenseText.pieces(entry(title = "Senha do wifi", body = "girassol2020", sensitive = true, tags = listOf("casa")), listOf("Mãe")).single()
        assertEquals("Senha do wifi", p.title)
        assertEquals("casa, Mãe", p.text)
        assertTrue(!p.text.contains("girassol"))
    }

    @Test
    fun aLongDiaryGoesInPiecesAndWhatIsAddedChangesOnlyTheLast() {
        val para = (1..12).joinToString("\n\n") { "Parágrafo $it: " + "fui ao mercado e depois ao parque com a Alma. ".repeat(3) }
        val pieces = SenseText.pieces(entry("diary", body = para))
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.text.length <= SenseText.PIECE })
        assertEquals(pieces.indices.toList(), pieces.map { it.chunk })
        // Each piece's start is where its text is in the body.
        pieces.forEach { assertTrue(para.substring(it.start).startsWith(it.text.take(30))) }
        // The day goes on: the pieces before the last stay the same (no model for them again).
        val more = SenseText.pieces(entry("diary", body = para + "\n\nE à noite vi um filme."))
        val before = pieces.dropLast(1).map { it.hash(space) }
        assertEquals(before, more.take(before.size).map { it.hash(space) })
        assertNotEquals(pieces.last().hash(space), more[pieces.size - 1].hash(space))
    }

    @Test
    fun aParagraphWithNoBreaksIsCutAnyway() {
        val wall = "a".repeat(SenseText.PIECE * 2 + 10)
        val cuts = SenseText.cut(wall)
        assertEquals(3, cuts.size)
        assertEquals(listOf(0, SenseText.PIECE, SenseText.PIECE * 2), cuts.map { it.first })
    }

    @Test
    fun theHashChangesWithTheTextAndTheSpaceOnly() {
        val a = SenseText.pieces(entry(title = "Contrato", body = "vence em março")).single()
        val same = SenseText.pieces(entry(title = "Contrato", body = "vence em março").copy(status = "done", dueAt = 99, updatedAt = 50)).single()
        assertEquals(a.hash(space), same.hash(space))
        val other = SenseText.pieces(entry(title = "Contrato", body = "vence em abril")).single()
        assertNotEquals(a.hash(space), other.hash(space))
        assertNotEquals(a.hash(space), a.hash(space.copy(id = "outro")))
    }

    @Test
    fun aPhotoIsWhatItShowsAndWhatIsWrittenAFileItsNameAndWhatItIs() {
        val photo = Capture(id = 3, createdAt = 1, source = "photo", imagePath = "/p.jpg", description = "Alma na praia", transcript = "PRAIA GRANDE", state = Capture.DONE)
        assertEquals("Alma na praia\nPRAIA GRANDE", SenseText.pieces(photo).single().text)
        assertTrue(SenseText.pieces(photo.copy(description = null, transcript = "")).isEmpty())
        val file = Capture(id = 4, createdAt = 1, source = Capture.FILE, filePath = "/f", fileName = "contrato_aluguel-2026.pdf", description = "contrato de aluguel do apartamento", state = Capture.DONE)
        val p = SenseText.filePieces(file).single()
        assertEquals(Vector.KIND_FILE, p.kind)
        assertEquals("contrato aluguel 2026", p.title)
        assertEquals("contrato de aluguel do apartamento", p.text)
        assertTrue(SenseText.pieces(file).isEmpty() && SenseText.filePieces(photo).isEmpty())
    }
}
