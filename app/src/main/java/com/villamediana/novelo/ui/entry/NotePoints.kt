package com.villamediana.novelo.ui.entry

import android.os.Build
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.villamediana.novelo.R
import com.villamediana.novelo.mind.Summary
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.ui.theme.NoveloColors
import com.villamediana.novelo.ui.theme.NoveloText

/**
 * "Em tópicos" (1.80): a long note's points, above its text — what was understood, to take in at a
 * glance; the text below stays hers. [hidden]: a sensitive note not shown yet, blurred with its text
 * (before Android 12, with no blur, not drawn). Nothing when the note has none.
 */
@Composable
internal fun NotePoints(summary: String, hidden: Boolean) {
    val points = remember(summary) { Summary.read(summary) }
    if (points.isEmpty() || (hidden && Build.VERSION.SDK_INT < 31)) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 18.dp)
            .then(if (hidden) Modifier.blur(10.dp) else Modifier)
            .clip(RoundedCornerShape(20.dp))
            .background(NoveloColors.Surface)
            .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 16.dp)
            .testTag("entry-points"),
    ) {
        Text(t(R.string.in_topics), style = NoveloText.Section, color = NoveloColors.TextMuted)
        Spacer(Modifier.height(8.dp))
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val accent = NoveloColors.Accent
                points.forEach { p ->
                    Row {
                        // The dot sits on the middle of the first line (23 sp high).
                        Box(Modifier.padding(top = 9.dp, end = 12.dp).size(5.dp).clip(CircleShape).background(accent))
                        Text(labeled(p), style = MaterialTheme.typography.bodyLarge, color = NoveloColors.Text)
                    }
                }
            }
        }
    }
}

/** "Local: a chácara do tio" with what it is about a little stronger. */
private fun labeled(point: String): AnnotatedString {
    val (head, rest) = Summary.label(point) ?: return AnnotatedString(point)
    return buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(head); append(':') }
        append(' ')
        append(rest)
    }
}
