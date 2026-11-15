package com.villamediana.novelo.mind

import androidx.test.core.app.ApplicationProvider
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.audio.Wav
import com.villamediana.novelo.server.FakeNovelo
import com.villamediana.novelo.server.MemoryBox
import com.villamediana.novelo.server.NoveloServer
import com.villamediana.novelo.settings.Source
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** The Novelo's own intelligence hears, sees and thinks: the app sends the request whole, the server's model answers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoveloMindTest {
    @get:Rule val tmp = TemporaryFolder()
    private val fake = FakeNovelo()
    private val graph get() = NoveloApplication.graph
    private val st get() = graph.settings
    private lateinit var server: NoveloServer

    @Before fun start() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        server = NoveloServer(OkHttpClient(), fake.url, MemoryBox(), context.getSharedPreferences("mind-${System.nanoTime()}", 0), { null }, "1.73.0", "com.villamediana.novelo")
        runBlocking { st.setHear(Source.NOVELO); st.setSee(Source.NOVELO); st.setThink(Source.NOVELO) }
    }

    @After fun stop() {
        fake.close()
        runBlocking { st.setHear(Source.LOCAL); st.setSee(Source.LOCAL); st.setThink(Source.NOVELO) }
    }

    /** [seconds] of a voice-like tone (not silence). */
    private fun talk(seconds: Int): File {
        val samples = FloatArray(seconds * Wav.RATE) { i -> (0.4 * sin(2 * PI * 220 * i / Wav.RATE)).toFloat() }
        return File(tmp.root, "talk-${System.nanoTime()}.wav").also { Wav.write(it, samples) }
    }

    @Test
    fun theServerHearsEachPieceOfWhatSheSaid() = runBlocking {
        val hearing = Hearing(st, graph.api, graph.engine, graph.localModel, tmp.newFolder("pieces"), server)
        assertNull(hearing.readiness(st.current()))
        fake.reply = { _, n -> fake.answer(if (n == 1) "\"comprar pão" else "e ligar pra Alma\"") }
        val kept = ArrayList<Heard>()
        // A minute of talk: more than one piece, each heard by the server and kept as it comes.
        val text = hearing.transcribe(talk(60), onPiece = { kept += it }, onPartial = {})
        assertTrue(fake.asks.size >= 2)
        assertTrue(text, text.startsWith("comprar pão e ligar pra Alma"))
        assertEquals("hear", fake.asks.first().job)
        val sent = fake.asks.first().body
        assertTrue(sent.contains("input_audio") && sent.contains("\"format\":\"wav\"") && !sent.contains("\"model\""))
        assertEquals("novelo:28p", kept.last().by)
        assertEquals(kept.last().total, kept.last().pieces)
        // Nothing said in a piece: the model answers a dash, and nothing is added.
        fake.reply = { _, _ -> fake.answer("-") }
        assertEquals("", hearing.transcribe(talk(5), onPartial = {}))
        // Silence isn't even sent.
        val before = fake.asks.size
        val quiet = File(tmp.root, "quiet.wav").also { Wav.write(it, FloatArray(3 * Wav.RATE)) }
        assertEquals("", hearing.transcribe(quiet, onPartial = {}))
        assertEquals(before, fake.asks.size)
    }

    @Test
    fun anAnswerThatIsNotAPlanIsAskedOfAnotherModel() = runBlocking {
        val thinking = Thinking(st, graph.api, graph.engine, graph.localModel, server)
        assertNull(thinking.readiness(st.current()))
        fake.reply = { _, n -> if (n == 1) fake.answer("desculpe, não entendi", route = "r1") else fake.answer("""{"reply":"anotado"}""", route = "r2") }
        val out = thinking.answer("instruções", "comprar pão")
        assertEquals("""{"reply":"anotado"}""", out)
        assertEquals(2, fake.asks.size)
        assertEquals("think", fake.asks[0].job)
        assertNull(fake.asks[0].not)
        assertEquals("r1", fake.asks[1].not)
        assertTrue(fake.asks[0].body.contains("json_object") && fake.asks[0].body.contains("\"reasoning_effort\":\"low\""))
        // The photo never goes to who thinks: only words.
        assertFalse(fake.asks[0].body.contains("image_url"))
    }

    @Test
    fun theServerSeesAPhotoAndWhenItIsOverSheIsToldToChoose() = runBlocking {
        val seeing = Seeing(st, graph.api, NoAbout, server)
        fake.reply = { _, _ -> fake.answer("\"O Cooper deitado no sofá azul.\"") }
        assertEquals("O Cooper deitado no sofá azul.", seeing.photo(byteArrayOf(1, 2, 3)))
        assertEquals("see", fake.asks.last().job)
        assertTrue(fake.asks.last().body.contains("data:image/jpeg;base64,"))
        // The server says the Novelo's intelligence is over for this phone.
        val ended = """{"kind":"ended","active":false}"""
        fake.reply = { _, _ -> fake.error(402, "plan", ""","plan":$ended""") }
        assertNull(seeing.photo(byteArrayOf(1, 2, 3)))
        assertTrue(server.ended)
        val asked = fake.asks.size
        // From then on nothing is even sent: hearing and thinking say what is missing.
        val hearing = Hearing(st, graph.api, graph.engine, graph.localModel, tmp.newFolder("p2"), server)
        val thinking = Thinking(st, graph.api, graph.engine, graph.localModel, server)
        for (work in listOf<suspend () -> Any>({ hearing.transcribe(talk(3), onPartial = {}) }, { thinking.answer("i", "u") })) {
            try {
                work()
                fail("it went on")
            } catch (e: NotReady) {
                assertTrue(e.message.orEmpty().isNotBlank())
            }
        }
        assertEquals(asked, fake.asks.size)
        // With the model on the phone or her API, she goes on.
        st.setThink(Source.API)
        assertEquals(com.villamediana.novelo.settings.t(com.villamediana.novelo.R.string.need_api), thinking.readiness(st.current()))
    }
}
