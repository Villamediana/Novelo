package com.villamediana.novelo.ui.library

import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.Med
import com.villamediana.novelo.meds.Doses
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
