package com.villamediana.novelo.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.villamediana.novelo.R
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.Library
import com.villamediana.novelo.data.Med
import com.villamediana.novelo.data.Status
import com.villamediana.novelo.remind.Later
import com.villamediana.novelo.remind.Repeat
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.ui.components.Dates
import com.villamediana.novelo.ui.components.NoveloIcons
import com.villamediana.novelo.ui.components.SwipeAction
import com.villamediana.novelo.ui.components.stitched
import com.villamediana.novelo.ui.meds.personColor
import com.villamediana.novelo.ui.theme.NoveloColors
import com.villamediana.novelo.ui.theme.onAccentFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * A swipe done (1.81), for a moment at the bottom of the screen: what happened to what ("Pulado · Academia"),
 * in the swipe's color, and "Desfazer", which puts it back as it was.
 */
class Undo(val text: String, val icon: ImageVector, val color: Color, val onColor: Color, val back: suspend () -> Unit)

/** Where a line of Hoje or Tarefas is: what a swipe on it can do depends on it. */
enum class Place {
    /** Hoje › Pra hoje: a task at its hour, in the hour's card (done, it stays there struck). */
    HOJE_TIME,

    /** Hoje › Atrasadas, Ao longo do dia: a task of today (or before). */
    HOJE,

    /** Hoje › Em andamento: being done, with no day — it is in Hoje whatever its day, so there's nothing to put off. */
    HOJE_DOING,

    /** Tarefas: the open ones, by category. */
    TASKS,

    /** Feitas (Tarefas) and Feitas hoje (Hoje), folded: done — a swipe opens it again. */
    DONE,
}

/**
 * What the lines of Hoje and Tarefas do when swiped (Miguel, 1.81: "swipe pra marcar, outro pra pular"):
 * to the right it is done (or open again, when it is); to the left it is let go for now — one that repeats
 * skips that time, any other goes to tomorrow at its hour. Each one says what it did, with a way back ([told]).
 */
class Swipes(private val scope: CoroutineScope, private val lib: Library, private val told: (Undo) -> Unit) {
    /** [e]'s swipes ([e] as the line shows it: in Hoje, one time of one that repeats is the task at that time). Right, left. */
    @Composable
    fun forTask(e: Entry, place: Place): Pair<SwipeAction?, SwipeAction?> {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val repeats = Repeat.repeats(e)
        // (A done one that repeats is only ever in Hoje's Feitas hoje: one of its times, done.)
        val hoje = place != Place.TASKS
        // The time a swipe means, for one that repeats: in Hoje the line's own (each time is its own line), elsewhere the one that counts now.
        val at = if (!repeats) null else if (hoje) e.remindAt else Repeat.current(e, now, zone)
        val done = e.statusEnum == Status.DONE
        val accent = NoveloColors.Accent
        val onAccent = NoveloColors.OnAccent
        val ink = NoveloColors.Text
        val paper = NoveloColors.Surface
        val check = NoveloIcons.Check
        val again = NoveloIcons.Retry
        val skip = NoveloIcons.Skip
        val right = if (done) {
            SwipeAction(t(R.string.swipe_reopen), again, ink, paper, leaves = place != Place.HOJE_TIME) {
                act(e, t(R.string.undo_reopened), again, ink, paper) { if (at != null) lib.setTime(e.id, at, false, zone) else lib.setStatus(e.id, Status.TODO, zone) }
            }
        } else {
            // Done, it leaves the list — but not one that stays in its hour's card struck, nor one that repeats in Tarefas (it is there for the next time).
            val leaves = place != Place.HOJE_TIME && !(place == Place.TASKS && repeats)
            SwipeAction(t(R.string.swipe_done), check, accent, onAccent, leaves) {
                act(e, t(R.string.undo_done), check, accent, onAccent) { if (at != null) lib.setTime(e.id, at, true, zone) else lib.setStatus(e.id, Status.DONE, zone) }
            }
        }
        val left = when {
            done || place == Place.HOJE_DOING || place == Place.DONE -> null
            repeats && at != null -> {
                // In Tarefas the card is the task, not one time: it says which time goes.
                val label = if (hoje) t(R.string.swipe_skip) else t(R.string.swipe_skip_at, whenOf(at, now, zone))
                SwipeAction(label, skip, ink, paper, leaves = place != Place.TASKS) {
                    act(e, t(R.string.undo_skipped) + " " + whenOf(at, now, zone), skip, ink, paper) { lib.skipTime(e.id, at, true, zone) }
                }
            }
            !repeats && Later.can(e, now, zone) -> SwipeAction(t(R.string.swipe_tomorrow), skip, ink, paper, leaves = place != Place.TASKS) {
                act(e, t(R.string.undo_tomorrow), skip, ink, paper) { lib.putOff(e.id, zone) }
            }
            else -> null
        }
        return right to left
    }

    /** A dose in Hoje's card of its hour: to the right, given (it stays there struck). Nothing to the left. */
    @Composable
    fun forDose(m: Med, at: Long, taken: Boolean): SwipeAction? {
        if (taken) return null
        val color = personColor(m.person)
        val on = onAccentFor(color)
        val check = NoveloIcons.Check
        val text = t(R.string.med_taken_action)
        return SwipeAction(text, check, color, on, leaves = false) {
            scope.launch {
                lib.toggleTaken(m.id, at)
                told(Undo(text + " · " + m.name.ifBlank { t(R.string.med_untitled) }, check, color, on) { lib.toggleTaken(m.id, at) })
            }
        }
    }

    /** Does [change] to [e] and says so, with the way back to how it was. */
    private fun act(e: Entry, said: String, icon: ImageVector, color: Color, on: Color, change: suspend () -> Unit) {
        scope.launch {
            val before = lib.get(e.id) ?: return@launch
            change()
            told(Undo(said + " · " + e.title, icon, color, on) { lib.restore(before) })
        }
    }

    /** "14:00", or "amanhã · 10:00" when the time isn't today's. */
    private fun whenOf(at: Long, now: Long, zone: ZoneId): String =
        if (Instant.ofEpochMilli(at).atZone(zone).toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) Dates.time(at, zone) else Dates.dayTime(at, zone)
}

/**
 * The swipe just done, at the bottom: a pill of ink with a stitch, the swipe's drawing in its color, what
 * happened, and "Desfazer". It goes by itself after a few seconds (a new swipe takes its place).
 */
@Composable
fun UndoBar(undo: Undo?, onUndo: (Undo) -> Unit, onGone: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(undo) {
        if (undo != null) {
            delay(UNDO_MS)
            onGone()
        }
    }
    // The last one shown, to go out with its words.
    var last by remember { mutableStateOf(undo) }
    if (undo != null) last = undo
    AnimatedVisibility(
        visible = undo != null,
        modifier = modifier,
        enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = 420f)) { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        val u = last ?: return@AnimatedVisibility
        val pill = RoundedCornerShape(26.dp)
        val ink = NoveloColors.Text
        val paper = NoveloColors.Surface
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .shadow(6.dp, pill)
                .clip(pill)
                .background(ink)
                .stitched(pill, paper.copy(alpha = 0.22f), inset = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(start = 10.dp, end = 6.dp)
                .testTag("undo"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(u, Modifier.weight(1f), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "undo") { now ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(now.color), contentAlignment = Alignment.Center) {
                        Icon(now.icon, null, tint = now.onColor, modifier = Modifier.size(17.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(now.text, style = MaterialTheme.typography.bodyMedium, color = paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(paper.copy(alpha = 0.12f))
                    .clickable { onUndo(u) }
                    .padding(horizontal = 16.dp, vertical = 9.dp)
                    .testTag("undo-action"),
            ) {
                Text(t(R.string.undo), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = paper)
            }
        }
    }
}

/** How long "Desfazer" stays. */
private const val UNDO_MS = 5_000L
