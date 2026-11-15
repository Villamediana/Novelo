package com.villamediana.novelo.backup

import androidx.test.core.app.ApplicationProvider
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.R
import com.villamediana.novelo.data.Capture
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.NoveloDatabase
import com.villamediana.novelo.mind.ReminderSync
import com.villamediana.novelo.server.FakeNovelo
import com.villamediana.novelo.server.MemoryBox
import com.villamediana.novelo.server.NoveloServer
import com.villamediana.novelo.settings.t
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.io.IOException

/** The backup in the cloud: encrypted on the phone, sent in pieces once, brought back with the secret only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudBackupTest {
    @get:Rule val tmp = TemporaryFolder()
    private val fake = FakeNovelo()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val settings get() = NoveloApplication.graph.settings
    private val now = 1_794_700_000_000L

    /** Her Google account's safe, as a phone has it. */
    private class Safe(var seed: ByteArray? = null) : SeedKeeper {
        override suspend fun read(): ByteArray? = seed
        override suspend fun write(seed: ByteArray): Boolean { this.seed = seed; return true }
        override suspend fun clear() { seed = null }
    }

    /** One phone: its database, its files, its key for the server. */
    private inner class Phone(name: String, val safe: Safe = Safe()) {
        val db: NoveloDatabase = NoveloDatabase.inMemory(context)
        val files: File = tmp.newFolder("$name-captures")
        val server = NoveloServer(OkHttpClient(), fake.url, MemoryBox(), context.getSharedPreferences("cloud-$name-${System.nanoTime()}", 0), { name.padEnd(40, 'x') }, "1.73.0", "com.villamediana.novelo")
        val core = Backup(db, files, File(tmp.root, "$name-state.json"), object : ReminderSync { override suspend fun sync(entryIds: List<Long>) = Unit }, "1.73.0", clock = { now })
        val cloud = CloudBackup(server, core, settings, safe, File(tmp.root, "$name-cloud.json"), tmp.newFolder("$name-work"), "1.73.0", clock = { now })
    }

    @Before fun clean() {
        runBlocking {
            settings.setCloud(false)
            settings.setCloudSeed(null)
        }
    }

    @After fun stop() {
        fake.close()
        runBlocking {
            settings.setCloud(false)
            settings.setCloudSeed(null)
        }
    }

    private val big = ByteArray(CloudCrypto.CHUNK + 300_000) { (it % 251).toByte() }

    private suspend fun aDay(p: Phone) {
        p.db.entries().insert(Entry(kind = "diary", title = "Quarta-feira", body = "Fui à praia com a Alma.", createdAt = now, updatedAt = now))
        p.db.entries().insert(Entry(kind = "note", title = "Senha do wi-fi", body = "abc123-segredo", sensitive = true, createdAt = now, updatedAt = now))
        val photo = File(p.files, "photo-1.jpg").apply { writeBytes(ByteArray(5_000) { (it * 3).toByte() }) }
        p.db.captures().insert(Capture(createdAt = now, source = "photo", imagePath = photo.path, transcript = "PRAIA", description = "Alma na praia", state = Capture.DONE))
        // One bigger than a piece: it goes in two.
        val video = File(p.files, "photo-2.jpg").apply { writeBytes(big) }
        p.db.captures().insert(Capture(createdAt = now, source = "photo", imagePath = video.path, transcript = "", state = Capture.DONE))
    }

    @Test
    fun itGoesEncryptedOnceAndComesBackOnAnotherPhone() = runBlocking {
        val a = Phone("a")
        aDay(a)
        // The server says what this phone has; the cloud is offered because of it.
        assertFalse(a.cloud.offered)
        a.server.me()
        assertTrue(a.cloud.offered)
        assertNull(a.cloud.code())
        assertTrue("kept in her Google safe", a.cloud.turnOn())
        val code = a.cloud.code()!!
        assertTrue(a.cloud.save())
        assertFalse(settings.current().cloudFailed)
        assertEquals(now, settings.current().cloudAt)

        // What the server has: pieces with names that say nothing, none readable.
        val vault = fake.vaults.values.single()
        assertEquals(1, vault.manifests.size)
        assertEquals(4, vault.blobs.size) // backup.json, the photo, the big one in two
        val everything = vault.blobs.values.fold(vault.manifests[0].first) { acc, b -> acc + b }.toString(Charsets.ISO_8859_1)
        for (word in listOf("praia", "abc123", "Alma", "wi-fi", "photo-1")) assertFalse(word, everything.contains(word))
        assertTrue(vault.blobs.values.all { it.size <= CloudCrypto.CHUNK + 28 })

        // Nothing changed: nothing goes. Something new: only what is new goes (the photos aren't sent again).
        val sent = fake.puts.size
        assertTrue(a.cloud.save())
        assertEquals(sent, fake.puts.size)
        assertEquals(1, vault.manifests.size)
        a.db.entries().insert(Entry(kind = "task", title = "Pagar o IPVA", status = "todo", createdAt = now, updatedAt = now))
        assertTrue(a.cloud.save())
        assertEquals(sent + 1, fake.puts.size)
        assertEquals(2, vault.manifests.size)

        // Another phone, nothing in it. Her Google safe brought the secret: the backup is found and comes back.
        settings.setCloud(false)
        settings.setCloudSeed(null)
        val b = Phone("b", Safe(a.safe.seed))
        val seed = b.cloud.known()!!
        val found = b.cloud.find(seed)!!
        assertEquals(3, found.entries)
        assertEquals(2, found.photos)
        val restored = b.cloud.restore(seed)
        assertEquals(3, restored.entries)
        assertEquals(2, restored.photos)
        assertEquals(listOf("Quarta-feira", "Senha do wi-fi", "Pagar o IPVA"), b.db.backup().entries().map { it.title })
        assertEquals("abc123-segredo", b.db.backup().entries().first { it.sensitive }.body)
        val photos = b.db.backup().captures().mapNotNull { it.imagePath }.map { File(it).readBytes() }
        assertTrue(photos.any { it.contentEquals(big) })
        assertTrue(photos.any { it.size == 5_000 })
        // The cloud backup is on there from then, with the same secret; its first backup finds everything already there.
        assertTrue(settings.current().cloud)
        assertArrayEquals(seed, settings.cloudSeed())
        val before = fake.puts.size
        assertTrue(b.cloud.save())
        assertTrue("only backup.json at most", fake.puts.size - before <= 1)

        // A third phone, no Google safe: the recovery code is the way back.
        settings.setCloud(false)
        settings.setCloudSeed(null)
        val c = Phone("c")
        assertNull(c.cloud.known())
        val typed = CloudCrypto.seed(code.lowercase().replace("-", " "))!!
        assertArrayEquals(seed, typed)
        assertEquals(3, c.cloud.restore(typed).entries)
        // A code that is well formed but hers of nothing: there is no backup there.
        assertNull(c.cloud.find(CloudCrypto.newSeed()))

        // Off, and erased: nothing left on the server, the secret forgotten everywhere on this phone.
        c.cloud.turnOff(erase = true)
        assertTrue(fake.vaults.isEmpty())
        assertFalse(settings.current().cloud)
        assertNull(settings.cloudSeed())
        assertNull(c.safe.seed)
        assertFalse(c.cloud.save())
    }

    @Test
    fun aPieceChangedOnTheWayIsNoticedAndNothingIsReplaced() = runBlocking {
        val a = Phone("a")
        aDay(a)
        a.server.me()
        a.cloud.turnOn()
        assertTrue(a.cloud.save())
        val seed = settings.cloudSeed()!!
        val vault = fake.vaults.values.single()
        val victim = vault.blobs.keys.first()
        vault.blobs[victim] = vault.blobs.getValue(victim).copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        val b = Phone("b")
        b.db.entries().insert(Entry(kind = "note", title = "O que já estava aqui", createdAt = now, updatedAt = now))
        try {
            b.cloud.restore(seed)
            fail("a changed piece came back")
        } catch (e: IOException) {
            assertEquals(t(R.string.backup_broken), e.message)
        }
        assertEquals(listOf("O que já estava aqui"), b.db.backup().entries().map { it.title })
        // A piece swapped for another (both genuine) is noticed too.
        val (x, y) = vault.blobs.keys.filter { it != victim }.take(2)
        vault.blobs[x] = vault.blobs.getValue(y)
        vault.blobs.remove(victim)
        try {
            b.cloud.restore(seed)
            fail("a swapped piece came back")
        } catch (e: Exception) {
            assertNotNull(e)
        }
        assertEquals(1, b.db.backup().entries().size)
    }

    @Test
    fun withoutNoveloPlusNothingGoesUpButWhatIsThereStillComesBack() = runBlocking {
        val a = Phone("a")
        aDay(a)
        a.server.me()
        a.cloud.turnOn()
        assertTrue(a.cloud.save())
        val seed = settings.cloudSeed()!!
        // The server says this phone is no longer Novelo+.
        fake.plan = """{"kind":"ended","active":false,"backup":false}"""
        a.server.me()
        assertFalse(a.cloud.offered)
        a.db.entries().insert(Entry(kind = "note", title = "Depois do fim", createdAt = now, updatedAt = now))
        assertFalse(a.cloud.save())
        assertTrue(settings.current().cloudFailed)
        assertEquals(t(R.string.cloud_not_plus), settings.current().cloudNote)
        // What she kept is still hers.
        settings.setCloud(false)
        settings.setCloudSeed(null)
        val b = Phone("b")
        assertEquals(2, b.cloud.restore(seed).entries)
    }

    @Test
    fun itIsNeverOffWithTheBackupStillUpThere() = runBlocking {
        val a = Phone("a")
        aDay(a)
        a.server.me()
        a.cloud.turnOn()
        assertTrue(a.cloud.save())
        // Off without erasing: nothing more goes; the backup and the secret stay.
        a.cloud.turnOff(erase = false)
        assertFalse(settings.current().cloud)
        assertEquals(1, fake.vaults.size)
        assertNotNull(settings.cloudSeed())
        a.cloud.turnOn()
        // "Off and erase" with no network: it can't erase, so it stays on (and she is told).
        fake.close()
        try {
            a.cloud.turnOff(erase = true)
            fail("it turned off without erasing")
        } catch (e: IOException) {
            assertTrue(settings.current().cloud)
            assertNotNull(settings.cloudSeed())
            assertNotNull(a.safe.seed)
        }
    }

    @Test
    fun noNetworkIsSaidAndItGoesLater() = runBlocking {
        val a = Phone("a")
        aDay(a)
        a.server.me()
        a.cloud.turnOn()
        fake.close()
        assertFalse(a.cloud.save())
        assertTrue(settings.current().cloudFailed)
        assertEquals(t(R.string.cloud_offline), settings.current().cloudNote)
    }
}
