package com.villamediana.novelo.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.villamediana.novelo.R
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.ui.theme.NoveloColors

/**
 * "Tocar como alarme": a reminder (or a medicine's times) rings until she stops it, or is only a
 * notification. On by default; the line under it says what it does now.
 */
@Composable
fun AlarmToggle(on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onChange(!on) }
            .padding(vertical = 8.dp)
            .testTag("alarm-toggle"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (on) NoveloIcons.Alarm else NoveloIcons.Bell, null, tint = if (on) NoveloColors.Accent else NoveloColors.TextMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t(R.string.alarm_toggle), style = MaterialTheme.typography.titleSmall, color = NoveloColors.Text)
            Text(t(if (on) R.string.alarm_on_hint else R.string.alarm_off_hint), style = MaterialTheme.typography.bodySmall, color = NoveloColors.TextMuted)
        }
        Switch(
            checked = on,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = NoveloColors.Accent,
                checkedThumbColor = NoveloColors.OnAccent,
                uncheckedTrackColor = NoveloColors.Surface2,
                uncheckedThumbColor = NoveloColors.TextFaint,
                uncheckedBorderColor = NoveloColors.Surface2,
            ),
        )
    }
}
