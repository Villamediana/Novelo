package com.villamediana.novelo.remind

import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.Kind
import com.villamediana.novelo.data.Status
import java.time.Instant
import java.time.ZoneId

/**
 * A task left for tomorrow ("Amanhã", 1.81: the line swiped to the left in Hoje or Tarefas; unused while the swipe is paused, 1.81.1). What made it
 * today's — its due day, its reminder, today or gone by — goes to tomorrow at the same hour; one with no day
 * at all gets tomorrow (no hour). Whatever is already after today stays where it is. A task that repeats isn't
 * put off: one of its times is let go instead ([Repeat.skip]).
 */
object Later {
    /** It can be left for tomorrow: an open task, not one that repeats, with something of today (or before), or no day at all. */
    fun can(e: Entry, now: Long, zone: ZoneId): Boolean {
        if (e.kindEnum != Kind.TASK || e.statusEnum == Status.DONE || Repeat.repeats(e)) return false
        val end = endOfToday(now, zone)
        return (e.dueAt == null && e.remindAt == null) || (e.dueAt != null && e.dueAt < end) || (e.remindAt != null && e.remindAt < end)
    }

    /** As it is tomorrow ([can] first): its day and reminder of today (or before) on tomorrow, at their hours; the reminder rings again. */
    fun tomorrow(e: Entry, now: Long, zone: ZoneId): Entry {
        val end = endOfToday(now, zone)
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
        fun moved(at: Long): Long = day.atTime(Instant.ofEpochMilli(at).atZone(zone).toLocalTime()).atZone(zone).toInstant().toEpochMilli()
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val due = when {
            e.dueAt != null && e.dueAt < end -> if (e.dueHasTime) moved(e.dueAt) else start
            e.dueAt == null && e.remindAt == null -> start
            else -> e.dueAt
        }
        val remind = e.remindAt?.let { if (it < end) moved(it) else it }
        return e.copy(
            dueAt = due,
            dueHasTime = e.dueAt != null && e.dueHasTime,
            remindAt = remind,
            rungAt = if (remind != e.remindAt) null else e.rungAt,
            updatedAt = now,
        )
    }

    private fun endOfToday(now: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}
