package com.villamediana.novelo.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.R
import com.villamediana.novelo.backup.Backups
import com.villamediana.novelo.backup.CloudBackup
import com.villamediana.novelo.backup.CloudCrypto
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.ui.components.Group
import com.villamediana.novelo.ui.components.Pill
import com.villamediana.novelo.ui.lock.Unlock
import com.villamediana.novelo.ui.lock.screen
import com.villamediana.novelo.ui.theme.NoveloColors
import kotlinx.coroutines.launch

/**
 * The backup in the cloud (Ajustes › Backup, and the first steps): on with one tap — the phone
 * makes the secret, keeps it in her Google account's safe, and the backups start going, encrypted.
 * A backup of hers already there (the app installed again, a new phone) is offered to come back;
 * so is one opened with the recovery code. Shown only when the server takes this phone's backup
 * (or it is already on).
 */
@Composable
fun CloudBackupBlock(modifier: Modifier = Modifier) {
    val graph = NoveloApplication.graph
    val backups = graph.backups
    val cloud = graph.cloud
    val context = LocalContext.current
    val s by graph.settings.now.collectAsStateWithLifecycle()
    val plan by graph.server.plan.collectAsStateWithLifecycle()
    val busy by backups.busy.collectAsStateWithLifecycle()
    val offered = graph.server.available && plan?.backup == true
    if (!offered && !s.cloud) return

    var working by remember { mutableStateOf(false) }
    var bringingBack by remember { mutableStateOf(false) }
    // What just happened (it came back; it didn't): the words, and whether it is a problem.
    var note by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var code by remember { mutableStateOf<String?>(null) }
    var safe by remember { mutableStateOf<Boolean?>(null) }
    var typing by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableStateOf<Pair<ByteArray, CloudBackup.Found>?>(null) }
    var replacing by remember { mutableStateOf<ByteArray?>(null) }
    var turningOff by remember { mutableStateOf(false) }

    // A backup of hers already in the cloud, its secret in her Google safe: offered to come back.
    LaunchedEffect(offered, s.cloud) {
        found = null
        if (offered && !s.cloud) {
            val seed = runCatching { cloud.known() }.getOrNull() ?: return@LaunchedEffect
            found = runCatching { cloud.find(seed) }.getOrNull()?.let { seed to it }
        }
    }

    // On the app's scope: leaving the screen never leaves a bringing-back halfway.
    fun bring(seed: ByteArray) {
        working = true
        bringingBack = true
        note = null
        graph.scope.launch {
            backups.cloudRestore(seed)
                .onSuccess { r ->
                    note = t(R.string.cloud_back, Backups.note(r.entries, r.photos, r.sounds, r.files)) to false
                    found = null
                    typing = false
                    typed = ""
                }
                .onFailure { note = (it.message ?: t(R.string.cloud_later)) to true }
            working = false
            bringingBack = false
        }
    }

    /** What the app has gives way to the backup: asked first, unless the app has nothing yet. */
    fun wantBack(seed: ByteArray) {
        graph.scope.launch { if (backups.empty()) bring(seed) else replacing = seed }
    }

    fun turnOn() {
        working = true
        note = null
        graph.scope.launch {
            // A backup of hers already there and nothing in the app (the app installed again, a new
            // phone): turning it on brings that backup back — a new one never takes its place.
            val seed = runCatching { cloud.known() }.getOrNull()
            if (seed != null) {
                val there = runCatching { cloud.find(seed) }
                if (there.isFailure) {
                    // Can't tell whether there is one (no network): nothing is turned on over it.
                    note = cloud.said(there.exceptionOrNull()!!) to true
                    working = false
                    return@launch
                }
                if (there.getOrNull() != null && backups.empty()) {
                    bring(seed)
                    return@launch
                }
            }
            safe = backups.cloudOn()
            code = cloud.code()
            working = false
        }
    }

    val (status, problem) = when {
        bringingBack || (s.cloud && busy == Backups.IMPORTING) -> t(R.string.backup_importing) to false
        (s.cloud && busy == Backups.SAVING) || (working && s.cloud) -> t(R.string.backup_doing) to false
        working -> t(R.string.cloud_looking) to false
        note != null -> note!!
        s.cloud && s.cloudFailed -> s.cloudNote to true
        s.cloud && s.cloudAt > 0 -> t(R.string.backup_last, moment(s.cloudAt)) to false
        else -> t(R.string.cloud_line) to false
    }

    Column(modifier.fillMaxWidth().animateContentSize()) {
        Group(
            Modifier,
            {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp).testTag("cloud-backup"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(t(R.string.cloud_title), style = MaterialTheme.typography.titleMedium, color = NoveloColors.Text)
                        Text(status, style = MaterialTheme.typography.bodySmall, color = if (problem) NoveloColors.Danger else NoveloColors.TextMuted)
                    }
                    if (working) CircularProgressIndicator(Modifier.padding(end = 12.dp).size(18.dp), color = NoveloColors.Accent, strokeWidth = 2.dp)
                    Switch(
                        checked = s.cloud,
                        onCheckedChange = { on -> if (on) turnOn() else turningOff = true },
                        enabled = !working && (offered || s.cloud),
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
            // The code that opens the backup anywhere: behind the phone's own lock.
            if (s.cloud) ({
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t(R.string.cloud_code), style = MaterialTheme.typography.titleMedium, color = NoveloColors.Text, modifier = Modifier.weight(1f))
                        val shown = code
                        if (shown == null) Pill(t(R.string.cloud_code_see), selected = false, onClick = {
                            val activity = context.screen()
                            graph.scope.launch {
                                if (activity != null && Unlock.available(context) && !Unlock.ask(activity)) return@launch
                                code = cloud.code()
                            }
                        }) else Pill(t(R.string.cloud_copy), selected = true, onClick = {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText(t(R.string.cloud_code), shown))
                            note = t(R.string.cloud_copied) to false
                        })
                    }
                    code?.let { c ->
                        Spacer(Modifier.height(8.dp))
                        Text(c, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace), color = NoveloColors.Text, modifier = Modifier.testTag("cloud-code"))
                        Spacer(Modifier.height(8.dp))
                        Text(t(R.string.cloud_code_line), style = MaterialTheme.typography.bodySmall, color = NoveloColors.TextMuted)
                        safe?.let {
                            Text(t(if (it) R.string.cloud_safe_yes else R.string.cloud_safe_no), style = MaterialTheme.typography.bodySmall, color = if (it) NoveloColors.TextMuted else NoveloColors.Accent, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }) else null,
        )

        // Off: a backup of hers that is already there, or one opened with the code.
        if (!s.cloud && !working) {
            found?.let { (seed, f) ->
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t(R.string.cloud_found, moment(f.at), Backups.note(f.entries, f.photos, f.sounds, f.files)),
                        style = MaterialTheme.typography.bodySmall, color = NoveloColors.TextMuted, modifier = Modifier.weight(1f).padding(end = 10.dp),
                    )
                    Pill(t(R.string.cloud_bring), selected = true, onClick = { wantBack(seed) })
                }
            }
            if (!typing) Text(
                t(R.string.cloud_have_code),
                style = MaterialTheme.typography.labelLarge,
                color = NoveloColors.TextMuted,
                modifier = Modifier.padding(top = 6.dp).clip(CircleShape).clickable { typing = true }.padding(horizontal = 6.dp, vertical = 8.dp).testTag("cloud-have-code"),
            ) else Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(NoveloColors.Surface2).padding(horizontal = 14.dp, vertical = 12.dp)) {
                    if (typed.isEmpty()) Text("XXXX-XXXX-XXXX-…", style = MaterialTheme.typography.bodyMedium, color = NoveloColors.TextFaint)
                    BasicTextField(
                        value = typed,
                        onValueChange = { typed = it.take(48) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = NoveloColors.Text, fontFamily = FontFamily.Monospace),
                        cursorBrush = SolidColor(NoveloColors.Accent),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.fillMaxWidth().testTag("cloud-code-field"),
                    )
                }
                Pill(t(R.string.cloud_bring), selected = true, onClick = {
                    val seed = CloudCrypto.seed(typed)
                    if (seed == null) note = t(R.string.cloud_bad_code) to true
                    else {
                        working = true
                        note = null
                        graph.scope.launch {
                            val there = runCatching { cloud.find(seed) }
                            working = false
                            when {
                                there.isFailure -> note = cloud.said(there.exceptionOrNull()!!) to true
                                there.getOrNull() == null -> note = t(R.string.cloud_none_for_code) to true
                                else -> wantBack(seed)
                            }
                        }
                    }
                })
            }
        }
    }

    replacing?.let { seed ->
        com.villamediana.novelo.ui.sheets.ConfirmDialog(
            title = t(R.string.cloud_replace_title),
            line = t(R.string.cloud_replace_line),
            yes = t(R.string.cloud_bring),
            onYes = { replacing = null; bring(seed) },
            onDismiss = { replacing = null },
        )
    }
    if (turningOff) {
        com.villamediana.novelo.ui.sheets.ConfirmDialog(
            title = t(R.string.cloud_off_title),
            line = t(R.string.cloud_off_line),
            yes = t(R.string.cloud_off_yes),
            onYes = {
                turningOff = false
                working = true
                code = null
                graph.scope.launch {
                    backups.cloudOff(erase = true).onFailure { note = (it.message ?: t(R.string.cloud_later)) to true }.onSuccess { note = null }
                    working = false
                }
            },
            onDismiss = { turningOff = false },
        )
    }
}
