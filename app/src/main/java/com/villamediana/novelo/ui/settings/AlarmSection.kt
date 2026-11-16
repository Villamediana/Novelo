package com.villamediana.novelo.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.R
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.ui.components.Group
import com.villamediana.novelo.ui.components.NoveloIcons
import com.villamediana.novelo.ui.theme.NoveloColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ajustes › Alarme: the sound the alarm rings with (Android's own sound picker, where she hears each
 * one, and can add her own), and whether what has a time is born ringing as an alarm or only
 * notifying (each one can still be changed on its own).
 */
@Composable
fun AlarmSection() {
    val graph = NoveloApplication.graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by graph.settings.now.collectAsStateWithLifecycle()
    val name = rememberSoundName(s.alarmSound)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        if (!data.hasExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)) return@rememberLauncherForActivityResult
        val picked = IntentCompat.getParcelableExtra(data, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        // "Padrão" (or nothing) is the phone's own; a sound this app can't read isn't kept (it would ring the phone's anyway).
        val keep = picked?.takeIf { it != AndroidSettings.System.DEFAULT_ALARM_ALERT_URI }
        if (keep != null && !playable(context, keep)) {
            Toast.makeText(context, t(R.string.alarm_sound_unplayable), Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        scope.launch { graph.settings.setAlarmSound(keep?.toString().orEmpty()) }
    }

    fun pick() {
        val current = s.alarmSound.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: AndroidSettings.System.DEFAULT_ALARM_ALERT_URI
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, t(R.string.alarm_sound))
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, AndroidSettings.System.DEFAULT_ALARM_ALERT_URI)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
        try {
            picker.launch(intent)
        } catch (_: ActivityNotFoundException) {
            // A phone without Android's sound picker: the phone's alarm sound stays.
        }
    }

    Group(
        Modifier,
        { Line(t(R.string.alarm_sound), name, onClick = ::pick, trailing = { Icon(NoveloIcons.Chevron, null, tint = NoveloColors.TextFaint, modifier = Modifier.size(18.dp)) }) },
        {
            Row(
                Modifier.fillMaxWidth()
                    .clickable { scope.launch { graph.settings.setAlarmByDefault(!s.alarmByDefault) } }
                    .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
                    .testTag("alarm-default"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(t(R.string.alarm_default), style = MaterialTheme.typography.titleMedium, color = NoveloColors.Text)
                    Text(
                        t(if (s.alarmByDefault) R.string.alarm_default_on else R.string.alarm_default_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = NoveloColors.TextMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Switch(
                    checked = s.alarmByDefault,
                    onCheckedChange = { on -> scope.launch { graph.settings.setAlarmByDefault(on) } },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = NoveloColors.Accent,
                        checkedThumbColor = NoveloColors.OnAccent,
                        uncheckedTrackColor = NoveloColors.Surface2,
                        uncheckedThumbColor = NoveloColors.TextFaint,
                        uncheckedBorderColor = NoveloColors.Surface2,
                    ),
                )
            }
        },
    )
}

/** Ajustes › Alarme in a few words: the sound, and whether new ones ring. */
@Composable
internal fun alarmSummary(sound: String, byDefault: Boolean): String =
    t(if (byDefault) R.string.alarm_summary_on else R.string.alarm_summary_off, rememberSoundName(sound))

/** The name of the alarm's sound, as Android calls it ("Oxygen"); the phone's own when none was chosen. */
@Composable
internal fun rememberSoundName(uri: String): String {
    val context = LocalContext.current
    val phone = t(R.string.alarm_sound_phone)
    val chosen = t(R.string.alarm_sound_chosen)
    val name by produceState(if (uri.isBlank()) phone else chosen, uri) {
        if (uri.isBlank()) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { RingtoneManager.getRingtone(context, Uri.parse(uri))?.getTitle(context) }.getOrNull()
        }?.takeIf { it.isNotBlank() } ?: chosen
    }
    return name
}

/** Whether this app can open [uri] to play it (a sound of hers in a folder it can't read can't). */
private fun playable(context: Context, uri: Uri): Boolean =
    runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
