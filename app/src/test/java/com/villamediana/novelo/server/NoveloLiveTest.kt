package com.villamediana.novelo.server

import androidx.test.core.app.ApplicationProvider
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.mind.Hearing
import com.villamediana.novelo.mind.Thinking
import com.villamediana.novelo.mind.chatBody
import com.villamediana.novelo.audio.Wav
import com.villamediana.novelo.settings.Source
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * The app against a novelo-server really running (NOVELO_E2E_URL): the registration, the
 * signatures, the asks and the plan as the two sides actually speak them.
 * Skipped when no server is given. The server is expected to answer asks with a made-up model
 * (the novelo-server repo's test helpers) — nothing here costs anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoveloLiveTest {
    @get:Rule val tmp = TemporaryFolder()
    private val url = System.getProperty("novelo.e2e").orEmpty()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val settings get() = NoveloApplication.graph.settings

    private fun server(install: String) = NoveloServer(OkHttpClient(), url, MemoryBox(), context.getSharedPreferences("live-${System.nanoTime()}", 0), { install }, "1.73.0", "com.villamediana.novelo")

    @Test
    fun thePhoneAndTheRealServerUnderstandEachOther() = runBlocking {
        assumeTrue("no server given", url.isNotBlank())
        val graph = NoveloApplication.graph
        val s = server("live-test-install-".padEnd(40, 'a'))
        // Registered by itself on the first ask; the plan is the server's word.
        val a = s.ask("think", chatBody("instruções", "comprar pão", json = true, effort = "low"))
        assertTrue(a.text, a.text.isNotBlank())
        assertTrue(a.route, a.route.startsWith("r"))
        assertEquals("plus", s.plan.value?.kind)
        assertEquals(s.plan.value, s.me())

        // Hearing and thinking through it, as the app does.
        try {
            settings.setHear(Source.NOVELO); settings.setThink(Source.NOVELO)
            val samples = FloatArray(40 * Wav.RATE) { i -> (0.4 * sin(2 * PI * 220 * i / Wav.RATE)).toFloat() }
            val wav = File(tmp.root, "talk.wav").also { Wav.write(it, samples) }
            val heard = Hearing(settings, graph.api, graph.engine, graph.localModel, tmp.newFolder("pieces"), s).transcribe(wav, onPartial = {})
            assertTrue(heard, heard.isNotBlank())
            assertTrue(Thinking(settings, graph.api, graph.engine, graph.localModel, s).answer("i", "u").isNotBlank())
        } finally {
            settings.setHear(Source.LOCAL)
        }
    }
}
