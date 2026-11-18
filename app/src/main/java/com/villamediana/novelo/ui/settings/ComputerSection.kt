package com.villamediana.novelo.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.R
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.sync.SyncEngine
import com.villamediana.novelo.ui.Nav
import com.villamediana.novelo.ui.Screen
import com.villamediana.novelo.ui.components.Group
import com.villamediana.novelo.ui.components.IconAction
import com.villamediana.novelo.ui.components.NoveloIcons
import com.villamediana.novelo.ui.theme.NoveloColors
import kotlinx.coroutines.launch

/** Ajustes › Computador in a few words. */
internal fun computerSummary(on: Boolean, computers: Int, status: SyncEngine.Status): String = when {
    !on -> t(R.string.web_off)
    status.error != null && computers == 0 -> t(R.string.web_on)
    status.filesUp > 0 -> t(R.string.web_sending, status.filesUp)
    computers == 1 -> t(R.string.web_one)
    computers > 1 -> t(R.string.web_many, computers)
    else -> t(R.string.web_on)
}

/**
 * Ajustes › Computador: the Novelo on the computer, for writing. Link one (read its code), the ones
 * linked (each can be unlinked), how the sync is going, and stopping it all (the server's copy erased).
 */
@Composable
internal fun ComputerSection(nav: Nav) {
    val web = NoveloApplication.graph.web
    val computers by web.computers.collectAsStateWithLifecycle()
    val status by web.engine.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var stopping by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { web.refresh() }

    Text(t(R.string.web_about), style = MaterialTheme.typography.bodyMedium, color = NoveloColors.TextMuted, modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 12.dp))
    Group(
        Modifier,
        {
            Line(t(R.string.web_link_one), t(R.string.web_link_one_hint), onClick = { nav.go(Screen.Link()) }) {
                IconAction(NoveloIcons.Plus, t(R.string.web_link_one), { nav.go(Screen.Link()) }, primary = true, modifier = Modifier.testTag("web-link"))
            }
        },
        *computers.orEmpty().map { c ->
            @Composable {
                Line(c.name.ifBlank { t(R.string.web_computer) }, if (c.lastSeen > 0) t(R.string.web_seen, moment(c.lastSeen)) else null) {
                    IconAction(NoveloIcons.Close, t(R.string.web_unlink), { scope.launch { runCatching { web.unlink(c.id) } } })
                }
            }
        }.toTypedArray(),
    )
    if (web.on) {
        Spacer(Modifier.height(10.dp))
        val line = when {
            status.busy -> t(R.string.web_syncing)
            status.error != null -> t(R.string.web_sync_failed)
            status.filesUp > 0 -> t(R.string.web_files_waiting, status.filesUp)
            status.lastAt > 0 -> t(R.string.web_synced, moment(status.lastAt))
            else -> null
        }
        if (line != null) Text(line, style = MaterialTheme.typography.bodySmall, color = NoveloColors.TextFaint, modifier = Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(10.dp))
        Group(
            Modifier,
            {
                Line(
                    if (stopping) t(R.string.web_stop_sure) else t(R.string.web_stop),
                    t(R.string.web_stop_hint),
                    subtitleColor = NoveloColors.TextMuted,
                    onClick = {
                        if (!stopping) stopping = true else scope.launch {
                            runCatching { web.stop() }
                            stopping = false
                        }
                    },
                ) {
                    IconAction(NoveloIcons.Trash, t(R.string.web_stop), {
                        if (!stopping) stopping = true else scope.launch {
                            runCatching { web.stop() }
                            stopping = false
                        }
                    }, primary = stopping)
                }
            },
        )
    }
}
