package com.villamediana.novelo.server

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.villamediana.novelo.mind.chatBody

/** The app and the Novelo's server: the phone registers itself, signs what it asks, and takes the server's word for what it has. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoveloServerTest {
    private val fake = FakeNovelo()
    private var clock = { System.currentTimeMillis() }
    private val prefs = ApplicationProvider.getApplicationContext<android.content.Context>().getSharedPreferences("server-test-${System.nanoTime()}", 0)
    private val box = MemoryBox()

    private fun server(base: String = fake.url) = NoveloServer(OkHttpClient(), base, box, prefs, { "a".repeat(40) }, "1.73.0", "com.villamediana.novelo", clock = { clock() })

    @After fun stop() = fake.close()

    @Test
    fun thePhoneRegistersItselfAndSignsWhatItAsks() = runBlocking {
        val s = server()
        assertNull(s.plan.value)
        fake.reply = { _, _ -> fake.answer("resposta", route = "r7") }
        val a = s.ask("think", chatBody("instruções", "comprar pão", json = true, effort = "low"))
        assertEquals("resposta", a.text)
        assertEquals("r7", a.route)
        assertEquals(1, fake.devices.size)
        assertEquals(listOf<String?>("a".repeat(40)), fake.installs)
        // The plan is what the server said on registering.
        assertEquals(Plan("plus", active = true), s.plan.value)
        assertFalse(s.ended)
        // Asked again: the same phone, no new registration; every signature checked.
        s.ask("think", chatBody("i", "u"), not = listOf("r7"))
        assertEquals(1, fake.devices.size)
        assertEquals("r7", fake.asks.last().not)
        assertNotEquals(fake.asks[0].request, fake.asks[1].request)
        assertTrue(fake.refused.toString(), fake.refused.isEmpty())
        // What goes is a chat request without a model.
        val sent = fake.asks.first().body
        assertTrue(sent, sent.contains("\"messages\"") && sent.contains("json_object") && !sent.contains("\"model\""))
        // Another instance (the app opened again) goes on with the same phone.
        server().ask("think", chatBody("i", "u"))
        assertEquals(1, fake.devices.size)
    }

    @Test
    fun aLongWaitAndAnErrorUnderA200() = runBlocking {
        val s = server()
        // The answer began before the outcome was known: spaces first, then the JSON.
        fake.reply = { _, _ -> fake.answer("demorou", spaces = 12) }
        assertEquals("demorou", s.ask("think", chatBody("i", "u")).text)
        fake.reply = { _, _ -> MockResponse.Builder().code(200).body("""    {"error":{"code":"busy","message":"ocupado","retry_after":20}}""").build() }
        try {
            s.ask("think", chatBody("i", "u"))
            fail("an error in the body is an error")
        } catch (e: ServerException) {
            assertEquals("busy", e.code)
            assertEquals(20, e.retryAfter)
            assertTrue(e.later)
        }
    }

    @Test
    fun theServerForgotThePhoneOrTheClockIsOff() = runBlocking {
        val s = server()
        s.ask("think", chatBody("i", "u"))
        // The server no longer knows it (its database was restored, the mode changed): it registers again by itself.
        fake.devices.clear()
        assertEquals("ok", s.ask("think", chatBody("i", "u")).text)
        assertEquals(1, fake.devices.size)
        assertEquals(2, fake.installs.size)
        // The phone's clock is an hour behind: the server says its time, and the phone signs with it from then on.
        fake.clockAhead = 3_600_000
        assertEquals("ok", s.ask("think", chatBody("i", "u")).text)
        val before = fake.asks.size
        assertEquals("ok", s.ask("think", chatBody("i", "u")).text)
        assertEquals(before + 1, fake.asks.size)
        assertTrue(fake.refused.isEmpty())
    }

    @Test
    fun manyAtOnceRegisterOnceAndARegistrationThatFailedIsDoneAgain() = runBlocking {
        val s = server()
        // Ten requests before the phone ever registered: one registration, one key, all answered.
        kotlinx.coroutines.coroutineScope {
            (1..10).map { async(kotlinx.coroutines.Dispatchers.IO) { s.ask("think", chatBody("i", "u")).text } }.forEach { assertEquals("ok", it.await()) }
        }
        assertEquals(1, fake.devices.size)
        assertEquals(1, fake.installs.size)
        assertTrue(fake.refused.toString(), fake.refused.isEmpty())
        // A registration that got as far as the new key but not as far as the server (the phone has a
        // key the server never saw, under the id of the old one): noticed, and done again by itself.
        box.create(ByteArray(8))
        assertEquals("ok", s.ask("think", chatBody("i", "u")).text)
        assertEquals(2, fake.devices.size)
        assertEquals(listOf("/v1/ask/think"), fake.refused)
        assertEquals("ok", s.ask("think", chatBody("i", "u")).text)
        assertEquals(2, fake.installs.size)
    }

    @Test
    fun theTrialIsOverWhenTheServerSaysSo() = runBlocking {
        val s = server()
        s.ask("think", chatBody("i", "u"))
        val ended = """{"kind":"ended","active":false}"""
        fake.reply = { _, _ -> fake.error(402, "plan", ""","plan":$ended""") }
        try {
            s.ask("think", chatBody("i", "u"))
            fail("402")
        } catch (e: ServerException) {
            assertEquals("plan", e.code)
        }
        assertTrue(s.ended)
        // Kept: the app opened again knows without asking.
        assertTrue(server().ended)
        // And back when the server says so.
        fake.plan = """{"kind":"trial","active":true,"trial_ends":123}"""
        assertEquals(Plan("trial", active = true, trialEnds = 123), s.me())
        assertFalse(s.ended)
    }

    @Test
    fun aConnectionThatFallsIsTheSameRequestAgain() = runBlocking {
        val s = server()
        s.ask("think", chatBody("i", "u"))
        fake.reply = { _, n -> if (n == 2) MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build() else fake.answer("depois da queda") }
        assertEquals("depois da queda", s.ask("think", chatBody("i", "u")).text)
        assertEquals(3, fake.asks.size)
        assertEquals(fake.asks[1].request, fake.asks[2].request)
    }

    @Test
    fun aBuildWithoutTheServerHasNone() = runBlocking {
        val s = server(base = "")
        assertFalse(s.available)
        try {
            s.ask("think", chatBody("i", "u"))
            fail("no server")
        } catch (e: ServerException) {
            assertEquals("not_configured", e.code)
        }
        assertEquals(0, fake.web.requestCount)
    }
}
