package com.villamediana.novelo.ui.library

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.villamediana.novelo.R
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.Med
import com.villamediana.novelo.data.Status
import com.villamediana.novelo.meds.Doses
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.ui.components.CategoryTag
import com.villamediana.novelo.ui.components.CheckCircle
import com.villamediana.novelo.ui.components.MAX_DOTS
import com.villamediana.novelo.ui.components.NoveloIcons
import com.villamediana.novelo.ui.components.StepDots
import com.villamediana.novelo.ui.components.StitchColor
import com.villamediana.novelo.mind.Steps
import com.villamediana.novelo.ui.components.meta
import com.villamediana.novelo.ui.components.stitched
import com.villamediana.novelo.ui.meds.DoseRow
import com.villamediana.novelo.ui.theme.NoveloColors
import java.time.Instant
import java.time.ZoneId

/**
 * Hoje by its times (Miguel, 1.69): one card per hour with whatever is due then — the medicines and
 * the tasks together — from 2 hours ago (to tick one forgotten) to the end of the day.
 */
data class Moment(val at: Long, val meds: List<Med>, val tasks: List<Entry>)

/** Hoje's medicines for its times: the ones due (from near now on), the doses given, and what a touch does. */
class DayDoses(
    val slots: List<Doses.Slot>,
    val taken: Set<Pair<Long, Long>>,
    val onToggle: (Long, Long) -> Unit,
    val onOpen: (Long) -> Unit,
)

/** The medicines' times and the tasks' hours as one line, in order: the ones of the same minute together. */
internal fun moments(slots: List<Doses.Slot>, tasks: List<Pair<Entry, Long>>): List<Moment> {
    class Here(var at: Long) { val meds = mutableListOf<Med>(); val tasks = mutableListOf<Entry>() }
    val byMinute = sortedMapOf<Long, Here>()
    // A dose is ticked by its time exactly: the medicines' time is the card's.
    slots.forEach { s -> byMinute.getOrPut(s.at / MINUTE) { Here(s.at) }.also { it.at = s.at }.meds += s.meds }
    tasks.forEach { (e, at) -> byMinute.getOrPut(at / MINUTE) { Here(at) }.tasks += e }
    return byMinute.values.map { Moment(it.at, it.meds, it.tasks) }
}

/** The time to look at: the first not all done that isn't long gone ("agora" near it, "próximo" before it). */
internal fun focusOf(moments: List<Moment>, taken: Set<Pair<Long, Long>>, now: Long): Long? =
    moments.firstOrNull { m -> m.at >= now - FOCUS_BEFORE_MS && (m.tasks.any { it.statusEnum != Status.DONE } || m.meds.any { (it.id to m.at) !in taken }) }?.at

/** A time stays "agora" this long after it, while not done. */
internal const val FOCUS_BEFORE_MS = 90 * 60_000L

private const val MINUTE = 60_000L

/**
 * One of Hoje's times as a card: the hour on the left (the one due now or next in the accent, one all
 * done quiet and struck), on the right its medicines, each with its circle to tick, then its tasks.
 */
@Composable
internal fun MomentCard(
    moment: Moment,
    shape: Shape,
    focus: Boolean,
    now: Long,
    taken: Set<Pair<Long, Long>>,
    onDose: (Long, Long) -> Unit,
    onMed: (Long) -> Unit,
    onTask: (Long) -> Unit,
    onToggleTask: (Entry) -> Unit,
    showPeople: Boolean,
    modifier: Modifier = Modifier,
) {
    val zone = ZoneId.systemDefault()
    val done = moment.tasks.all { it.statusEnum == Status.DONE } && moment.meds.all { (it.id to moment.at) in taken }
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(NoveloColors.Surface)
            .stitched(shape, StitchColor)
            .padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)
            .animateContentSize(),
    ) {
        Column(Modifier.width(62.dp).padding(top = 10.dp)) {
            Text(
                Doses.hhmm(Instant.ofEpochMilli(moment.at).atZone(zone).toLocalTime()),
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                color = when { done -> NoveloColors.TextFaint; focus -> NoveloColors.Accent; else -> NoveloColors.Text },
                textDecoration = if (done) TextDecoration.LineThrough else null,
            )
            if (focus) Text(t(if (moment.at <= now) R.string.med_now else R.string.med_next), style = MaterialTheme.typography.labelSmall, color = NoveloColors.Accent)
        }
        Column(Modifier.weight(1f)) {
            moment.meds.forEach { m -> DoseRow(m, moment.at, (m.id to moment.at) in taken, onDose, onMed, showPeople) }
            moment.tasks.forEach { e -> TaskRow(e, onOpen = { onTask(e.id) }, onToggle = { onToggleTask(e) }) }
        }
    }
}

/** A task at its hour: the circle to tick, the title, and its quiet line (category, steps, what else matters). */
@Composable
private fun TaskRow(e: Entry, onOpen: () -> Unit, onToggle: () -> Unit) {
    val done = e.statusEnum == Status.DONE
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpen).testTag("today-task-${e.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.testTag("check-task-${e.id}")) { CheckCircle(done, NoveloColors.Accent, onToggle, size = 22.dp) }
        Column(Modifier.weight(1f).padding(top = 6.dp, bottom = 6.dp, end = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = if (done) NoveloColors.TextMuted else NoveloColors.Text,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (e.sensitive) {
                    Spacer(Modifier.width(6.dp))
                    Icon(NoveloIcons.Lock, t(R.string.sensitive), tint = NoveloColors.TextFaint, modifier = Modifier.size(14.dp))
                }
            }
            val line = meta(e, named = false, withStatus = true, inToday = true, timeShown = true)
            val category = e.category
            if (line.isNotEmpty() || category != null) {
                Spacer(Modifier.height(3.dp))
                val steps = remember(e.steps) { Steps.read(e.steps) }
                val stepsText = if (steps.isEmpty()) null else "${steps.count { it.done }}/${steps.size}"
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (category != null) CategoryTag(category)
                    line.forEach { (text, strong) ->
                        if (text == stepsText && steps.size <= MAX_DOTS) StepDots(steps.count { it.done }, steps.size)
                        Text(text, style = MaterialTheme.typography.labelMedium, color = if (strong) NoveloColors.Accent else NoveloColors.TextFaint, maxLines = 1)
                    }
                }
            }
        }
    }
}
