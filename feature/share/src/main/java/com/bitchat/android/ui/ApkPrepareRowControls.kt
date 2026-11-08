package com.bitchat.android.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * The progress readout for the prepare-for-sharing row.
 *
 * This sits under the row's subtitle so the trailing slot is free to hold a single control. Which
 * of the three renderings applies is decided entirely by [status]; the caller does not choose.
 *
 * Determinate transfer/resume progress and indeterminate non-transfer phases use the stable
 * Material 3 progress API. The expressive wavy variant can be introduced independently later.
 */
@Composable
internal fun ApkDownloadProgressBar(
    status: ApkPreparationStatus,
    progressPercent: Int,
    modifier: Modifier = Modifier
) {
    val barModifier = modifier
        .fillMaxWidth()
        .padding(top = 6.dp)

    when {
        // Only the transfer knows a fraction. Elsewhere an indeterminate bar is honest about
        // having no measure, the same distinction the subtitle already draws.
        status is ApkPreparationStatus.Downloading &&
            status.phase.hasMeasurableProgress &&
            progressPercent > 0 ->
            LinearProgressIndicator(
                progress = { progressPercent.asProgressFraction() },
                modifier = barModifier
            )

        status is ApkPreparationStatus.Downloading ->
            LinearProgressIndicator(modifier = barModifier)

        status is ApkPreparationStatus.Resumable ->
            LinearProgressIndicator(
                progress = { status.progressPercent.asProgressFraction() },
                modifier = barModifier
            )
    }
}

/** Percentages arrive from a worker across a process boundary, so they are not trusted to be 0..100. */
private fun Int.asProgressFraction(): Float = (this / 100f).coerceIn(0f, 1f)

/**
 * The single trailing control on the prepare row.
 *
 * Every status renders exactly one of these at the same width, so the text column beside it keeps
 * its measure and stops re-wrapping each time the status changes.
 *
 * These buttons carry no visible label, which makes [description] load-bearing rather than
 * decorative: it is both the TalkBack announcement and the long-press tooltip for a sighted user
 * who does not recognise the glyph.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ApkPrepareRowIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(description) } },
        state = rememberTooltipState(),
        modifier = modifier
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = if (enabled) tint else tint.copy(alpha = 0.38f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
