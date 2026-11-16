package com.villamediana.novelo.ui.alarm

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.R
import com.villamediana.novelo.remind.AlarmService
import com.villamediana.novelo.remind.Ringing
import com.villamediana.novelo.settings.NoveloSettings
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.ui.components.Dates
import com.villamediana.novelo.ui.components.NoveloMark
import com.villamediana.novelo.ui.theme.NoveloColors
import com.villamediana.novelo.ui.theme.NoveloTheme
import com.villamediana.novelo.ui.components.paper

/**
 * The alarm ringing, over the lock screen (it turns the screen on): what it is and its time, "Parar" big,
 * and "Mais 10 min" and "Feito" quiet beside. It follows [AlarmService.ringing] and leaves when it ends.
 */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val graph = NoveloApplication.graph
        setContent {
            val settings by graph.settings.flow.collectAsStateWithLifecycle(initialValue = NoveloSettings())
            val ringing by AlarmService.ringing.collectAsState()
            LaunchedEffect(ringing) { if (ringing == null) finish() }
            NoveloTheme(mode = settings.theme, accent = settings.accent) {
                ringing?.let { r ->
                    AlarmScreen(
                        r,
                        onStop = { send(AlarmService.ACTION_STOP) },
                        onSnooze = { send(AlarmService.ACTION_SNOOZE) },
                        onDone = if (r.task) ({ send(AlarmService.ACTION_DONE) }) else null,
                    )
                }
            }
        }
    }

    private fun send(action: String) {
        runCatching { startService(Intent(this, AlarmService::class.java).setAction(action)) }
        finish()
    }
}

/** What the alarm shows (a screen of its own, and the tests' prints). */
@Composable
fun AlarmScreen(r: Ringing, onStop: () -> Unit, onSnooze: () -> Unit, onDone: (() -> Unit)?) {
    val beat = rememberInfiniteTransition(label = "alarm")
    // The yarn breathes; a ring goes out from "Parar", over and over, softly.
    val breath by beat.animateFloat(1f, 1.07f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "breath")
    val wave by beat.animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Restart), label = "wave")
    Box(Modifier.fillMaxSize().paper()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.graphicsLayer { scaleX = breath; scaleY = breath }) { NoveloMark(size = 72.dp) }
            Spacer(Modifier.height(28.dp))
            Text(Dates.time(r.at), style = MaterialTheme.typography.displayLarge, color = NoveloColors.Text)
            Spacer(Modifier.height(10.dp))
            Text(
                r.title.ifBlank { t(R.string.alarm_meds) },
                style = MaterialTheme.typography.headlineSmall,
                color = NoveloColors.Text,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (r.text.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    r.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = NoveloColors.TextMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1.2f))
            Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(104.dp)
                        .graphicsLayer {
                            val grow = 1f + 0.42f * wave
                            scaleX = grow
                            scaleY = grow
                            alpha = 0.35f * (1f - wave)
                        }
                        .clip(CircleShape)
                        .background(NoveloColors.Accent),
                )
                Box(
                    Modifier
                        .size(104.dp)
                        .clip(CircleShape)
                        .background(NoveloColors.Accent)
                        .clickable(onClick = onStop)
                        .semantics { contentDescription = t(R.string.alarm_stop) }
                        .testTag("alarm-stop"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(t(R.string.alarm_stop), style = MaterialTheme.typography.titleLarge, color = NoveloColors.OnAccent)
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Quiet(t(R.string.alarm_snooze), "alarm-snooze", onSnooze)
                onDone?.let { Quiet(t(if (r.meds) R.string.med_taken_action else R.string.notif_done), "alarm-done", it) }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun Quiet(text: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(NoveloColors.Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp)
            .testTag(tag),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = NoveloColors.Text)
    }
}
