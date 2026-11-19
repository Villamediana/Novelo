package com.villamediana.novelo.mind

import androidx.test.core.app.ApplicationProvider
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.NoveloDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/** "Em tópicos" (1.80): a note's points — the model decides when a note is worth them; read, cleaned, kept up to date. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SummaryTest {
    private lateinit var db: NoveloDatabase
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val now = LocalDateTime.of(2026, 11, 19, 10, 0).atZone(zone).toInstant().toEpochMilli()

    @Before fun open() { db = NoveloDatabase.inMemory(ApplicationProvider.getApplicationContext()) }
    @After fun close() { db.close() }

    private fun keeper() = Keeper(db, zone = { zone }, clock = { now })

    /** About 60 words, two subjects: the kind of note that gets points. */
    private val party = "Pensei em fazer o aniversário da Ana na chácara do tio, mas se chover pode ser no salão do prédio. " +
        "Seria no sábado dia 12, à tarde, umas quatro horas. Pra comer, churrasco, e o bolo eu peço pra Dona Lúcia, " +
        "que faz aquele de cenoura com chocolate que ela adora. Ainda falta ver quantas pessoas vêm, acho que umas trinta."

    private val points = listOf("Local: a chácara do tio; se chover, o salão do prédio", "Sábado, dia 12, às 16h", "Comida: churrasco; o bolo de cenoura com a Dona Lúcia", "Umas 30 pessoas")

    private fun json(s: String) = "\"" + s.replace("\"", "\\\"") + "\""
    private fun list(p: List<String>) = p.joinToString(",", "[", "]") { json(it) }

    @Test
    fun theModelsPointsComeCleanHoweverTheyAreWritten() {
        assertEquals(
            listOf("Local: chácara", "Data: sábado", "Bolo: Dona Lúcia", "Convidados: 30"),
            Summary.clean(listOf("- Local: chácara\n• Data: sábado", "1. **Bolo:** Dona Lúcia", "  ", "2) Convidados: 30", "- local: chácara", "—")),
        )
        assertEquals(Summary.MAX_POINTS, Summary.clean((1..50).map { "ponto $it" }).size)
        assertEquals(Summary.MAX_POINT, Summary.clean(listOf("a".repeat(500))).single().length)
        // Kept as one point per line, and read back the same.
        assertEquals(listOf("a", "b"), Summary.read(Summary.write(listOf("a", "b"))))
        assertEquals(emptyList<String>(), Summary.read(""))
    }

    @Test
    fun aListATextOrSomeShapeAreAllPointsAndTheAnswerIsNeverLost() {
        fun summaryOf(value: String) = Brain.parse("""{"reply":"ok","create":[{"kind":"note","title":"Festa","body":"x","summary":$value}]}""")!!.create.single().summary
        assertEquals(listOf("a", "b"), summaryOf("""["a","b"]"""))
        assertEquals(listOf("- a\n- b"), summaryOf(""""- a\n- b""""))
        assertEquals(listOf("Festa: a", "Festa: b", "Data: sábado"), summaryOf("""[{"assunto":"Festa","pontos":["a","b"]},{"tema":"Data","texto":"sábado"}]"""))
        assertEquals(emptyList<String>(), summaryOf("null"))
        // On an update too.
        val plan = Brain.parse("""{"reply":"ok","update":[{"id":3,"append":"mais","summary":{"x":["c"]}}]}""")!!
        assertEquals(listOf("c"), plan.update.single().summary)
    }

    @Test
    fun aNoteKeepsThePointsTheModelSawWorthItAndNothingElseHasThem() = runBlocking {
        val plan = Brain.parse(
            """{"reply":"ok","create":[
                {"kind":"note","title":"Aniversário da Ana","body":${json(party)},"summary":${list(points)}},
                {"kind":"note","title":"Senha do wifi","body":"girassol 2026"},
                {"kind":"note","title":"Reunião rápida","body":"Decidimos trocar o fornecedor.","summary":["Decisão: trocar o fornecedor"]},
                {"kind":"task","title":"Ligar pra Dona Lúcia","body":${json(party)},"summary":${list(points)}},
                {"kind":"diary","title":"Hoje","body":${json(party)},"summary":${list(points)}}
            ]}""",
        )!!
        val r = keeper().apply(plan, captureId = null)
        val (long, none, short, task, diary) = r.outcomes.map { db.entries().get(it.entryId)!! }
        assertEquals(points, Summary.read(long.summary))
        assertEquals("", none.summary) // the model saw it is read at a glance
        // Short or with one point: the model's call, kept as it said.
        assertEquals(listOf("Decisão: trocar o fornecedor"), Summary.read(short.summary))
        assertEquals("", task.summary)
        assertEquals("", diary.summary)
    }

    @Test
    fun newsOfANoteBringsItsPointsUpToDate() = runBlocking {
        val id = db.entries().insert(Entry(kind = "note", title = "Aniversário da Ana", body = party, summary = Summary.write(points), createdAt = now - 60_000, updatedAt = now - 60_000))
        val more = points + "Convites: mandar pelo grupo da família"
        // News joined to it, with the points whole again.
        keeper().apply(Brain.parse("""{"reply":"ok","update":[{"id":$id,"append":"Os convites eu mando pelo grupo da família.","summary":${list(more)}}]}""")!!, captureId = null)
        assertEquals(more, Summary.read(db.entries().get(id)!!.summary))
        // News with no points: the ones it had still hold.
        keeper().apply(Brain.parse("""{"reply":"ok","update":[{"id":$id,"append":"A Ana não pode saber."}]}""")!!, captureId = null)
        assertEquals(more, Summary.read(db.entries().get(id)!!.summary))
        // Points that come empty of anything (only bullets): the same.
        keeper().apply(Brain.parse("""{"reply":"ok","update":[{"id":$id,"append":"Vai ser surpresa.","summary":["-", " "]}]}""")!!, captureId = null)
        assertEquals(more, Summary.read(db.entries().get(id)!!.summary))
        // Only its title: the same.
        keeper().apply(Brain.parse("""{"reply":"ok","update":[{"id":$id,"title":"Festa surpresa da Ana"}]}""")!!, captureId = null)
        assertEquals(more, Summary.read(db.entries().get(id)!!.summary))
        // Its text written again without points: the old ones went with it.
        keeper().apply(Brain.parse("""{"reply":"ok","update":[{"id":$id,"body":"Desisti da festa, vamos só jantar fora no sábado."}]}""")!!, captureId = null)
        val after = db.entries().get(id)!!
        assertEquals("Desisti da festa, vamos só jantar fora no sábado.", after.body)
        assertEquals("", after.summary)
    }

    @Test
    fun aTaskNeverGetsPointsFromAnUpdate() = runBlocking {
        val id = db.entries().insert(Entry(kind = "task", title = "Organizar a festa", status = "todo", body = party, createdAt = now, updatedAt = now))
        keeper().apply(Brain.parse("""{"reply":"ok","update":[{"id":$id,"append":"Mais uma coisa.","summary":${list(points)}}]}""")!!, captureId = null)
        assertEquals("", db.entries().get(id)!!.summary)
    }

    @Test
    fun theModelSeesANotesPointsSoItCanWriteThemAgain() {
        val e = Entry(id = 4, kind = "note", title = "Aniversário da Ana", body = party, summary = Summary.write(points.take(2)), createdAt = now, updatedAt = now)
        val line = Brain.line(e, zone, now, long = true)
        assertTrue(line, line.contains(" · em tópicos: Local: a chácara do tio; se chover, o salão do prédio | Sábado, dia 12, às 16h — Pensei"))
        // Not in the short line, nor for a sensitive note.
        assertFalse(Brain.line(e, zone, now).contains("em tópicos"))
        assertFalse(Brain.line(e.copy(sensitive = true), zone, now, long = true).contains("em tópicos"))
        // And the instructions say what it is.
        assertTrue(Brain.SYSTEM.contains("- summary (só em nota)"))
    }

    @Test
    fun aPointSaysWhatItIsAboutOnlyWhenItStartsSo() {
        assertEquals("Local" to "a chácara do tio", Summary.label("Local: a chácara do tio"))
        assertNull(Summary.label("10:30 reunião com o Pedro"))
        assertNull(Summary.label("Às 10:30 a reunião"))
        assertNull(Summary.label("https://novelo.app"))
        assertNull(Summary.label("Uma frase longa demais pra ser um assunto: e o resto"))
        assertNull(Summary.label("Sem nada depois:"))
        assertNotNull(Summary.label("Comida: churrasco"))
    }
}
