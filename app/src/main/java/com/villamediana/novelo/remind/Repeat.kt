package com.villamediana.novelo.remind

import com.villamediana.novelo.R
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.Kind
import com.villamediana.novelo.data.Status
import com.villamediana.novelo.settings.Lang
import com.villamediana.novelo.settings.t
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle

/**
 * How a reminder (and its task) comes back: every day, on some days of the week, or every month on
 * one day — at [time], or at each of [times] when it is more than once a day ("10:00, 14:00 e 20:00").
 * Kept with the entry as JSON ([Entry.repeat]).
 */
@Serializable
data class Repeat(
    /** [DAY], [WEEK] or [MONTH]. */
    val every: String,
    /** For [WEEK]: the days, 1 = Monday … 7 = Sunday. */
    val days: List<Int> = emptyList(),
    /** "HH:MM". */
    val time: String = "09:00",
    /** For [MONTH]: the day of the month (a short month takes its last day). */
    val day: Int? = null,
    /** More than one time a day ("HH:MM", in order); empty: only [time]. */
    val times: List<String> = emptyList(),
) {
    /** Each of its times of the day, in order. */
    val allTimes: List<LocalTime>
        get() = times.ifEmpty { listOf(time) }.mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }.distinct().sorted().ifEmpty { listOf(LocalTime.of(9, 0)) }

    /** The first time it comes after [after] (ms); null when the rule says nothing. */
    fun next(after: Long, zone: ZoneId): Long? {
        val from = Instant.ofEpochMilli(after).atZone(zone)
        val start = from.toLocalDate()
        val ats = allTimes
        fun ms(d: LocalDate) = ats.map { d.atTime(it).atZone(zone).toInstant().toEpochMilli() }
        return when (every) {
            DAY -> (0..1).map { start.plusDays(it.toLong()) }.flatMap(::ms).firstOrNull { it > after }
            WEEK -> {
                val on = days.filter { it in 1..7 }.toSet().ifEmpty { return null }
                (0..7).map { start.plusDays(it.toLong()) }.filter { it.dayOfWeek.value in on }.flatMap(::ms).firstOrNull { it > after }
            }
            MONTH -> {
                val dom = (day ?: start.dayOfMonth).coerceIn(1, 31)
                (0..12).map { start.withDayOfMonth(1).plusMonths(it.toLong()) }
                    .map { m -> m.withDayOfMonth(minOf(dom, m.lengthOfMonth())) }
                    .flatMap(::ms).firstOrNull { it > after }
            }
            else -> null
        }
    }

    /** Its times from [from] (included) up to [until] (not), in order. */
    fun between(from: Long, until: Long, zone: ZoneId): List<Long> {
        val out = mutableListOf<Long>()
        var t = next(from - 1, zone)
        while (t != null && t < until && out.size < MAX_TIMES) {
            out += t
            t = next(t, zone)
        }
        return out
    }

    /** "todo dia às 08:00", "todo dia às 10:00, 14:00 e 20:00", "seg e qua às 08:00", "todo mês no dia 10 às 08:00" — in the app's language. */
    fun describe(): String {
        val at = joinAnd(allTimes.map { "%02d:%02d".format(it.hour, it.minute) })
        return when (every) {
            DAY -> t(R.string.repeat_daily_at, at)
            WEEK -> {
                val names = days.filter { it in 1..7 }.sorted().map { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Lang.locale).removeSuffix(".") }
                if (names.size == 7) t(R.string.repeat_daily_at, at) else t(R.string.repeat_days_at, joinAnd(names), at)
            }
            MONTH -> t(R.string.repeat_monthly_at, day ?: 1, at)
            else -> ""
        }
    }

    private fun joinAnd(names: List<String>): String =
        if (names.size <= 1) names.joinToString("") else names.dropLast(1).joinToString(", ") + " " + t(R.string.and) + " " + names.last()

    companion object {
        const val DAY = "day"
        const val WEEK = "week"
        const val MONTH = "month"

        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        fun read(text: String?): Repeat? = text?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
            ?.takeIf { it.every in setOf(DAY, WEEK, MONTH) && (it.every != WEEK || it.days.any { d -> d in 1..7 }) }

        fun write(r: Repeat?): String? = r?.let { json.encodeToString(serializer(), it) }

        /**
         * A rule said by the model or chosen on screen, made whole from the first time it comes
         * ([first], ms): its hour, its day of the week or of the month when none was given. [times]:
         * more than one time a day ("10:00", "14h"…) — then those are its hours.
         */
        fun from(every: String?, days: List<Int>, first: Long, zone: ZoneId, times: List<String> = emptyList()): Repeat? {
            val at = Instant.ofEpochMilli(first).atZone(zone)
            val said = times.mapNotNull { com.villamediana.novelo.meds.Doses.time(it) }.distinct().sorted().map { "%02d:%02d".format(it.hour, it.minute) }
            val time = said.firstOrNull() ?: "%02d:%02d".format(at.hour, at.minute)
            val more = if (said.size > 1) said else emptyList()
            return when (every?.trim()?.lowercase()) {
                DAY -> Repeat(DAY, time = time, times = more)
                WEEK -> Repeat(WEEK, days.filter { it in 1..7 }.distinct().sorted().ifEmpty { listOf(at.dayOfWeek.value) }, time, times = more)
                MONTH -> Repeat(MONTH, time = time, day = at.dayOfMonth, times = more)
                else -> null
            }
        }

        /**
         * A task that repeats, done now ("feito" said, its circle in Tarefas, the notification): the time
         * it means is done ([current]) and it is there again for the next. Anything else is just done.
         */
        fun done(e: Entry, now: Long, zone: ZoneId): Entry {
            // (The model's "done" comes already marked so: its times are looked at as the open task's.)
            val open = e.copy(status = Status.TODO.key, doneAt = null)
            val at = if (repeats(e)) current(open, now, zone) else null
            if (at == null) return e.copy(status = Status.DONE.key, doneAt = now, checkInAt = null, remindAt = null, updatedAt = now)
            return tick(open, at, done = true, now = now, zone = zone)
        }

        /** At most this many times in one look (years of a task three times a day). */
        const val MAX_TIMES = 2_000

        /** Its times done ones stay listed this long (Hoje shows the last 2 hours, struck); older ones in a row from the start leave. */
        private const val KEEP_DONE_MS = 24 * 60 * 60_000L

        private val longs = kotlinx.serialization.builtins.ListSerializer(Long.serializer())

        /** A task that comes back (each of its times is ticked on its own, 1.70). */
        fun repeats(e: Entry): Boolean = e.kindEnum == Kind.TASK && read(e.repeat) != null

        /** Its times done. */
        fun doneOf(e: Entry): Set<Long> = runCatching { json.decodeFromString(longs, e.doneTimes) }.getOrDefault(emptyList()).toSet()

        /** Its times done, written as [Entry.doneTimes] keeps them. */
        fun writeDone(times: Collection<Long>): String = json.encodeToString(longs, times.distinct().sorted())

        /** Where its times count from (one from before 1.70 not yet fixed: its reminder). */
        private fun startOf(e: Entry): Long? = e.occursFrom ?: e.remindAt

        /** Its times from where they count up to [until] (not included), each with done or not. */
        fun timesOf(e: Entry, until: Long, zone: ZoneId): List<Pair<Long, Boolean>> {
            if (!repeats(e)) return emptyList()
            val rule = read(e.repeat) ?: return emptyList()
            val from = startOf(e) ?: return emptyList()
            val done = doneOf(e)
            return rule.between(from, until, zone).map { it to (it in done) }
        }

        /** The last of its times that has come by [now], when it isn't done yet; null otherwise. */
        fun come(e: Entry, now: Long, zone: ZoneId): Long? = timesOf(e, now + 1, zone).lastOrNull()?.takeIf { !it.second }?.first

        /**
         * The time a tick without one means: the last that has come, when not done yet; otherwise
         * the next one (done ahead of it). The older ones not done are ticked by their own time (Hoje).
         */
        fun current(e: Entry, now: Long, zone: ZoneId): Long? {
            come(e, now, zone)?.let { return it }
            val rule = read(e.repeat) ?: return null
            val from = startOf(e) ?: return null
            val done = doneOf(e)
            var t = rule.next(maxOf(now, from - 1), zone)
            var n = 0
            while (t != null && t in done && n++ < MAX_TIMES) t = rule.next(t, zone)
            return t
        }

        /** When it rings next after [after]: the first of its times not done already (null: it doesn't repeat). */
        fun nextRing(e: Entry, after: Long, zone: ZoneId): Long? {
            val rule = read(e.repeat) ?: return null
            val done = if (e.kindEnum == Kind.TASK) doneOf(e) else emptySet()
            var t = rule.next(after, zone)
            var n = 0
            while (t != null && t in done && n++ < MAX_TIMES) t = rule.next(t, zone)
            return t
        }

        /** [at] is one of its times (not a reminder put off to another hour). */
        fun isTime(e: Entry, at: Long, zone: ZoneId): Boolean = read(e.repeat)?.next(at - 1, zone) == at

        /**
         * One of its times ticked ([done]) or not any more: the task stays open, its reminder on the
         * next time not done, its due day (when it has one) on the time that counts now; ticked, its
         * steps start over. The times done long ago in a row from the start leave the list.
         */
        fun tick(e: Entry, at: Long, done: Boolean, now: Long, zone: ZoneId): Entry {
            val rule = read(e.repeat) ?: return e
            val times = doneOf(e).toMutableSet()
            if (done) times += at else times -= at
            var from = minOf(startOf(e) ?: at, at)
            var n = 0
            while (from in times && from < now - KEEP_DONE_MS && n++ < MAX_TIMES) {
                times -= from
                from = rule.next(from, zone) ?: break
            }
            var after = e.copy(
                status = Status.TODO.key,
                doneAt = null,
                occursFrom = from,
                doneTimes = writeDone(times.filter { it >= from }),
                checkInAt = if (done) null else e.checkInAt,
                updatedAt = now,
            )
            after = after.copy(remindAt = nextRing(after, now, zone))
            val due = after.dueAt?.let { current(after, now, zone) }?.let { t ->
                if (after.dueHasTime) t else Instant.ofEpochMilli(t).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
            }
            if (due != null) after = after.copy(dueAt = due)
            // Its steps start over for the next time.
            if (done) after = after.copy(steps = com.villamediana.novelo.mind.Steps.write(com.villamediana.novelo.mind.Steps.read(e.steps).map { it.copy(done = false) }))
            return after
        }

        /** One of its times as Hoje shows it: the task at that hour, done or not. */
        fun at(e: Entry, time: Long, done: Boolean): Entry =
            e.copy(dueAt = time, dueHasTime = true, remindAt = time, status = if (done) Status.DONE.key else Status.TODO.key)
    }
}
