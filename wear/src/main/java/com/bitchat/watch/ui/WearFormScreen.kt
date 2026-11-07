package com.bitchat.watch.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

/**
 * Independently scrollable form items remain reachable on small watches and at large fonts.
 * The primary action, when present, is an [EdgeButton] hugging the bottom of the display: the
 * scaffold reserves its space so it is never cropped by the round edge. Short forms sit
 * centered above it, so the list is already at its end and the button shows at full size.
 */
@Composable
internal fun WearFormScreen(
    edgeButton: (@Composable BoxScope.() -> Unit)? = null,
    content: TransformingLazyColumnScope.() -> Unit,
) {
    val state = rememberTransformingLazyColumnState()
    val direction = LocalLayoutDirection.current
    val body: @Composable BoxScope.(androidx.compose.foundation.layout.PaddingValues) -> Unit =
        { padding ->
            TransformingLazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                contentPadding = padding.withAdditionalPadding(direction, horizontal = 14.dp),
                content = content,
            )
        }
    if (edgeButton != null) {
        ScreenScaffold(scrollState = state, edgeButton = edgeButton, content = body)
    } else {
        ScreenScaffold(scrollState = state, content = body)
    }
}

/** The primary action of a form screen, sized for one or two short words. */
@Composable
internal fun FormEdgeButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    EdgeButton(onClick = onClick, enabled = enabled, buttonSize = EdgeButtonSize.Medium) {
        Text(label, maxLines = 1)
    }
}

/** Icon, title and one short line: the shared head of every setup prompt. */
internal fun TransformingLazyColumnScope.formHeader(
    title: String,
    body: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
) {
    item(key = "form_header") {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint ?: MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 6.dp).size(26.dp),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            if (body != null) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
            }
        }
    }
}
