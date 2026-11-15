package com.villamediana.novelo.server

import androidx.test.core.app.ApplicationProvider
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.backup.Backup
import com.villamediana.novelo.backup.CloudBackup
import com.villamediana.novelo.backup.CloudCrypto
import com.villamediana.novelo.backup.SeedKeeper
import com.villamediana.novelo.data.Capture
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.NoveloDatabase
import com.villamediana.novelo.mind.Hearing
import com.villamediana.novelo.mind.ReminderSync
import com.villamediana.novelo.mind.Thinking
import com.villamediana.novelo.mind.chatBody
import com.villamediana.novelo.audio.Wav
import com.villamediana.novelo.settings.Source
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * signatures, the asks, the plan and the cloud backup as the two sides actually speak them.
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

    private class Safe(var seed: ByteArray? = null) : SeedKeeper {
        override suspend fun read(): ByteArray? = seed
        override suspend fun write(seed: ByteArray): Boolean { this.seed = seed; return true }
        override suspend fun clear() { seed = null }
    }

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

        // The cloud backup: up, and back on another phone with the secret only.
        settings.setCloud(false); settings.setCloudSeed(null)
        val now = System.currentTimeMillis()
        fun phone(name: String, server: NoveloServer, safe: Safe): Triple<NoveloDatabase, CloudBackup, File> {
            val db = NoveloDatabase.inMemory(context)
            val files = tmp.newFolder("$name-files")
            val core = Backup(db, files, File(tmp.root, "$name-state.json"), object : ReminderSync { override suspend fun sync(entryIds: List<Long>) = Unit }, "1.73.0")
            return Triple(db, CloudBackup(server, core, settings, safe, File(tmp.root, "$name-cloud.json"), tmp.newFolder("$name-work"), "1.73.0"), files)
        }
        val safe = Safe()
        val (db, cloud, files) = phone("a", s, safe)
        db.entries().insert(Entry(kind = "diary", title = "Hoje", body = "Fui à praia.", createdAt = now, updatedAt = now))
        val big = ByteArray(CloudCrypto.CHUNK + 123_456) { (it % 249).toByte() }
        val photo = File(files, "photo-1.jpg").apply { writeBytes(big) }
        db.captures().insert(Capture(createdAt = now, source = "photo", imagePath = photo.path, transcript = "", state = Capture.DONE))
        assertTrue(cloud.offered)
        try {
            cloud.turnOn()
            assertTrue(settings.current().cloudNote, cloud.save())
            // Again with nothing changed, then with something new.
            assertTrue(cloud.save())
            db.entries().insert(Entry(kind = "note", title = "Mais uma", createdAt = now, updatedAt = now))
            assertTrue(settings.current().cloudNote, cloud.save())
            val seed = settings.cloudSeed()!!

            settings.setCloud(false); settings.setCloudSeed(null)
            val other = server("live-test-install-".padEnd(40, 'b'))
            val (db2, cloud2, _) = phone("b", other, Safe(seed))
            assertEquals(2, cloud2.find(cloud2.known()!!)!!.entries)
            val back = cloud2.restore(seed)
            assertEquals(2, back.entries)
            assertEquals(1, back.photos)
            assertEquals(listOf("Hoje", "Mais uma"), db2.backup().entries().map { it.title })
            assertArrayEquals(big, File(db2.backup().captures().first { it.imagePath != null }.imagePath!!).readBytes())
            // A secret that is someone else's opens nothing; erasing takes it all.
            assertNull(cloud2.find(CloudCrypto.newSeed()))
            cloud2.turnOff(erase = true)
            assertNull(cloud2.find(seed))
        } finally {
            settings.setCloud(false); settings.setCloudSeed(null)
        }
    }
}
