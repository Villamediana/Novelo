package com.villamediana.novelo.ui.library

import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.Med
import com.villamediana.novelo.meds.Doses
import com.villamediana.novelo.remind.Repeat
import com.villamediana.novelo.ui.Filter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/** Hoje by its times (1.69): from 2 hours ago to the day's end, the medicines and the tasks of each hour together. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TodayTimesTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val today = LocalDate.of(2026, 11, 14)
    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val now = at(today, 15)

    private fun task(id: Long, title: String, due: Long? = null, dueHasTime: Boolean = due != null, remind: Long? = null) =
        Entry(id = id, kind = "task", title = title, status = "todo", dueAt = due, dueHasTime = dueHasTime, remindAt = remind, createdAt = at(today, 7), updatedAt = at(today, 7))

    @Test
    fun aTaskLeftMoreThanTwoHoursGoesToAtrasadas() {
        val all = listOf(
            task(1, "Ontem", due = at(today.minusDays(1), 18)),
            task(2, "Ligar pro banco às 9", due = at(today, 9)),
            task(3, "Lembrete das 10, vence amanhã", due = at(today.plusDays(1), 12), remind = at(today, 10)),
            task(4, "Há uma hora", remind = at(today, 14)),
            task(5, "Às 18", due = at(today, 18)),
            task(6, "Hoje, sem hora", due = at(today, 0), dueHasTime = false),
            task(7, "Às 13, no limite", remind = at(today, 13)),
        )
        val s = sectionsFor(Filter.TODAY, all, showDone = false, zone = zone, now = now).toMap()
        // Gone more than 2 hours ago: late, after the ones of the days before.
        assertEquals(listOf(1L, 2L), s["Atrasadas"]!!.map { it.id })
        // From 2 hours ago on, in its order.
        assertEquals(listOf(7L, 4L, 5L), s["Pra hoje"]!!.map { it.id })
        // Only its reminder was today, due tomorrow: not late, still for the day.
        assertEquals(listOf(6L, 3L), s["Ao longo do dia"]!!.map { it.id })
    }

    /**
     * The bug of 1.69 (Miguel's screenshots, 20:22): "Comida meninos" at 10, 14 and 20 left Hoje when its
     * 20:00 rang — the reminder went on to tomorrow's 10:00 as if it were done. Now each time is its own:
     * the 20:00 not done is in Pra hoje, the 14:00 not done is late, the 10:00 done is gone.
     */
    @Test
    fun eachTimeOfATaskThatRepeatsIsThereUntilTicked() {
        val rule = Repeat(Repeat.DAY, time = "10:00", times = listOf("10:00", "14:00", "20:00"))
        val food = task(1, "Comida meninos", remind = at(today.plusDays(1), 10)).copy(
            repeat = Repeat.write(rule), occursFrom = at(today, 10), doneTimes = Repeat.writeDone(listOf(at(today, 10))), rungAt = at(today, 20),
        )
        val night = at(today, 20, 22)
        val s = sectionsFor(Filter.TODAY, listOf(food), showDone = false, zone = zone, now = night).toMap()
        assertEquals(listOf(at(today, 20)), s["Pra hoje"]!!.map { it.remindAt })
        assertEquals(listOf(at(today, 14)), s["Atrasadas"]!!.map { it.dueAt })
        // In the afternoon: the 14:00 (an hour ago) and the 20:00 to come; the 10:00 ticked, past 2 hours, isn't there.
        val afternoon = sectionsFor(Filter.TODAY, listOf(food), showDone = false, zone = zone, now = now).toMap()
        assertEquals(listOf(at(today, 14), at(today, 20)), afternoon["Pra hoje"]!!.map { it.remindAt })
        assertEquals(emptyList<Long>(), afternoon["Atrasadas"]!!.map { it.id })
        // …it is in Feitas hoje, with its hour.
        assertEquals(listOf(at(today, 10)), afternoon["Feitas hoje"]!!.map { it.dueAt })
        // Ticked a moment ago, still in its card (struck), until 2 hours go by.
        val ticked = food.copy(doneTimes = Repeat.writeDone(listOf(at(today, 10), at(today, 14))))
        val pra = sectionsFor(Filter.TODAY, listOf(ticked), showDone = false, zone = zone, now = now).toMap()["Pra hoje"]!!
        assertEquals(listOf("done", "todo"), pra.map { it.status })
        // Yesterday's not done are late too, until ticked.
        val older = food.copy(occursFrom = at(today.minusDays(1), 20), doneTimes = "[]")
        val late = sectionsFor(Filter.TODAY, listOf(older), showDone = false, zone = zone, now = now).toMap()["Atrasadas"]!!
        assertEquals(listOf(at(today.minusDays(1), 20), at(today, 10)), late.map { it.dueAt })
        // Hoje's count on the main screen: each time not done up to tonight.
        assertEquals(4, com.villamediana.novelo.ui.home.todayCount(listOf(older), zone, now))
    }

    /** A reminder that rang isn't done: the task keeps its hour in Hoje, late when 2 hours go by (before 1.70 it left). */
    @Test
    fun aReminderThatRangKeepsTheTaskInHoje() {
        val rang = task(1, "Ligar pro banco", remind = at(today, 14)).copy(rungAt = at(today, 14))
        val yesterday = task(2, "Pagar a escola", remind = at(today.minusDays(1), 18)).copy(rungAt = at(today.minusDays(1), 18))
        val dueLater = task(3, "Renovar seguro", due = at(today.plusDays(3), 0), dueHasTime = false, remind = at(today.minusDays(1), 9))
        val overdue = task(5, "Mandar o contrato", due = at(today.minusDays(1), 0), dueHasTime = false, remind = at(today, 14, 30))
        val s = sectionsFor(Filter.TODAY, listOf(rang, yesterday, dueLater, overdue), showDone = false, zone = zone, now = now).toMap()
        assertEquals(listOf(1L), s["Pra hoje"]!!.map { it.id })
        // Due yesterday stays late even with a reminder today (as before).
        assertEquals(listOf(5L, 2L), s["Atrasadas"]!!.map { it.id })
        // Past midnight, yesterday's 23:00 is still near: Pra hoje, not late yet.
        val late = task(4, "Dar o remédio da noite", remind = at(today.minusDays(1), 23))
        val night = sectionsFor(Filter.TODAY, listOf(late), showDone = false, zone = zone, now = at(today, 0, 30)).toMap()
        assertEquals(listOf(4L), night["Pra hoje"]!!.map { it.id })
    }

    /** A task with its hour, done: struck in its card while near (as the medicines), not in Feitas hoje twice (1.71). */
    @Test
    fun aTimedTaskDoneStaysStruckInItsCard() {
        val done = task(1, "Ligar pra Ana", remind = at(today, 14)).copy(status = "done", doneAt = at(today, 14, 10), rungAt = at(today, 14))
        val early = task(2, "Comprar pão", remind = at(today, 8)).copy(status = "done", doneAt = at(today, 8, 5), rungAt = at(today, 8))
        val loose = task(3, "Ler o contrato").copy(status = "done", doneAt = at(today, 11))
        val s = sectionsFor(Filter.TODAY, listOf(done, early, loose), showDone = false, zone = zone, now = now).toMap()
        assertEquals(listOf(1L), s["Pra hoje"]!!.map { it.id })
        assertEquals(listOf("done"), s["Pra hoje"]!!.map { it.status })
        assertEquals(setOf(2L, 3L), s["Feitas hoje"]!!.map { it.id }.toSet())
    }

    @Test
    fun theMedicinesAndTheTasksOfAnHourShareItsCard() {
        val amox = Med(id = 1, name = "Amoxicilina", startsAt = 0, createdAt = 0, updatedAt = 0)
        val vit = Med(id = 2, name = "Vitamina D", startsAt = 0, createdAt = 0, updatedAt = 0)
        val slots = listOf(Doses.Slot(at(today, 14), listOf(amox)), Doses.Slot(at(today, 20), listOf(amox, vit)))
        val dinner = task(1, "Comida meninos", remind = at(today, 20))
        val call = task(2, "Ligar pra Ana", remind = at(today, 16, 30) + 12_000) // seconds don't keep it apart
        val list = moments(slots, listOf(dinner to at(today, 20), call to at(today, 16, 30) + 12_000))
        assertEquals(listOf(at(today, 14), at(today, 16, 30) + 12_000, at(today, 20)), list.map { it.at })
        assertEquals(listOf(1L, 2L), list[2].meds.map { it.id })
        assertEquals(listOf(1L), list[2].tasks.map { it.id })
        // The same minute as a dose: one card, at the dose's own time (it's ticked by it).
        val same = moments(listOf(Doses.Slot(at(today, 20), listOf(vit))), listOf(dinner to at(today, 20) + 30_000))
        assertEquals(listOf(at(today, 20)), same.map { it.at })
        // "Agora": the 14:00 not taken yet (an hour ago); taken, the next one is "próximo".
        assertEquals(at(today, 14), focusOf(list, emptySet(), now))
        assertEquals(at(today, 16, 30) + 12_000, focusOf(list, setOf(1L to at(today, 14)), now))
    }
}
