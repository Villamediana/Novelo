package com.villamediana.novelo.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.EntryTopic
import com.villamediana.novelo.data.Med
import com.villamediana.novelo.data.MedTaken
import com.villamediana.novelo.data.NoveloDatabase
import com.villamediana.novelo.data.Topic
import com.villamediana.novelo.meds.Doses
import com.villamediana.novelo.mind.Before
import com.villamediana.novelo.mind.Brain
import com.villamediana.novelo.mind.DayDiary
import com.villamediana.novelo.mind.FileSeen
import com.villamediana.novelo.mind.Keeper
import com.villamediana.novelo.mind.Photo
import com.villamediana.novelo.mind.Recall
import com.villamediana.novelo.remind.Repeat
import com.villamediana.novelo.ui.Filter
import com.villamediana.novelo.ui.library.sectionsFor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Before as BeforeTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalTime
import java.time.ZoneId

/**
 * What the app does, written down for the page to do the same (novelo-server: test/web-parity.test.js):
 * the dates the model writes, the repeats, the medicines' times, the diary, the context the model sees,
 * reading its answer, a plan written in, the search and Hoje — on fixed cases. Writes
 * app/build/web-parity.json (and novelo-server/test/fixtures/web-parity.json when that folder is here).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WebParityTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: NoveloDatabase
    private val sp = ZoneId.of("America/Sao_Paulo")
    private val madrid = ZoneId.of("Europe/Madrid")
    // Wednesday 2026-11-11 14:30 in São Paulo.
    private val now = java.time.LocalDateTime.of(2026, 11, 11, 14, 30).atZone(sp).toInstant().toEpochMilli()
    private val day = 86_400_000L
    private val json = Json { encodeDefaults = true }
    // Comida dos meninos: yesterday's 20:00 let go ("Pular", 1.81).
    private val tue20 = now - day + 5 * 3_600_000 + 1_800_000

    @BeforeTest fun open() {
        db = NoveloDatabase.inMemory(context)
    }

    @After fun close() = db.close()

    /** A row as the phone's database keeps it (true/false as 1/0), what the page gets. */
    private fun row(e: Entry): JsonObject = sql(json.encodeToJsonElement(e) as JsonObject, setOf("dueHasTime", "sensitive", "alarm"))
    private fun row(m: Med): JsonObject = sql(json.encodeToJsonElement(m) as JsonObject, setOf("alarm"))
    private fun sql(o: JsonObject, bools: Set<String>): JsonObject = JsonObject(o.mapValues { (k, v) -> if (k in bools && v is JsonPrimitive && !v.isString) JsonPrimitive(if (v.content == "true") 1 else 0) else v })

    private fun pair(p: Pair<Long, Boolean>?): JsonElement = p?.let { buildJsonArray { add(JsonPrimitive(it.first)); add(JsonPrimitive(it.second)) } } ?: JsonNull

    private fun entries(): List<Entry> = listOf(
        Entry(id = 1, kind = "task", title = "Fechar contrato da torre de Campinas", body = "Falta a assinatura do proprietário e a planta atualizada.", status = "doing", steps = """[{"text":"pedir planta","done":true},{"text":"assinatura"}]""", dueAt = now + 2 * day, dueHasTime = false, createdAt = now - 3 * day, updatedAt = now - day, category = "Trabalho", checkInAt = now + day),
        Entry(id = 2, kind = "task", title = "Pagar o IPVA", status = "todo", remindAt = now + 3 * 3_600_000, createdAt = now - day, updatedAt = now - day, category = "Casa"),
        Entry(id = 3, kind = "note", title = "Senha do wifi", body = "girassol", sensitive = true, createdAt = now - 5 * day, updatedAt = now - 5 * day),
        Entry(
            id = 4, kind = "note", title = "Ideias pro aniversário da Ana",
            body = "Piquenique no parque, bolo de cenoura. Pensei em fazer no sábado de manhã, antes de esquentar, e chamar os amigos da escola dela.\n\n" +
                "Se chover, a gente faz no salão do prédio, que precisa reservar com o síndico com uma semana de antes. Levar a toalha xadrez e a caixa de som.",
            summary = "Piquenique no parque, sábado de manhã\nBolo de cenoura\nSe chover: o salão do prédio (reservar com o síndico)",
            createdAt = now - 2 * day, updatedAt = now - 2 * day,
        ),
        Entry(id = 5, kind = "diary", title = "Quarta-feira, 11 de novembro", body = "Fui correr cedo no parque.", createdAt = now - 3_600_000, updatedAt = now - 3_600_000),
        Entry(id = 6, kind = "task", title = "Comida dos meninos", status = "todo", repeat = """{"every":"day","time":"10:00","times":["10:00","14:00","20:00"]}""", remindAt = now + 6 * 3_600_000, occursFrom = now - 2 * day - 4 * 3_600_000 - 1_800_000, doneTimes = "[]", createdAt = now - 3 * day, updatedAt = now - day, skippedTimes = "[$tue20]"),
        Entry(id = 7, kind = "task", title = "Mandar o relatório", status = "todo", dueAt = now - 2 * day, createdAt = now - 4 * day, updatedAt = now - 4 * day, category = "Trabalho"),
        Entry(id = 8, kind = "task", title = "Comprar ração", status = "done", doneAt = now - 3_600_000, createdAt = now - 2 * day, updatedAt = now - 3_600_000, category = "Casa"),
    )

    private val med = Med(id = 1, name = "Amoxicilina", strength = "500 mg", dose = "½ comprimido", amount = "250 mg", person = "Cooper", times = """["08:00","16:00","00:00"]""", everyHours = 8, startsAt = now - 2 * day - 6 * 3_600_000, endsAt = now + 5 * day, notes = "depois de comer", createdAt = now - 2 * day, updatedAt = now - 2 * day)

    @Test fun dumpForThePage() = runBlocking {
        val out = buildJsonObject {
            put("now", now)
            put("zone", sp.id)
            put("system", Brain.SYSTEM)
            putJsonArray("times") {
                for (z in listOf(sp, madrid)) for (t in listOf("2026-11-12", "2026-11-12T15:00", "2026-11-12 15:00:00", "+10m", "+1h30m", "-20m", "+2d", "+30s", "amanhã", "null", "", "2026-03-29T02:30", "2026-10-25T02:30", "2026-11-12T15", "2026-13-01")) {
                    add(buildJsonObject { put("text", t); put("zone", z.id); put("out", pair(Brain.time(t, z, now))); put("remind", Brain.remindTime(t, z, now)?.let { JsonPrimitive(it) } ?: JsonNull) })
                }
            }
            putJsonArray("repeats") {
                val rules = listOf(
                    Repeat.from("day", emptyList(), now, sp, listOf("10:00", "14h", "20h")),
                    Repeat.from("week", listOf(1, 3, 5), now, sp),
                    Repeat.from("month", emptyList(), java.time.LocalDateTime.of(2026, 1, 31, 9, 0).atZone(sp).toInstant().toEpochMilli(), sp),
                    Repeat.from("week", emptyList(), now, madrid, listOf("08:00")),
                )
                for (r in rules) add(buildJsonObject {
                    put("json", Repeat.write(r)!!)
                    put("zone", if (r == rules.last()) madrid.id else sp.id)
                    put("next", r!!.next(now, if (r == rules.last()) madrid else sp)?.let { JsonPrimitive(it) } ?: JsonNull)
                    putJsonArray("between") { r.between(now, now + 40 * day, if (r == rules.last()) madrid else sp).take(30).forEach { add(JsonPrimitive(it)) } }
                })
            }
            putJsonArray("doses") {
                val m2 = med.copy(id = 2, everyHours = null, times = """["08:00","20:00"]""", days = "[1,3,5]", endsAt = null)
                for (m in listOf(med, m2)) add(buildJsonObject {
                    put("med", row(m))
                    putJsonArray("slots") { Doses.slots(m, now - 3 * day, now + 3 * day, sp).forEach { add(JsonPrimitive(it)) } }
                    putJsonArray("last") { Doses.lastTimes(m, 4, now, sp).forEach { add(JsonPrimitive(it)) } }
                    put("nearest", Doses.nearest(m, now, sp)?.let { JsonPrimitive(it) } ?: JsonNull)
                })
            }
            putJsonArray("every") { add(JsonPrimitive(Doses.writeTimes(Doses.every(LocalTime.of(7, 30), 8)))); add(JsonPrimitive(Doses.writeTimes(Doses.every(LocalTime.of(22, 0), 6)))) }
            putJsonArray("amounts") {
                for ((s, d) in listOf("500 mg" to "½ comprimido", "1.000 mg" to "2 comprimidos", "0.125 mg" to "meio", "200 mg/5 ml" to "5 ml", "1,5 g" to "1 e meio", "500 mg" to "10 gotas", "40 mg" to "uma cápsula")) {
                    add(buildJsonObject { put("strength", s); put("dose", d); put("out", Doses.amount(s, d)?.let { JsonPrimitive(it) } ?: JsonNull) })
                }
            }
            putJsonArray("diary") {
                for ((a, b) in listOf("Fui correr." to "Fui correr.\n\nDepois trabalhei.", "Fui correr cedo." to "À noite vi um filme.", "" to "Tudo novo.", "O dia foi bom." to "O dia foi bom. Vi a Ana.")) {
                    add(buildJsonObject { put("body", a); put("more", b); put("out", DayDiary.merge(a, b)) })
                }
            }
            put("title", DayDiary.title(now, sp))

            // The context the model sees.
            val all = entries()
            val meds = listOf(med)
            val taken = listOf(MedTaken(1, Doses.slots(med, now - day, now, sp).last(), now - 60_000))
            val before = Before(now - 600_000, "lembra de pagar o IPVA", "Lembrete pra pagar o IPVA às 17h.", items = listOf(all[1] to "criado"))
            val photos = listOf(Photo(40, "/x", now - day, "recibo do mercado", "Total R\$ 32,00"))
            val files = listOf(FileSeen("contrato.pdf", "application/pdf", 230_000, "", "contrato de aluguel"))
            val open = all.filter { it.isOpenTask }.sortedWith(compareBy<Entry> { if (it.dueAt == null) 1 else 0 }.thenBy { it.dueAt ?: 0 }.thenByDescending { it.createdAt })
            val live = all.filterNot { it.kindEnum == com.villamediana.novelo.data.Kind.TASK && it.statusEnum == com.villamediana.novelo.data.Status.DONE }.sortedByDescending { it.createdAt }
            val hits = Recall(live, listOf(Topic(1, "Pedro", "pedro", "person")), listOf(EntryTopic(1, 1)), emptyList()).search("contrato da torre", 12, now).map { it.entry }
            put("context", buildJsonObject {
                putJsonArray("entries") { all.forEach { add(row(it)) } }
                putJsonArray("meds") { meds.forEach { add(row(it)) } }
                putJsonArray("taken") { taken.forEach { add(json.encodeToJsonElement(it)) } }
                putJsonArray("hits") { hits.forEach { add(JsonPrimitive(it.id)) } }
                put("text", Brain.context("preciso ligar pro Pedro sobre o contrato da torre", now, sp, open, hits, null, live.take(5), typed = true, diary = Brain.todaysDiary(all, now, sp), photos = photos, before = before, files = files, categories = com.villamediana.novelo.data.Categories.inUse(all), meds = meds, taken = taken))
                put("lookup", Brain.context("o que eu fiz ontem?", now, sp, open, hits, null, live.take(5), looked = "de 2026-11-10 a 2026-11-10", typed = true, diary = Brain.todaysDiary(all, now, sp), categories = com.villamediana.novelo.data.Categories.inUse(all), meds = meds, taken = taken))
            })

            // Reading the model's answers.
            putJsonArray("answers") {
                val answers = listOf(
                    """```json
{"reply":"Anotei.","create":[{"kind":"Task","title":"Ligar pro Pedro","due":"2026-11-12T09:00","steps":"achar o número","related":"1","photos":true,"alarm":false},{"kind":"lembrete","title":"x"},{"kind":"note","title":" "}],"update":[{"id":"2","steps_done":"pagar","delete":false}],"search":"contrato","show":7,"show_meds":["remédio#1","#3"],"meds":[{"id":"remédio#1","taken":true,"every_hours":"8h","days":["seg","qua","7","x"],"taken_count":"2 vezes"}]}
```""",
                    """<think>pensando</think>{"reply":"Achei.","period":{"from":"2026-11-10","to":"2026-11-10"}}""",
                    "não é JSON",
                )
                for (a in answers) {
                    val p = Brain.parse(a)
                    add(buildJsonObject {
                        put("answer", a)
                        put("plan", p?.let { pl ->
                            buildJsonObject {
                                put("reply", pl.reply)
                                putJsonArray("create") { pl.create.forEach { c -> add(buildJsonObject { put("kind", c.kind); put("title", c.title); putJsonArray("steps") { c.steps.forEach { add(JsonPrimitive(it)) } }; putJsonArray("related") { c.related.forEach { add(JsonPrimitive(it)) } }; putJsonArray("photos") { c.photos.forEach { add(JsonPrimitive(it)) } }; put("alarm", c.alarm?.let { JsonPrimitive(it) } ?: JsonNull) }) } }
                                putJsonArray("update") { pl.update.forEach { u -> add(buildJsonObject { put("id", u.id); putJsonArray("stepsDone") { u.stepsDone.forEach { add(JsonPrimitive(it)) } }; put("delete", u.delete) }) } }
                                putJsonArray("search") { pl.search.forEach { add(JsonPrimitive(it)) } }
                                put("period", pl.period?.let { buildJsonObject { put("from", it.from); put("to", it.to) } } ?: JsonNull)
                                putJsonArray("show") { pl.show.forEach { add(JsonPrimitive(it)) } }
                                putJsonArray("showMeds") { pl.showMeds.forEach { add(JsonPrimitive(it)) } }
                                putJsonArray("meds") { pl.meds.forEach { m -> add(buildJsonObject { put("id", m.id?.let { JsonPrimitive(it) } ?: JsonNull); put("taken", m.taken?.let { JsonPrimitive(it) } ?: JsonNull); put("everyHours", m.everyHours?.let { JsonPrimitive(it) } ?: JsonNull); put("days", m.days?.let { d -> JsonArray(d.map { JsonPrimitive(it) }) } ?: JsonNull); put("takenCount", m.takenCount?.let { JsonPrimitive(it) } ?: JsonNull) }) } }
                                put("lookupOnly", pl.isLookupOnly)
                            }
                        } ?: JsonNull)
                    })
                }
            }

            // A plan written in: the same rows before and after.
            putJsonArray("keeper") {
                val plans = listOf(
                    """{"reply":"ok","create":[{"kind":"task","title":"Ligar pro Pedro sobre o contrato","due":"2026-11-12T09:00","remind_at":"2026-11-12T08:45","steps":["achar o número","ligar"],"category":"trabalho","entities":[{"name":"Pedro","type":"person"},{"name":"Torre de Campinas","type":"project"}],"related":[1],"tags":["ligação"]},{"kind":"diary","title":"Dia corrido","body":"Fui correr cedo no parque.\n\nDepois fiquei o dia todo no contrato."},{"kind":"task","title":"Tomar água","remind_at":"+30m","repeat":{"every":"day","times":["09:00","15:00"]}}],"update":[{"id":1,"add_steps":["revisar cláusulas"],"steps_done":["assinatura"],"append":"O proprietário assinou."},{"id":2,"status":"done"},{"id":6,"status":"done"},{"id":4,"delete":true},{"id":7,"category":"Casa","due":"2026-11-13","repeat":{"every":"week","days":[2,4]}}],"categories":[{"name":"casa","to":"Lar"}],"meds":[{"id":1,"taken":true},{"name":"Dipirona","dose":"1 comprimido","strength":"500 mg","times":["08:00","20:00"],"end":"2026-11-14","person":"cooper"},{"id":1,"taken_at":["08:10"]}]}""",
                    """{"reply":"ok","meds":[{"name":"Amoxicilina","person":"Cooper","times":["09:00"],"every_hours":12},{"id":1,"taken_count":2},{"id":1,"untaken_at":["08:00"]},{"id":1,"end":"none"}],"update":[{"id":5,"append":"Fui correr cedo no parque.\n\nÀ noite vi um filme."},{"id":3,"title":"Senha do wifi de casa","body":"girassol-2026"}]}""",
                    // "Em tópicos": a note's points, however the model writes them (a short note too, when it says so); never a task's;
                    // news with them whole again; news without them keeps the ones there; the text written again without them, none.
                    """{"reply":"ok","create":[{"kind":"note","title":"Viagem pra Floripa","body":"Vamos de 3 a 7 de dezembro, ficando num hotel perto da praia da Joaquina. O carro a gente aluga no aeroporto, que sai mais barato que levar o nosso.\n\nPreciso ver se o hotel aceita cachorro, porque o Cooper vai junto, e comprar protetor solar e um guarda-sol novo antes.","summary":"- Datas: 3 a 7 de dezembro\n- Hotel: perto da praia da Joaquina\n**Carro:** alugar no aeroporto\n- datas: 3 a 7 de dezembro\n3. Levar: protetor solar e guarda-sol"},{"kind":"note","title":"Placa do carro","body":"ABC1D23","summary":["Placa: ABC1D23","Cor: prata"]},{"kind":"task","title":"Reservar o hotel","summary":["a","b"]}],"update":[{"id":4,"append":"A Ana prefere bolo de chocolate.","summary":[{"assunto":"Festa","pontos":["piquenique no parque","sábado de manhã"]},{"tema":"Bolo","texto":"de chocolate"},"Se chover: o salão do prédio"]},{"id":1,"append":"Mais uma coisa.","summary":["a","b"]}]}""",
                    """{"reply":"ok","update":[{"id":4,"append":"Mais uma coisa."},{"id":3,"summary":["Senha: girassol"]}]}""",
                    """{"reply":"ok","update":[{"id":4,"body":"Desisti do piquenique, vamos só jantar fora."}]}""",
                )
                for (planText in plans) {
                    db.clearAllTables()
                    // Each case from the same start: new rows numbered from the ones there, as on the page (AUTOINCREMENT remembers).
                    db.openHelper.writableDatabase.execSQL("DELETE FROM sqlite_sequence")
                    all.forEach { db.entries().insert(it) }
                    db.meds().insert(med)
                    taken.forEach { db.meds().take(it) }
                    db.graph().insertTopic(Topic(1, "Pedro", "pedro", "person"))
                    db.graph().tie(EntryTopic(1, 1))
                    val plan = Brain.parse(planText)!!
                    val result = Keeper(db, zone = { sp }, clock = { now }, alarmByDefault = { true }).apply(plan, captureId = 99, saidAt = now - 5_000)
                    add(buildJsonObject {
                        put("plan", planText)
                        putJsonArray("entries") { db.entries().all().sortedBy { it.id }.forEach { add(row(it)) } }
                        putJsonArray("meds") { db.meds().all().sortedBy { it.id }.forEach { add(row(it)) } }
                        putJsonArray("taken") { db.meds().allTaken().sortedWith(compareBy({ it.medId }, { it.at })).forEach { add(json.encodeToJsonElement(it)) } }
                        putJsonArray("topics") { db.graph().allTopics().sortedBy { it.id }.forEach { add(json.encodeToJsonElement(it)) } }
                        putJsonArray("ties") { db.graph().allTies().sortedWith(compareBy({ it.entryId }, { it.topicId })).forEach { add(json.encodeToJsonElement(it)) } }
                        putJsonArray("links") { db.graph().allLinks().forEach { add(json.encodeToJsonElement(it)) } }
                        putJsonArray("progress") { db.backup().progress().forEach { add(json.encodeToJsonElement(it)) } }
                        putJsonArray("captures") { db.backup().captures().forEach { add(JsonPrimitive(it.id)) } }
                        putJsonArray("outcomes") { result.outcomes.forEach { add(buildJsonObject { put("entryId", it.entryId); put("action", it.action) }) } }
                        putJsonArray("touched") { result.touched.forEach { add(JsonPrimitive(it)) } }
                        put("medsChanged", result.medsChanged)
                    })
                }
            }

            // The search and Hoje.
            putJsonArray("recall") {
                val r = Recall(all, listOf(Topic(1, "Pedro", "pedro", "person")), listOf(EntryTopic(1, 1), EntryTopic(2, 1)), listOf(com.villamediana.novelo.data.Link(3, 4)))
                for (q in listOf("contrato", "pedro", "aniversario ana", "wifi senha", "torres campinas", "ipva 2026")) add(buildJsonObject {
                    put("q", q)
                    putJsonArray("ids") { r.search(q, 12, now).forEach { add(JsonPrimitive(it.entry.id)) } }
                })
            }
            put("today", buildJsonObject {
                putJsonArray("sections") {
                    sectionsFor(Filter.TODAY, all, false, sp, now).forEach { (label, items) ->
                        add(buildJsonObject { put("label", label); putJsonArray("ids") { items.forEach { add(JsonPrimitive("${it.id}@${it.remindAt}")) } } })
                    }
                }
            })

            // A time let go (swiped to the left, 1.81), brought back, and one let go then done after all.
            putJsonArray("skips") {
                val e6 = all.first { it.id == 6L }
                val wed14 = now - 1_800_000
                val skipped = Repeat.skip(e6, wed14, true, now, sp)
                val back = Repeat.skip(skipped, wed14, false, now, sp)
                val done = Repeat.tick(e6, tue20, true, now, sp)
                for ((name, e) in listOf("skip" to skipped, "back" to back, "done" to done)) add(buildJsonObject {
                    put("name", name)
                    put("row", row(e))
                    putJsonArray("times") { Repeat.timesOf(e, now + day, sp).forEach { add(pair(it)) } }
                    put("current", Repeat.current(e, now, sp)?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("nextRing", Repeat.nextRing(e, now, sp)?.let { JsonPrimitive(it) } ?: JsonNull)
                })
            }
        }
        val text = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), out)
        File("build").mkdirs()
        File("build/web-parity.json").writeText(text)
        val server = File(System.getenv("NOVELO_SERVER_DIR") ?: "../../novelo-server").absoluteFile.let { if (it.isDirectory) it else File("/root/novelo-server") }
        if (File(server, "test").isDirectory) File(server, "test/fixtures").apply { mkdirs() }.resolve("web-parity.json").writeText(text)
    }
}
