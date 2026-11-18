package com.villamediana.novelo.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.villamediana.novelo.R
import com.villamediana.novelo.mind.MindState
import com.villamediana.novelo.mind.Stage
import com.villamediana.novelo.mind.Waiting
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.ui.components.IconAction
import com.villamediana.novelo.ui.components.NoveloIcons
import com.villamediana.novelo.ui.components.groupShape
import com.villamediana.novelo.ui.components.pressScale
import com.villamediana.novelo.ui.theme.NoveloColors
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The line under the button: what she said that didn't go all the way yet, the oldest on top. The
 * one going through turns (one button: it turns, and a touch stops it); the others wait their
 * turn, or for the internet, or for her (send again · throw away). A row swiped to the side is
 * thrown away. The button above stays free the whole time.
 */
@Composable
internal fun LineTray(
    line: List<Waiting>,
    going: MindState.Working?,
    onStop: () -> Unit,
    onRetry: (Long) -> Unit,
    onDiscard: (Long) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    // What she just said comes into view at the end of the line.
    var seen by remember { mutableIntStateOf(line.size) }
    LaunchedEffect(line.size) {
        if (line.size > seen) runCatching { list.animateScrollToItem(line.lastIndex) }
        seen = line.size
    }
    LazyColumn(
        modifier.fillMaxWidth().heightIn(max = TRAY_MAX).testTag("line"),
        state = list,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(line, key = { it.id }) { w ->
            val i = line.indexOf(w)
            LineRow(
                w = w,
                going = going?.takeIf { it.captureId == w.id },
                shape = groupShape(i, line.size, outer = 20.dp),
                onStop = onStop,
                onRetry = { onRetry(w.id) },
                onDiscard = { onDiscard(w.id) },
                onSettings = onSettings,
                modifier = Modifier.animateItem(),
            )
        }
    }
}

/** About three rows show; more than that and the line scrolls. */
private val TRAY_MAX = 190.dp

@Composable
internal fun LineRow(
    w: Waiting,
    going: MindState.Working?,
    shape: androidx.compose.ui.graphics.Shape,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val slide = remember { Animatable(0f) }
    val discard by rememberUpdatedState(onDiscard)
    val working = w.where == Waiting.Where.GOING
    // The words: as they are heard while it is heard, what she said otherwise.
    val words = when {
        w.kind == Waiting.Kind.PHOTO -> t(R.string.line_photo)
        working && going?.stage == Stage.HEARING && going.text.isNotBlank() -> tail(going.text, 90)
        // (What is going through knows its words before the line reads them again.)
        else -> w.text.trim().ifEmpty { going?.text?.trim().orEmpty().takeIf { w.kind != Waiting.Kind.FILE }.orEmpty() }
    }
    // A recording not heard yet (no network for the ear): how long it is, so one tells from another.
    val unheard = words.isEmpty() && w.kind == Waiting.Kind.VOICE && w.durationMs > 0
    val status = when (w.where) {
        Waiting.Where.GOING -> going?.let(::stageText) ?: t(R.string.line_going)
        Waiting.Where.IN_LINE -> t(R.string.line_in_line)
        Waiting.Where.LATER -> if (w.message == t(R.string.offline_kept)) t(R.string.line_offline) else t(R.string.line_soon)
        Waiting.Where.STOPPED -> t(R.string.line_stopped)
        Waiting.Where.FAILED -> w.message?.takeIf { it.isNotBlank() } ?: t(R.string.line_failed)
    }
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationX = slide.value
                alpha = 1f - (abs(slide.value) / size.width.coerceAtLeast(1f) * 1.4f).coerceIn(0f, 1f)
            }
            // Swiped to the side, it is thrown away (the row's own gesture: the screen under it doesn't change).
            .pointerInput(w.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val far = abs(slide.value) > size.width * SWIPE_AWAY
                        scope.launch {
                            if (far) {
                                slide.animateTo(if (slide.value < 0) -size.width.toFloat() else size.width.toFloat(), tween(160))
                                discard()
                            } else {
                                slide.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 500f))
                            }
                        }
                    },
                    onDragCancel = { scope.launch { slide.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 500f)) } },
                ) { change, amount ->
                    change.consume()
                    scope.launch { slide.snapTo(slide.value + amount) }
                }
            }
            .clip(shape)
            .background(NoveloColors.Surface)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .heightIn(min = 40.dp)
            .testTag("line-${w.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                status,
                style = MaterialTheme.typography.labelMedium,
                color = if (working) NoveloColors.Accent else NoveloColors.TextMuted,
                maxLines = if (w.where == Waiting.Where.FAILED) 3 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (words.isNotEmpty() || unheard) {
                Text(
                    if (unheard) t(R.string.line_audio, (w.durationMs / 1000).let { "%d:%02d".format(it / 60, it % 60) })
                    else if (w.kind == Waiting.Kind.PHOTO || w.kind == Waiting.Kind.FILE) words else "“$words”",
                    style = MaterialTheme.typography.bodySmall,
                    color = NoveloColors.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        when (w.where) {
            Waiting.Where.GOING -> SpinStop(onStop)
            // Waiting its turn: nothing to do but wait (or swipe it away).
            Waiting.Where.IN_LINE -> Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(NoveloColors.TextFaint.copy(alpha = 0.5f)))
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (w.need != null) IconAction(NoveloIcons.Tune, t(R.string.settings), onSettings, primary = true, size = 40.dp)
                IconAction(NoveloIcons.Retry, t(R.string.try_again), onRetry, primary = w.need == null, size = 40.dp)
                IconAction(NoveloIcons.Trash, t(R.string.discard), onDiscard, size = 40.dp)
            }
        }
    }
}

/** How far across a row has to go to be thrown away. */
private const val SWIPE_AWAY = 0.38f

/** The one button of what is going through: a thread turning around a stop. A touch stops it. */
@Composable
private fun SpinStop(onStop: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val turn = rememberInfiniteTransition(label = "turn")
    val angle by turn.animateFloat(0f, 360f, infiniteRepeatable(tween(1_100, easing = LinearEasing)), label = "angle")
    val ring = NoveloColors.Accent
    Box(
        Modifier
            .size(40.dp)
            .pressScale(interaction, 0.9f)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onStop)
            .semantics { role = Role.Button; contentDescription = t(R.string.stop) }
            .testTag("line-stop"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(30.dp)) {
            val w = 2.dp.toPx()
            val box = Size(size.width - w, size.height - w)
            val at = Offset(w / 2, w / 2)
            drawArc(ring.copy(alpha = 0.16f), 0f, 360f, false, at, box, style = Stroke(w))
            drawArc(ring, angle - 90f, 96f, false, at, box, style = Stroke(w, cap = StrokeCap.Round))
        }
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(NoveloColors.TextMuted))
    }
}
