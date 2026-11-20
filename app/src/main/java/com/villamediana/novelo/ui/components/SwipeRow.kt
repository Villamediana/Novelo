package com.villamediana.novelo.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.villamediana.novelo.ui.theme.NoveloColors
import com.villamediana.novelo.ui.theme.NoveloText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sign

/**
 * What a line swiped to one side does (1.81): its word and drawing, its color and what goes on it, and
 * whether the line leaves the list after it ([leaves]: it slides away) or stays (it comes back to its
 * place, changed — done and struck, or with its new day).
 */
@Immutable
class SwipeAction(
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val onColor: Color,
    val leaves: Boolean,
    val run: () -> Unit,
)

/**
 * A line that can be swiped to the side (Hoje and Tarefas, 1.81): [right] when the finger goes right (it
 * shows at the start), [left] when it goes left. The line goes with the finger and, under it, what the
 * swipe does comes into view — a soft band of its color with its drawing, which grows with the way;
 * past the line ([armAt]) the color floods from the drawing, the phone ticks, and letting go does it.
 * Short of it, the line goes back. A side with nothing to do only gives a little.
 *
 * The swipe is the line's own (as the line under the button, 1.78): started on it, the tabs don't turn;
 * a swipe up and down is still the list's. A screen reader has both as actions of the line: [content]
 * gets them as a modifier for the part it touches (the one a screen reader stops on).
 */
@Composable
fun Swipeable(
    key: Any,
    right: SwipeAction?,
    left: SwipeAction?,
    shape: Shape,
    modifier: Modifier = Modifier,
    /** A line with no paper of its own (a row inside a card): this under it, so what is behind doesn't show through. */
    surface: Color? = null,
    /** How far in from the edge the drawing sits. */
    inset: Dp = 20.dp,
    /** The running stitch on what shows under it (the cards have one; a row inside a card doesn't). */
    stitch: Boolean = true,
    content: @Composable (actions: Modifier) -> Unit,
) {
    if (right == null && left == null) {
        Box(modifier) { content(Modifier) }
        return
    }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val offset = remember(key) { Animatable(0f) }
    var armed by remember(key) { mutableStateOf(false) }
    var width by remember { mutableIntStateOf(0) }
    val toRight by rememberUpdatedState(right)
    val toLeft by rememberUpdatedState(left)
    // Past the line the color floods from the drawing; the drawing gives a little jump as it does.
    val flood by animateFloatAsState(if (armed) 1f else 0f, spring(dampingRatio = 1f, stiffness = 240f), label = "flood")
    val pop = remember(key) { Animatable(1f) }
    LaunchedEffect(armed) {
        if (armed) {
            pop.snapTo(0.8f)
            pop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 650f))
        }
    }

    fun settle() {
        armed = false
        scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 500f)) }
    }

    fun commit(a: SwipeAction, side: Float) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch {
            if (a.leaves) {
                // It slides away, then it's done (the list closes the gap behind it).
                offset.animateTo(side * width.toFloat(), tween(LEAVING_MS, easing = FastOutLinearInEasing))
                a.run()
                // Still here after its way out (it didn't leave after all): it comes back in, changed.
                delay(COME_BACK_MS)
                armed = false
                offset.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 380f))
            } else {
                // Done right away: it comes back to its place over the flood, changed.
                a.run()
                offset.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 380f))
                armed = false
            }
        }
    }

    val actions = Modifier.semantics {
        customActions = listOfNotNull(right, left).map { a -> CustomAccessibilityAction(a.label) { a.run(); true } }
    }
    Box(modifier.onSizeChanged { width = it.width }) {
        val x = offset.value
        val shown = when {
            x > 0f -> right
            x < 0f -> left
            else -> null
        }
        if (shown != null) {
            Under(shown, x, armAt(width, density), flood, pop.value, shape, start = x > 0f, inset = inset, stitch = stitch, modifier = Modifier.matchParentSize())
        }
        Box(
            Modifier
                .graphicsLayer { translationX = offset.value }
                .then(if (surface != null) Modifier.clip(shape).background(surface) else Modifier)
                .pointerInput(key) {
                    var raw = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { raw = offset.value },
                        onDragEnd = {
                            val v = offset.value
                            val a = if (v > 0f) toRight else if (v < 0f) toLeft else null
                            if (a != null && abs(v) >= armAt(size.width, this)) commit(a, sign(v)) else settle()
                        },
                        onDragCancel = { settle() },
                    ) { change, amount ->
                        change.consume()
                        raw += amount
                        val a = if (raw > 0f) toRight else if (raw < 0f) toLeft else null
                        // A side with nothing to do gives only a little (it says no); the other goes with the finger.
                        val v = if (a == null) sign(raw) * min(abs(raw) * 0.18f, 14.dp.toPx()) else raw.coerceIn(-size.width.toFloat(), size.width.toFloat())
                        scope.launch { offset.snapTo(v) }
                        val past = a != null && abs(v) >= armAt(size.width, this)
                        if (past != armed) {
                            armed = past
                            haptics.performHapticFeedback(if (past) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.SegmentTick)
                        }
                    }
                },
        ) { content(actions) }
    }
}

/** How far a line has to go for letting go to do it: a third of it, not less than a thumb nor more than half a card. */
fun armAt(width: Int, density: Density): Float = with(density) { (width * 0.3f).coerceIn(64.dp.toPx(), 120.dp.toPx()) }

/** How long a line takes to slide away. */
private const val LEAVING_MS = 200

/** A line that slid away and is still there this long after (it didn't leave the list) comes back in. */
private const val COME_BACK_MS = 700L

/**
 * What shows under a line being swiped: a band of the action's color, soft, with its drawing near the
 * edge — growing with the way, its word coming once there is room — and, past the line, the color
 * flooding from the drawing to the whole band, the drawing and the word in what reads on it.
 */
@Composable
private fun Under(a: SwipeAction, x: Float, armAt: Float, flood: Float, pop: Float, shape: Shape, start: Boolean, inset: Dp, stitch: Boolean, modifier: Modifier) {
    val density = LocalDensity.current
    val dark = NoveloColors.palette.dark
    val way = (abs(x) / armAt).coerceIn(0f, 1f)
    val tint = a.color.copy(alpha = if (dark) 0.2f else 0.13f)
    val ink = lerp(a.color, a.onColor, flood)
    val ring = with(density) { ICON.toPx() / 2f }
    val edge = with(density) { inset.toPx() } + ring
    // The word comes once the drawing is all out and there is room beside it.
    val room = with(density) { (abs(x) - (inset + ICON + 10.dp).toPx()) / 40.dp.toPx() }.coerceIn(0f, 1f)
    Box(
        modifier
            .clip(shape)
            .drawBehind {
                drawRect(tint)
                if (flood > 0f) {
                    val center = Offset(if (start) edge else size.width - edge, size.height / 2f)
                    drawCircle(a.color, radius = flood * hypot(size.width, size.height), center = center)
                }
            }
            .then(if (stitch) Modifier.stitched(shape, lerp(a.color.copy(alpha = 0.3f), a.onColor.copy(alpha = 0.42f), flood)) else Modifier)
            .padding(horizontal = inset),
        contentAlignment = if (start) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!start) Word(a.label, ink, room)
            Box(
                Modifier
                    .size(ICON)
                    .graphicsLayer {
                        val s = (0.55f + 0.45f * way) * pop
                        scaleX = s
                        scaleY = s
                        alpha = (way * 1.8f).coerceAtMost(1f)
                    },
                contentAlignment = Alignment.Center,
            ) {
                // A ring that closes as the line goes: full at the line (the circle to tick, drawn by the way).
                Canvas(Modifier.size(ICON)) {
                    val w = 1.6.dp.toPx()
                    val box = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
                    val at = Offset(w / 2, w / 2)
                    drawArc(ink.copy(alpha = 0.22f), 0f, 360f, false, at, box, style = Stroke(w))
                    drawArc(ink, -90f, 360f * way, false, at, box, style = Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
                Icon(a.icon, null, tint = ink, modifier = Modifier.size(ICON * 0.56f))
            }
            if (start) Word(a.label, ink, room)
        }
    }
}

@Composable
private fun Word(text: String, color: Color, alpha: Float) {
    Text(text, style = NoveloText.Section, color = color, maxLines = 1, modifier = Modifier.graphicsLayer { this.alpha = alpha })
}

/** The drawing's circle under a swiped line. */
private val ICON = 34.dp
