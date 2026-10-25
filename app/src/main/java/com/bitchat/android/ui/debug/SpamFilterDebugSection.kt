package com.bitchat.android.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.spam.SpamFilter
import com.bitchat.android.spam.SpamFilterPreferences
import com.bitchat.android.spam.SpamTuning
import com.bitchat.android.ui.theme.BitchatFontFamily
import kotlin.math.roundToInt

/** Debug controls for the spam filter: live limit tuning and the mark-instead-of-hide bypass. */
@Composable
fun SpamFilterDebugSection() {
    val colorScheme = MaterialTheme.colorScheme
    val filterEnabled by SpamFilterPreferences.enabled.collectAsState()
    val bypass by SpamFilterPreferences.debugBypass.collectAsState()
    val tuning by SpamFilterPreferences.tuning.collectAsState()
    val flagged by SpamFilter.shared.flagged.collectAsState()
    val update: (SpamTuning) -> Unit = SpamFilterPreferences::setTuning

    Surface(shape = RoundedCornerShape(12.dp), color = colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.FilterAlt, contentDescription = null, tint = colorScheme.error)
                Text("Spam filter", fontFamily = BitchatFontFamily, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Switch(checked = filterEnabled, onCheckedChange = SpamFilterPreferences::setEnabled)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Show spam in red instead of hiding it", fontFamily = BitchatFontFamily, fontSize = 12.sp)
                    Text(
                        "Bypass for debugging. ${flagged.size} marked since the last reset or limit change.",
                        fontFamily = BitchatFontFamily,
                        fontSize = 11.sp,
                        color = colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                Switch(checked = bypass, onCheckedChange = SpamFilterPreferences::setDebugBypass, enabled = filterEnabled)
            }

            Text(
                "Limits per sender. Changing one starts every sender with a full budget.",
                fontFamily = BitchatFontFamily,
                fontSize = 11.sp,
                color = colorScheme.onSurface.copy(alpha = 0.7f)
            )
            TuningSlider("Message burst", "${tuning.messageBurst} messages", tuning.messageBurst, 1..30) {
                update(tuning.copy(messageBurst = it))
            }
            TuningSlider("Message refill", "1 every ${tuning.secondsPerMessage} s", tuning.secondsPerMessage, 1..60) {
                update(tuning.copy(secondsPerMessage = it))
            }
            TuningSlider("Characters", "${tuning.charactersPerMinute} per minute", tuning.charactersPerMinute, 100..3000, step = 50) {
                update(tuning.copy(charactersPerMinute = it))
            }
            TuningSlider("Links", "${tuning.linksPerMinute} per minute", tuning.linksPerMinute, 0..10) {
                update(tuning.copy(linksPerMinute = it))
            }
            TuningSlider("Repeats allowed", "${tuning.allowedRepeats} near-copies", tuning.allowedRepeats, 0..10) {
                update(tuning.copy(allowedRepeats = it))
            }
            TuningSlider("Copies from other users allowed", "${tuning.crossUserAllowedRepeats} near-copies", tuning.crossUserAllowedRepeats, 0..10) {
                update(tuning.copy(crossUserAllowedRepeats = it))
            }
            TuningSlider("Compare across users from", "${tuning.crossUserMinLength} chars", tuning.crossUserMinLength, 4..100) {
                update(tuning.copy(crossUserMinLength = it))
            }
            TuningSlider("Near-copy match", "≥ ${tuning.repeatSimilarityPercent}%", tuning.repeatSimilarityPercent, 50..100) {
                update(tuning.copy(repeatSimilarityPercent = it))
            }
            TuningSlider("Repeat window", "${tuning.repeatWindowMinutes} min", tuning.repeatWindowMinutes, 1..60) {
                update(tuning.copy(repeatWindowMinutes = it))
            }
            TuningSlider("Repeated characters", "≥ ${tuning.selfMatchPercent}% self-match", tuning.selfMatchPercent, 50..100) {
                update(tuning.copy(selfMatchPercent = it))
            }
            TuningSlider("Repeated characters from", "${tuning.selfMatchMinLength} chars", tuning.selfMatchMinLength, 8..200, step = 4) {
                update(tuning.copy(selfMatchMinLength = it))
            }

            Row {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { update(SpamTuning.DEFAULT) }, enabled = tuning != SpamTuning.DEFAULT) {
                    Text("Reset to defaults", fontFamily = BitchatFontFamily, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun TuningSlider(
    label: String,
    valueText: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    onChange: (Int) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Column {
        Row {
            Text(label, fontFamily = BitchatFontFamily, fontSize = 11.sp, color = colorScheme.onSurface.copy(alpha = 0.7f))
            Spacer(Modifier.weight(1f))
            Text(valueText, fontFamily = BitchatFontFamily, fontSize = 11.sp)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { raw ->
                val snapped = range.first + ((raw - range.first) / step).roundToInt() * step
                onChange(snapped.coerceIn(range))
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step - 1).coerceAtLeast(0)
        )
    }
}
