package com.villamediana.novelo.mind

import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.local.LocalModel
import com.villamediana.novelo.settings.Job
import com.villamediana.novelo.settings.Source
import com.villamediana.novelo.ui.home.Missing
import com.villamediana.novelo.ui.home.missing
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
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

/** Who sees: the Novelo's model on the phone, or her API (its own provider, key and model) — and the main screen says when it is missing, without locking. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SeeingTest {
    private val server = MockWebServer()
    private val graph get() = NoveloApplication.graph
    private val st get() = graph.settings
    private val bodies = mutableListOf<String>()

    /** Answers given before the usual one, in order (the test's own). */
    private val replies = ArrayDeque<MockResponse>()

    /** The phone's model, played: it says it saw. */
    private val phone = object : About {
        override suspend fun photo(image: ByteArray) = "visto no celular"
        override suspend fun file(file: FileSeen, said: String?, look: ByteArray?) = "arquivo visto no celular"
    }

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(bodies) { bodies += request.body?.utf8().orEmpty() }
                synchronized(replies) { replies.removeFirstOrNull() }?.let { return it }
                return when {
                    request.url.encodedPath.endsWith("/chat/completions") -> MockResponse.Builder().body("""{"choices":[{"message":{"content":"\"O Cooper deitado no sofá azul.\""}}]}""").build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    @After fun stop() {
        server.close()
        runBlocking {
            st.setSee(Source.LOCAL)
            st.setApiProvider(Job.SEE, ""); st.setApiBase(Job.SEE, ""); st.setApiKey(Job.SEE, ""); st.setModel(Job.SEE, "")
            st.setHear(Source.LOCAL)
        }
    }

    @Test
    fun theNovelosModelOrHerApiSees() = runBlocking {
        val seeing = Seeing(st, graph.api, phone, graph.server)
        st.setSee(Source.LOCAL)
        assertEquals("visto no celular", seeing.photo(byteArrayOf(1, 2, 3)))
        assertEquals(0, server.requestCount)
        // Her API: the photo goes to it, the answer comes without quotes.
        st.setSee(Source.API)
        assertNull(seeing.photo(byteArrayOf(1, 2, 3))) // not set up yet: nothing goes anywhere
        assertEquals(0, server.requestCount)
        st.setApiProvider(Job.SEE, "custom")
        st.setApiBase(Job.SEE, server.url("/v1").toString())
        st.setApiKey(Job.SEE, "see-key")
        st.setModel(Job.SEE, "gpt-4o-mini")
        assertEquals("O Cooper deitado no sofá azul.", seeing.photo(byteArrayOf(1, 2, 3)))
        val sent = synchronized(bodies) { bodies.last() }
        assertTrue(sent, sent.contains("gpt-4o-mini") && sent.contains("image_url"))
        // A document: its name and start go, and its first page when it had no text.
        assertEquals("O Cooper deitado no sofá azul.", seeing.file(FileSeen("receita.pdf", "application/pdf", 1000, peek = ""), "a receita do Cooper", byteArrayOf(9)))
        assertTrue(synchronized(bodies) { bodies.last() }.contains("receita.pdf"))
    }

    private suspend fun herApiSees() {
        st.setSee(Source.API)
        st.setApiProvider(Job.SEE, "custom")
        st.setApiBase(Job.SEE, server.url("/v1").toString())
        st.setApiKey(Job.SEE, "see-key")
        st.setModel(Job.SEE, "gpt-4o-mini")
    }

    private fun says(content: String) = MockResponse.Builder().body(kotlinx.serialization.json.buildJsonObject {
        put("choices", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.buildJsonObject { put("message", kotlinx.serialization.json.buildJsonObject { put("content", kotlinx.serialization.json.JsonPrimitive(content)) }) }) })
    }.toString()).build()

    @Test
    fun severalPhotosAreSeenInOneLookEachItsOwn() = runBlocking {
        val seeing = Seeing(st, graph.api, phone, graph.server)
        herApiSees()
        synchronized(replies) { replies += says("""{"1": "Um recibo do Restaurante Sol.", "2": "\"Pôr do sol na praia.\""}""") }
        assertEquals(listOf("Um recibo do Restaurante Sol.", "Pôr do sol na praia."), seeing.photos(listOf(byteArrayOf(1), byteArrayOf(2))))
        assertEquals(1, server.requestCount) // one request for both
        val sent = synchronized(bodies) { bodies.last() }
        // Each photo after its number, and the answer asked as JSON.
        assertEquals(2, Regex("image_url").findAll(sent).count() / 2)
        assertTrue(sent, sent.contains(""""text":"1:"""") && sent.contains(""""text":"2:"""") && sent.contains("json_object"))
        // One photo: the usual look, no JSON.
        assertEquals(listOf("O Cooper deitado no sofá azul."), seeing.photos(listOf(byteArrayOf(3))))
        assertTrue(!synchronized(bodies) { bodies.last() }.contains("json_object"))
        // The phone's model sees them one by one, offline (nothing goes anywhere).
        st.setSee(Source.LOCAL)
        assertEquals(listOf("visto no celular", "visto no celular"), seeing.photos(listOf(byteArrayOf(1), byteArrayOf(2))))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun whatTheOneLookLeftOutIsLookedAtAlone() = runBlocking {
        val seeing = Seeing(st, graph.api, phone, graph.server)
        herApiSees()
        // The answer has only the first: the second goes alone.
        synchronized(replies) { replies += says("""{"1": "Um recibo."}""") }
        assertEquals(listOf("Um recibo.", "O Cooper deitado no sofá azul."), seeing.photos(listOf(byteArrayOf(1), byteArrayOf(2))))
        assertEquals(2, server.requestCount)
        // The one look failed: each alone, as before.
        synchronized(replies) { replies += MockResponse.Builder().code(500).build() }
        assertEquals(listOf("O Cooper deitado no sofá azul.", "O Cooper deitado no sofá azul."), seeing.photos(listOf(byteArrayOf(1), byteArrayOf(2))))
        assertEquals(5, server.requestCount)
    }

    @Test
    fun theDescriptionsAreReadByNumberOrInOrder() {
        assertEquals(listOf("a", "b"), OmniAbout.numbered("""Aqui: {"1": "a", "2": "b"}""", 2))
        assertEquals(listOf("a", null, "c"), OmniAbout.numbered("""{"1": "a", "3": "c"}""", 3))
        assertEquals(listOf("a", "b"), OmniAbout.numbered("""["a", "b", "c"]""", 2))
        assertEquals(listOf(null, "b"), OmniAbout.numbered("""{"1": "", "2": "b"}""", 2))
        assertTrue(OmniAbout.numbered("não sei", 2).isEmpty())
        assertTrue(OmniAbout.numbered("""{"1": "a"""", 2).isEmpty())
    }

    @Test
    fun whoSeesMissingIsSaidWithoutLocking() = runBlocking {
        // Her API hears and thinks; who sees is the Novelo with no model on the phone: said, not locking.
        val noModel = LocalModel.State.Missing
        val s0 = st.current().copy(hear = Source.API, hearProvider = "openai", hasHearKey = true, hearModel = "whisper-1", see = Source.LOCAL)
        val m0 = missing(s0, noModel, engine = true)
        assertEquals(listOf(Missing.SEE), m0)
        assertFalse(m0.any { it.locks })
        // The Novelo hears too: the ears are asked for once (the same model sees).
        assertEquals(listOf(Missing.EARS), missing(s0.copy(hear = Source.LOCAL), noModel, engine = true))
        // Her API sees, not set up: said; set up, nothing missing.
        assertEquals(listOf(Missing.SEE), missing(s0.copy(see = Source.API), noModel, engine = true))
        assertTrue(missing(s0.copy(see = Source.API, seeProvider = "openai", hasSeeKey = true, seeModel = "gpt-4o-mini"), noModel, engine = true).isEmpty())
    }
}
