package com.villamediana.novelo.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.villamediana.novelo.data.Capture
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.EntryTopic
import com.villamediana.novelo.data.Med
import com.villamediana.novelo.data.MedTaken
import com.villamediana.novelo.data.NoveloDatabase
import com.villamediana.novelo.data.Topic
import com.villamediana.novelo.server.MemoryBox
import com.villamediana.novelo.server.NoveloServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The phone and a computer on the real Novelo server (novelo-server, run with Node): the phone links
 * the computer from its code, everything goes up encrypted here and is opened by the page's own
 * locks (web/js/crypto.js) on the other side, what the computer writes comes into the rows, both
 * changing the same task merge, files go both ways, and stopping erases it all.
 * Skipped where Node or novelo-server isn't (NOVELO_SERVER_DIR, NODE).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoveloWebTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val node = System.getenv("NODE") ?: "/opt/node/bin/node"
    private val serverDir = File(System.getenv("NOVELO_SERVER_DIR") ?: "../../novelo-server").absoluteFile.let { if (it.isDirectory) it else File("/root/novelo-server") }
    private lateinit var proc: Process
    private lateinit var url: String
    private lateinit var db: NoveloDatabase
    private lateinit var engine: SyncEngine
    private lateinit var transport: ServerTransport
    private lateinit var space: PrefsSpace
    private val applied = mutableListOf<SyncEngine.Applied>()

    private val media = object : SyncMedia {
        override fun metered() = false
        override fun thumb(captureId: Long, image: File, turn: Int): File = File(tmp.root, "thumb-$captureId.jpg").apply { writeBytes(image.readBytes().take(100).toByteArray()) }
        override fun place(captureId: Long, kind: String, mime: String?, name: String?, at: Long) = File(tmp.root, "got/$captureId-$kind-${name ?: "x"}")
    }

    @Before fun start() {
        assumeTrue("Node: $node", File(node).canExecute())
        assumeTrue("novelo-server: $serverDir", File(serverDir, "server.js").isFile && File(serverDir, "test/web-peer.mjs").isFile)
        val port = ServerSocket(0).use { it.localPort }
        val data = tmp.newFolder("server")
        val env = File(data, ".env").apply { writeText("") }
        proc = ProcessBuilder(node, "--disable-warning=ExperimentalWarning", "server.js")
            .directory(serverDir)
            .redirectErrorStream(true)
            .redirectOutput(File(data, "log.txt"))
            .apply {
                environment()["PORT"] = port.toString()
                environment()["HOST"] = "127.0.0.1"
                environment()["DATA_DIR"] = File(data, "data").path
                environment()["NOVELO_ENV"] = env.path
            }
            .start()
        url = "http://127.0.0.1:$port"
        val http = OkHttpClient()
        val until = System.currentTimeMillis() + 20_000
        while (true) {
            val up = runCatching { http.newCall(Request.Builder().url("$url/health").build()).execute().use { it.isSuccessful } }.getOrDefault(false)
            if (up) break
            check(System.currentTimeMillis() < until) { "o servidor não subiu: " + File(data, "log.txt").readText() }
            Thread.sleep(100)
        }
        db = NoveloDatabase.inMemory(context)
        val server = NoveloServer(OkHttpClient(), url, MemoryBox(), context.getSharedPreferences("web-${System.nanoTime()}", 0), { "install-teste-${System.nanoTime()}" }, "1.79.0", "com.villamediana.novelo")
        transport = ServerTransport(server)
        space = PrefsSpace(context).also { it.stop() }
        engine = SyncEngine(db, transport, space, media) { applied += it }
    }

    @After fun stop() {
        if (::proc.isInitialized) {
            proc.destroy()
            proc.waitFor(5, TimeUnit.SECONDS)
        }
        if (::db.isInitialized) db.close()
    }

    private val state by lazy { File(tmp.root, "peer.json").path }

    /** One step of the computer (test/web-peer.mjs): its JSON answer. */
    private fun peer(vararg args: String): JsonObject {
        val p = ProcessBuilder(listOf(node, File(serverDir, "test/web-peer.mjs").path, state) + args).redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) { "peer ${args.toList()}: $err" }
        return Json.parseToJsonElement(out.trim().lines().last()).jsonObject
    }

    private fun records(): List<JsonObject> = peer("pull")["records"]!!.jsonArray.map { it.jsonObject }
    private fun rec(table: String, id: String) = records().firstOrNull { it["t"]!!.jsonPrimitive.content == table && it["id"]!!.jsonPrimitive.content == id }
    private fun JsonObject.r(field: String) = this["r"]!!.jsonObject[field]?.jsonPrimitive

    /** The computer shows its code; the phone reads it and links it. */
    private fun link() = runBlocking {
        val code = NoveloWeb.code(peer("link", url, "Chrome no Windows")["code"]!!.jsonPrimitive.content.replace("http://", "https://"), url.replace("http://", "https://"))
        assertNotNull(code)
        val (spaceId, master) = engine.start()
        transport.approve(code!!.linkId, SyncCrypto.wrapLink(code.webKey, code.linkId, code.secret, spaceId, master, "Pixel da Ana"), "Pixel da Ana")
        val linked = peer("wait")
        assertEquals(spaceId, linked["space"]!!.jsonPrimitive.content)
        assertEquals("Pixel da Ana", linked["owner"]!!.jsonPrimitive.content)
    }

    @Test fun everythingGoesBothWaysEncryptedAndStoppingErasesIt() = runBlocking {
        val now = 1_795_000_000_000L
        val task = db.entries().insert(Entry(kind = "task", title = "Fechar o contrato", status = "todo", steps = """[{"text":"pedir planta","done":false}]""", createdAt = now, updatedAt = now, remindAt = now + 3_600_000))
        val note = db.entries().insert(Entry(kind = "note", title = "Senha do wifi", body = "girassol", sensitive = true, createdAt = now, updatedAt = now))
        val topic = db.graph().insertTopic(Topic(name = "Pedro", norm = "pedro", type = "person"))
        db.graph().tie(EntryTopic(task, topic))
        val med = db.meds().insert(Med(name = "Amoxicilina", dose = "1 comprimido", times = """["08:00"]""", startsAt = now, createdAt = now, updatedAt = now))
        db.meds().take(MedTaken(med, now, now))
        val photoBytes = ByteArray(5_000) { (it * 7).toByte() }
        val photo = File(tmp.root, "photo.jpg").apply { writeBytes(photoBytes) }
        val shot = db.captures().insert(Capture(createdAt = now, source = "photo", imagePath = photo.path, state = Capture.DONE, description = "um recibo"))
        // A recording waiting to be heard stays here (only a sound kept on purpose goes).
        db.captures().insert(Capture(createdAt = now, source = "voice", audioPath = File(tmp.root, "voice.wav").apply { writeBytes(ByteArray(10_000)) }.path, state = Capture.RECORDED))

        link()
        engine.sync()
        engine.moveFiles()

        // The computer opens all of it with the page's locks.
        val got = records()
        val t = got.first { it["t"]!!.jsonPrimitive.content == "entries" && it["id"]!!.jsonPrimitive.content == task.toString() }
        assertEquals("Fechar o contrato", t.r("title")!!.content)
        assertEquals(0L, t.r("sensitive")!!.longOrNull)
        assertEquals("girassol", rec("entries", note.toString())!!.r("body")!!.content)
        assertNotNull(rec("entry_topics", "$task:$topic"))
        assertNotNull(rec("med_taken", "$med:$now"))
        val cap = rec("captures", shot.toString())!!
        assertNull("the phone's paths stay here", cap["r"]!!.jsonObject["imagePath"])
        val m = cap["r"]!!.jsonObject["media"]!!.jsonObject
        val image = m["image"]!!.jsonObject["id"]!!.jsonPrimitive.content
        assertNotNull(m["thumb"])
        assertEquals(Base64.getEncoder().encodeToString(photoBytes), peer("blob-get", image)["data"]!!.jsonPrimitive.content)
        assertTrue(got.filter { it["t"]!!.jsonPrimitive.content == "captures" }.all { it["r"]!!.jsonObject["media"]?.jsonObject?.get("audio") == null })

        // The computer writes: a new task (an id of its own), ties it to Pedro, renames the note, deletes nothing yet.
        val webId = (1L shl 46) + 12_345
        peer("put", """{"t":"entries","id":"$webId","r":{"id":$webId,"kind":"task","title":"Escrito no computador","body":"","status":"todo","steps":"[]","tags":"[]","dueHasTime":0,"sensitive":0,"createdAt":$now,"updatedAt":${now + 5},"remindAt":${now + 7_200_000},"doneTimes":"[]","alarm":1}}""")
        peer("put", """{"t":"entry_topics","id":"$webId:$topic","r":{"entryId":$webId,"topicId":$topic}}""")
        val noteRec = rec("entries", note.toString())!!
        val renamed = JsonObject(noteRec["r"]!!.jsonObject + ("title" to kotlinx.serialization.json.JsonPrimitive("Wi-Fi de casa")) + ("updatedAt" to kotlinx.serialization.json.JsonPrimitive(now + 10)))
        peer("put", JsonObject(noteRec + ("r" to renamed)).toString())
        applied.clear()
        engine.sync()
        val fromWeb = db.entries().get(webId)!!
        assertEquals("Escrito no computador", fromWeb.title)
        assertEquals(listOf(topic), db.graph().topicsOf(webId).map { it.id })
        assertEquals("Wi-Fi de casa", db.entries().get(note)!!.title)
        assertTrue(applied.single().entries.containsAll(listOf(webId, note)))
        // Nothing echoes back: the rows written from there aren't sent up again.
        val before = records().size
        engine.sync()
        assertEquals(before, records().size)

        // Both change the same task: the computer its title (first), the phone its status. Both stay.
        val taskRec = rec("entries", task.toString())!!
        val retitled = JsonObject(taskRec["r"]!!.jsonObject + ("title" to kotlinx.serialization.json.JsonPrimitive("Fechar o contrato de Campinas")) + ("updatedAt" to kotlinx.serialization.json.JsonPrimitive(now + 20)))
        peer("put", JsonObject(taskRec + ("r" to retitled)).toString())
        db.entries().update(db.entries().get(task)!!.copy(status = "done", doneAt = now + 30, updatedAt = now + 30))
        engine.sync()
        val merged = db.entries().get(task)!!
        assertEquals("Fechar o contrato de Campinas", merged.title)
        assertEquals("done", merged.status)
        val there = rec("entries", task.toString())!!
        assertEquals("Fechar o contrato de Campinas", there.r("title")!!.content)
        assertEquals("done", there.r("status")!!.content)

        // A photo sent from the computer comes down to the phone.
        val webPhoto = ByteArray(3_000) { (it % 251).toByte() }
        val blob = peer("blob-put", Base64.getEncoder().encodeToString(webPhoto))["id"]!!.jsonPrimitive.content
        val capId = (1L shl 46) + 99
        peer("put", """{"t":"captures","id":"$capId","r":{"id":$capId,"createdAt":$now,"source":"photo","state":"done","outcomes":"[]","shown":"[]","heardPieces":0,"tries":0,"shownPhotos":"[]","turn":0,"sound":0,"fileSize":0,"media":{"image":{"id":"$blob","size":${webPhoto.size + 28},"mime":"image/jpeg"}}}}""")
        engine.sync()
        engine.moveFiles()
        val there2 = db.captures().get(capId)!!
        assertTrue(java.util.Arrays.equals(webPhoto, File(there2.imagePath!!).readBytes()))

        // A record that isn't the row it names (its name one row, its id another) is left alone:
        // it would land on the task written above. Nor do a file kind or a file id that aren't ones come down.
        peer("put", """{"t":"entries","id":"${webId + 1}","r":{"id":${webId + 2},"kind":"task","title":"Trocado","status":"todo","createdAt":$now,"updatedAt":${now + 99}}}""")
        val capBad = (1L shl 46) + 100
        peer("put", """{"t":"captures","id":"$capBad","r":{"id":$capBad,"createdAt":$now,"source":"file","state":"done","fileName":"../../shared_prefs/x.xml","media":{"../x":{"id":"$blob","size":10},"file":{"id":"../../etc","size":10}}}}""")
        engine.sync()
        engine.moveFiles()
        assertEquals("Escrito no computador", db.entries().get(webId)!!.title)
        assertNull(db.entries().get(webId + 1))
        assertNull(db.entries().get(webId + 2))
        assertTrue(db.sync().files(capBad).isEmpty())

        // A dose unticked and ticked again — the same row, made again after its deletion went up — stays
        // (it isn't taken for "deleted there" and deleted again here).
        db.meds().untake(med, now)
        engine.sync()
        assertNull(rec("med_taken", "$med:$now"))
        db.meds().take(MedTaken(med, now, now + 1))
        engine.sync()
        engine.sync()
        assertNotNull(rec("med_taken", "$med:$now"))
        assertEquals(1, db.meds().takenBetween(now, now).size)
        // The same from the computer's side: it unticks, the phone too; it ticks again, the phone too.
        peer("delete", "med_taken", "$med:$now")
        engine.sync()
        assertEquals(0, db.meds().takenBetween(now, now).size)
        db.meds().take(MedTaken(med, now, now + 2))
        engine.sync()
        assertEquals(1, db.meds().takenBetween(now, now).size)
        assertNotNull(rec("med_taken", "$med:$now"))

        // The computer deletes the note; the phone too.
        peer("delete", "entries", note.toString())
        engine.sync()
        assertNull(db.entries().get(note))
        // The phone deletes the task it made: the computer sees it gone, with its ties.
        db.entries().delete(task)
        engine.sync()
        assertNull(rec("entries", task.toString()))
        assertNull(rec("entry_topics", "$task:$topic"))

        // Stopping erases the server's copy: the computer is out.
        engine.stop()
        assertEquals(401, peer("me")["status"]!!.jsonPrimitive.longOrNull!!.toInt())
        assertTrue(!space.on)
    }

    @Test fun aPhoneThatLostItsKeyStartsTheSpaceOverFromWhatItHas() = runBlocking {
        val now = 1_795_000_000_000L
        db.entries().insert(Entry(kind = "note", title = "Antes", createdAt = now, updatedAt = now))
        link()
        engine.sync()
        // The key is lost (reinstalled): a new one, the same install — the space starts over from here.
        space.stop()
        val fresh = SyncEngine(db, transport, space, media)
        fresh.start()
        fresh.sync()
        // The old computer is out; the space has what the phone has.
        assertEquals(401, peer("me")["status"]!!.jsonPrimitive.longOrNull!!.toInt())
    }
}
