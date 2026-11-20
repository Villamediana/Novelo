package com.villamediana.novelo.remind

import androidx.test.core.app.ApplicationProvider
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.NoveloDatabase
import com.villamediana.novelo.data.Status
import com.villamediana.novelo.data.Step
import com.villamediana.novelo.mind.Brain
import com.villamediana.novelo.mind.Keeper
import com.villamediana.novelo.mind.ReminderSync
import com.villamediana.novelo.mind.Steps
import com.villamediana.novelo.settings.Lang
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/** Reminders that come back: every day, on days of the week, every month — and a task that repeats, done. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepeatTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private lateinit var db: NoveloDatabase

    @Before fun open() {
        Lang.init(ApplicationProvider.getApplicationContext())
        db = NoveloDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After fun close() { db.close() }

    // Wednesday, 2026-11-11.
    private val wed10 = at(2026, 11, 11, 10)

    @Test
    fun theNextTime() {
        assertEquals(at(2026, 11, 11, 21), Repeat(Repeat.DAY, time = "21:00").next(wed10, zone))
        assertEquals(at(2026, 11, 12, 8), Repeat(Repeat.DAY, time = "08:00").next(wed10, zone))
        // Mondays and Wednesdays at 8: today's 8 is gone, next is Monday.
        assertEquals(at(2026, 11, 16, 8), Repeat(Repeat.WEEK, listOf(1, 3), "08:00").next(wed10, zone))
        assertEquals(at(2026, 11, 11, 18), Repeat(Repeat.WEEK, listOf(1, 3), "18:00").next(wed10, zone))
        // The 31st of every month: a short month takes its last day.
        assertEquals(at(2026, 11, 30, 9), Repeat(Repeat.MONTH, time = "09:00", day = 31).next(wed10, zone))
        assertEquals(at(2026, 12, 10, 9), Repeat(Repeat.MONTH, time = "09:00", day = 10).next(wed10, zone))
        assertNull(Repeat.read("""{"every":"week","days":[]}"""))
    }

    /** More than once a day ("comida dos meninos às 10, 14 e 20"): each of its times, in words, done one after the other. */
    @Test
    fun severalTimesADay() = runBlocking {
        val three = Repeat(Repeat.DAY, time = "10:00", times = listOf("10:00", "14:00", "20:00"))
        assertEquals(at(2026, 11, 11, 14), three.next(wed10, zone))
        assertEquals(at(2026, 11, 11, 20), three.next(at(2026, 11, 11, 14), zone))
        assertEquals(at(2026, 11, 12, 10), three.next(at(2026, 11, 11, 20), zone))
        assertEquals("todo dia às 10:00, 14:00 e 20:00", three.describe())
        assertEquals("seg e qua às 08:00 e 18:00", Repeat(Repeat.WEEK, listOf(1, 3), "08:00", times = listOf("08:00", "18:00")).describe())
        // Made from what is said ("14h" too), and kept as it was before when it is one time.
        assertEquals(listOf("10:00", "14:00", "20:00"), Repeat.from("day", emptyList(), wed10, zone, listOf("20:00", "10h", "14h"))!!.times)
        assertEquals(emptyList<String>(), Repeat.from("day", emptyList(), wed10, zone)!!.times)
        // The model: the task comes back at each time; done, it goes to the next one.
        val keeper = Keeper(db, zone = { zone }, clock = { at(2026, 11, 11, 9, 7) })
        val r = keeper.apply(Brain.parse("""{"reply":"ok","create":[{"kind":"task","title":"Comida meninos","remind_at":"2026-11-11T10:00","repeat":{"every":"day","times":["10:00","14:00","20:00"]}}]}""")!!, null)
        val id = r.outcomes.single().entryId
        assertEquals(at(2026, 11, 11, 10), db.entries().get(id)!!.remindAt)
        assertEquals("todo dia às 10:00, 14:00 e 20:00", Repeat.read(db.entries().get(id)!!.repeat)!!.describe())
        val lib = com.villamediana.novelo.data.Library(db, object : ReminderSync { override suspend fun sync(entryIds: List<Long>) {} }, clock = { at(2026, 11, 11, 10, 30) })
        lib.setStatus(id, Status.DONE, zone)
        assertEquals(at(2026, 11, 11, 14), db.entries().get(id)!!.remindAt)
        assertEquals(Status.TODO, db.entries().get(id)!!.statusEnum)
    }

    /**
     * Each time on its own (1.70): ringing isn't doing — the time that rang stays to tick; ticked from the
     * app (no time said) it is the last one come; Hoje ticks each by its own; one done ahead doesn't ring.
     */
    @Test
    fun eachTimeIsTickedOnItsOwn() = runBlocking {
        val three = Repeat(Repeat.DAY, time = "10:00", times = listOf("10:00", "14:00", "20:00"))
        var clock = at(2026, 11, 14, 9, 7)
        val lib = com.villamediana.novelo.data.Library(db, object : ReminderSync { override suspend fun sync(entryIds: List<Long>) {} }, clock = { clock })
        val id = db.entries().insert(
            Entry(kind = "task", title = "Comida meninos", status = "todo", remindAt = at(2026, 11, 14, 10), repeat = Repeat.write(three), occursFrom = at(2026, 11, 14, 10), createdAt = clock, updatedAt = clock),
        )
        fun e() = runBlocking { db.entries().get(id)!! }
        // 10:00 and 14:00 rang (the ring goes on), none ticked: both still to do.
        db.entries().update(e().copy(remindAt = Repeat.nextRing(e(), at(2026, 11, 14, 14), zone), rungAt = at(2026, 11, 14, 14)))
        assertEquals(at(2026, 11, 14, 20), e().remindAt)
        clock = at(2026, 11, 14, 14, 20)
        assertEquals(listOf(false, false), Repeat.timesOf(e(), clock + 1, zone).map { it.second })
        // "Feito" with no time: the last one come (14:00); the 10:00 waits for its own tick.
        assertEquals(at(2026, 11, 14, 14), Repeat.current(e(), clock, zone))
        lib.setStatus(id, Status.DONE, zone)
        assertEquals(setOf(at(2026, 11, 14, 14)), Repeat.doneOf(e()))
        assertEquals(Status.TODO, e().statusEnum)
        assertEquals(at(2026, 11, 14, 10), Repeat.come(e().copy(doneTimes = "[]"), at(2026, 11, 14, 11), zone))
        lib.toggleTime(id, at(2026, 11, 14, 10), zone)
        assertEquals(setOf(at(2026, 11, 14, 10), at(2026, 11, 14, 14)), Repeat.doneOf(e()))
        // Tapped again: not done any more.
        lib.toggleTime(id, at(2026, 11, 14, 10), zone)
        assertEquals(setOf(at(2026, 11, 14, 14)), Repeat.doneOf(e()))
        lib.toggleTime(id, at(2026, 11, 14, 10), zone)
        // 19:50, the 20:00 done ahead: it doesn't ring; the next ring is tomorrow's 10:00.
        clock = at(2026, 11, 14, 19, 50)
        lib.setStatus(id, Status.DONE, zone)
        assertEquals(at(2026, 11, 15, 10), e().remindAt)
        assertEquals(at(2026, 11, 15, 10), Repeat.nextRing(e(), clock, zone))
        // Two days later, ticking: the ones done long ago leave the list; it counts from the first not done.
        clock = at(2026, 11, 16, 10, 5)
        lib.toggleTime(id, at(2026, 11, 16, 10), zone)
        assertEquals(at(2026, 11, 15, 10), e().occursFrom)
        assertEquals(listOf(at(2026, 11, 15, 10), at(2026, 11, 15, 14), at(2026, 11, 15, 20)), Repeat.timesOf(e(), at(2026, 11, 16, 0), zone).filter { !it.second }.map { it.first })
    }

    /** Before 1.70 a task that repeats had no times kept: on opening, today's count from today, today's ticks on the nearest time. */
    @Test
    fun theOnesFromBeforeAreFixed() = runBlocking {
        val three = Repeat(Repeat.DAY, time = "10:00", times = listOf("10:00", "14:00", "20:00"))
        val now = at(2026, 11, 14, 20, 22)
        val lib = com.villamediana.novelo.data.Library(db, object : ReminderSync { override suspend fun sync(entryIds: List<Long>) {} }, clock = { now })
        val id = db.entries().insert(
            Entry(kind = "task", title = "Comida meninos", status = "todo", remindAt = at(2026, 11, 15, 10), repeat = Repeat.write(three), createdAt = at(2026, 11, 14, 9, 7), updatedAt = now),
        )
        // Ticked at 10:12 and 14:31 (the old way: the reminder went on).
        db.progress().insert(com.villamediana.novelo.data.Progress(entryId = id, at = at(2026, 11, 14, 10, 12), text = "", status = "done"))
        db.progress().insert(com.villamediana.novelo.data.Progress(entryId = id, at = at(2026, 11, 14, 14, 31), text = "", status = "done"))
        db.progress().insert(com.villamediana.novelo.data.Progress(entryId = id, at = at(2026, 11, 13, 20, 1), text = "", status = "done"))
        lib.fixRepeats(zone)
        val e = db.entries().get(id)!!
        assertEquals(at(2026, 11, 14, 10), e.occursFrom)
        assertEquals(setOf(at(2026, 11, 14, 10), at(2026, 11, 14, 14)), Repeat.doneOf(e))
        assertEquals(at(2026, 11, 14, 20), Repeat.come(e, now, zone))
        assertEquals(at(2026, 11, 15, 10), e.remindAt)
    }

    @Test
    fun saidInWords() {
        assertEquals("todo dia às 21:00", Repeat(Repeat.DAY, time = "21:00").describe())
        assertEquals("seg e qua às 08:00", Repeat(Repeat.WEEK, listOf(3, 1), "08:00").describe())
        assertEquals("todo mês no dia 10 às 09:00", Repeat(Repeat.MONTH, time = "09:00", day = 10).describe())
    }

    @Test
    fun aTaskThatRepeatsDoneComesBackForTheNextTime() {
        val rule = Repeat(Repeat.WEEK, listOf(1, 3), "08:00")
        val e = Entry(
            id = 1, kind = "task", title = "Tomar o remédio", status = "todo", remindAt = at(2026, 11, 11, 8), dueAt = at(2026, 11, 11, 0),
            steps = Steps.write(listOf(Step("água", done = true))), repeat = Repeat.write(rule), createdAt = wed10, updatedAt = wed10,
        )
        val after = Repeat.done(e, wed10, zone)
        assertEquals(Status.TODO, after.statusEnum)
        assertEquals(at(2026, 11, 16, 8), after.remindAt)
        assertEquals(at(2026, 11, 16, 0), after.dueAt)
        assertEquals(false, Steps.read(after.steps).single().done)
        // One that doesn't repeat is just done.
        val once = Repeat.done(e.copy(repeat = null), wed10, zone)
        assertEquals(Status.DONE, once.statusEnum)
        // Its hour kept, quiet (it doesn't ring).
        assertEquals(once.remindAt, once.rungAt)
    }

    @Test
    fun theModelSetsItAndStopsIt() = runBlocking {
        val keeper = Keeper(db, zone = { zone }, clock = { wed10 })
        val r = keeper.apply(Brain.parse("""{"create":[{"kind":"task","title":"Academia","remind_at":"2026-11-11T18:00","repeat":{"every":"week","days":[1,3,5]}}]}""")!!, null)
        val id = r.outcomes.single().entryId
        val e = db.entries().get(id)!!
        assertEquals(Repeat(Repeat.WEEK, listOf(1, 3, 5), "18:00"), Repeat.read(e.repeat))
        assertEquals(at(2026, 11, 11, 18), e.remindAt)
        // Done (said): it comes back on Friday.
        val said = keeper.apply(Brain.parse("""{"update":[{"id":$id,"status":"done"}]}""")!!, null)
        // The main screen says "Feito" (not "Atualizado"), though it is open again for Friday.
        assertEquals(com.villamediana.novelo.data.Outcome.DONE, said.outcomes.single().action)
        val done = db.entries().get(id)!!
        assertEquals(Status.TODO, done.statusEnum)
        assertEquals(at(2026, 11, 13, 18), done.remindAt)
        assertEquals("done", db.progress().latest(id, 1).single().status)
        // Not any more.
        keeper.apply(Brain.parse("""{"update":[{"id":$id,"repeat":{"every":"none"}}]}""")!!, null)
        assertNull(db.entries().get(id)!!.repeat)
        // Every day with no hour said: 9:00, the next one.
        val daily = keeper.apply(Brain.parse("""{"create":[{"kind":"task","title":"Regar as plantas","repeat":{"every":"day"}}]}""")!!, null).outcomes.single().entryId
        assertEquals(at(2026, 11, 12, 9), db.entries().get(daily)!!.remindAt)
    }

    @Test
    fun tickedInTheAppItComesBackToo() = runBlocking {
        val lib = com.villamediana.novelo.data.Library(db, object : ReminderSync { override suspend fun sync(entryIds: List<Long>) {} }, clock = { wed10 })
        val id = db.entries().insert(Entry(kind = "task", title = "Regar", status = "todo", remindAt = at(2026, 11, 11, 9), repeat = Repeat.write(Repeat(Repeat.DAY, time = "09:00")), createdAt = wed10, updatedAt = wed10))
        lib.setStatus(id, Status.DONE, zone)
        val e = db.entries().get(id)!!
        assertEquals(Status.TODO, e.statusEnum)
        assertEquals(at(2026, 11, 12, 9), e.remindAt)
    }

    /**
     * "Pular" (swiped to the left, 1.81): one time of a task that repeats is let go — not done, not late, never rung —
     * and comes back with a tap on its page; done after all, it is just done.
     */
    @Test
    fun aTimeLetGoIsAsIfItWerentThere() = runBlocking {
        val three = Repeat(Repeat.DAY, time = "10:00", times = listOf("10:00", "14:00", "20:00"))
        var clock = at(2026, 11, 14, 13, 30)
        val rang = mutableListOf<Long>()
        val lib = com.villamediana.novelo.data.Library(db, object : ReminderSync { override suspend fun sync(entryIds: List<Long>) { rang += entryIds } }, clock = { clock })
        val id = db.entries().insert(
            Entry(kind = "task", title = "Academia", status = "todo", remindAt = at(2026, 11, 14, 14), repeat = Repeat.write(three), occursFrom = at(2026, 11, 13, 10), createdAt = clock, updatedAt = clock),
        )
        fun e() = runBlocking { db.entries().get(id)!! }
        // Yesterday's 20:00 forgotten (late) and today's 14:00 still to come: both let go.
        lib.skipTime(id, at(2026, 11, 13, 20), zone = zone)
        lib.skipTime(id, at(2026, 11, 14, 14), zone = zone)
        assertEquals(setOf(at(2026, 11, 13, 20), at(2026, 11, 14, 14)), Repeat.skippedOf(e()))
        assertEquals(listOf(id, id), rang)
        val times = Repeat.timesOf(e(), at(2026, 11, 15, 0), zone).map { it.first }
        assertEquals(false, at(2026, 11, 13, 20) in times)
        assertEquals(false, at(2026, 11, 14, 14) in times)
        // It doesn't ring at 14:00 any more: the next is 20:00; "feito" with no time doesn't mean the one let go.
        assertEquals(at(2026, 11, 14, 20), e().remindAt)
        assertEquals(Status.TODO, e().statusEnum)
        assertEquals(at(2026, 11, 14, 10), Repeat.current(e(), clock, zone))
        lib.toggleTime(id, at(2026, 11, 14, 10), zone)
        assertEquals(at(2026, 11, 14, 20), Repeat.current(e(), clock, zone))
        // Hoje: nothing of it late (the 13th's 10:00 and 14:00 are, they weren't let go), nothing at 14:00.
        val today = com.villamediana.novelo.ui.library.sectionsFor(com.villamediana.novelo.ui.Filter.TODAY, listOf(e()), false, zone, clock)
        val late = today.first().second.map { it.remindAt }
        assertEquals(listOf(at(2026, 11, 13, 10), at(2026, 11, 13, 14)), late)
        assertEquals(listOf(at(2026, 11, 14, 20)), today[1].second.filter { it.statusEnum != Status.DONE }.map { it.remindAt })
        // Brought back from its page: there again, and it rings at 14:00 again.
        lib.skipTime(id, at(2026, 11, 14, 14), skip = false, zone = zone)
        assertEquals(setOf(at(2026, 11, 13, 20)), Repeat.skippedOf(e()))
        assertEquals(at(2026, 11, 14, 14), e().remindAt)
        // Let go, then done after all: just done.
        lib.skipTime(id, at(2026, 11, 14, 14), zone = zone)
        lib.setTime(id, at(2026, 11, 14, 14), true, zone)
        assertEquals(setOf(at(2026, 11, 13, 20)), Repeat.skippedOf(e()))
        assertEquals(true, at(2026, 11, 14, 14) in Repeat.doneOf(e()))
        // Two days on, the ones behind it long ago in a row from the start leave both lists.
        lib.toggleTime(id, at(2026, 11, 13, 10), zone)
        lib.toggleTime(id, at(2026, 11, 13, 14), zone)
        clock = at(2026, 11, 16, 9)
        lib.skipTime(id, at(2026, 11, 14, 20), zone = zone)
        assertEquals(at(2026, 11, 15, 10), e().occursFrom)
        assertEquals("[]", e().skippedTimes)
        // A new rule: its times count from the start, none let go.
        lib.skipTime(id, at(2026, 11, 16, 10), zone = zone)
        lib.setReminder(id, at(2026, 11, 16, 18), Repeat(Repeat.DAY, time = "18:00"))
        assertEquals("[]", e().skippedTimes)
    }

    /**
     * "Amanhã" (swiped to the left, 1.81): what made it today's — its day, its reminder, today or gone — goes to
     * tomorrow at the same hour and rings again; one with no day gets tomorrow. "Desfazer" puts it all back.
     */
    @Test
    fun leftForTomorrowAtTheSameHour() = runBlocking {
        val clock = at(2026, 11, 11, 16)
        val lib = com.villamediana.novelo.data.Library(db, object : ReminderSync { override suspend fun sync(entryIds: List<Long>) {} }, clock = { clock })
        val due = db.entries().insert(Entry(kind = "task", title = "Ligar pro banco", status = "todo", dueAt = at(2026, 11, 11, 15), dueHasTime = true, remindAt = at(2026, 11, 11, 14, 45), rungAt = at(2026, 11, 11, 14, 45), createdAt = clock, updatedAt = clock))
        val late = db.entries().insert(Entry(kind = "task", title = "Pagar a luz", status = "doing", dueAt = at(2026, 11, 9, 0), createdAt = clock, updatedAt = clock))
        val loose = db.entries().insert(Entry(kind = "task", title = "Presente da Ana", status = "todo", createdAt = clock, updatedAt = clock))
        val friday = db.entries().insert(Entry(kind = "task", title = "Relatório", status = "todo", dueAt = at(2026, 11, 13, 0), remindAt = at(2026, 11, 11, 18), createdAt = clock, updatedAt = clock))
        val later = db.entries().insert(Entry(kind = "task", title = "Dentista", status = "todo", dueAt = at(2026, 11, 20, 9), dueHasTime = true, createdAt = clock, updatedAt = clock))
        fun e(id: Long) = runBlocking { db.entries().get(id)!! }
        val before = lib.putOff(due, zone)!!
        assertEquals(at(2026, 11, 12, 15), e(due).dueAt)
        assertEquals(true, e(due).dueHasTime)
        assertEquals(at(2026, 11, 12, 14, 45), e(due).remindAt)
        assertNull(e(due).rungAt)
        lib.putOff(late, zone)
        assertEquals(at(2026, 11, 12, 0), e(late).dueAt)
        assertEquals(Status.DOING, e(late).statusEnum)
        lib.putOff(loose, zone)
        assertEquals(at(2026, 11, 12, 0), e(loose).dueAt)
        assertEquals(false, e(loose).dueHasTime)
        // Due Friday, reminded today: only the reminder goes to tomorrow.
        lib.putOff(friday, zone)
        assertEquals(at(2026, 11, 13, 0), e(friday).dueAt)
        assertEquals(at(2026, 11, 12, 18), e(friday).remindAt)
        // Nothing of it today: nothing to put off (the swipe isn't there).
        assertNull(lib.putOff(later, zone))
        assertEquals(false, Later.can(e(later), clock, zone))
        assertEquals(false, Later.can(e(due).copy(repeat = Repeat.write(Repeat(Repeat.DAY))), clock, zone))
        // "Desfazer": as it was.
        lib.restore(before)
        assertEquals(at(2026, 11, 11, 15), e(due).dueAt)
        assertEquals(at(2026, 11, 11, 14, 45), e(due).remindAt)
        assertEquals(at(2026, 11, 11, 14, 45), e(due).rungAt)
    }

    /** "Desfazer" after a time done by a swipe: not done again, and its steps as they were (done starts them over). */
    @Test
    fun undoingPutsItAllBack() = runBlocking {
        val clock = at(2026, 11, 14, 10, 5)
        val lib = com.villamediana.novelo.data.Library(db, object : ReminderSync { override suspend fun sync(entryIds: List<Long>) {} }, clock = { clock })
        val id = db.entries().insert(
            Entry(
                kind = "task", title = "Treino", status = "todo", remindAt = at(2026, 11, 14, 10), repeat = Repeat.write(Repeat(Repeat.DAY, time = "10:00")),
                occursFrom = at(2026, 11, 14, 10), steps = com.villamediana.novelo.mind.Steps.write(listOf(Step("alongar", true), Step("correr"))), createdAt = clock, updatedAt = clock,
            ),
        )
        val before = db.entries().get(id)!!
        lib.setTime(id, at(2026, 11, 14, 10), true, zone)
        assertEquals(false, com.villamediana.novelo.mind.Steps.read(db.entries().get(id)!!.steps).first().done)
        lib.restore(before)
        val back = db.entries().get(id)!!
        assertEquals(emptySet<Long>(), Repeat.doneOf(back))
        assertEquals(true, com.villamediana.novelo.mind.Steps.read(back.steps).first().done)
        assertEquals(at(2026, 11, 14, 10), back.remindAt)
        val done = db.entries().insert(Entry(kind = "task", title = "Pagar", status = "waiting", createdAt = clock, updatedAt = clock))
        val was = db.entries().get(done)!!
        lib.setStatus(done, Status.DONE, zone)
        lib.restore(was)
        assertEquals(Status.WAITING, db.entries().get(done)!!.statusEnum)
        assertNull(db.entries().get(done)!!.doneAt)
        assertEquals("waiting", db.progress().latest(done, 1).single().status)
    }
}
