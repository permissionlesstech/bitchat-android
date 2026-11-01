package com.bitchat.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import com.bitchat.android.ui.theme.BitchatMotion

/**
 * Counts that roll to their new value instead of snapping.
 *
 * Peer counts change on their own, without the user doing anything, so a hard digit swap is easy
 * to miss and looks like a rendering fault when it is noticed. Sliding the digits in the
 * direction the number moved — up when someone joins, down when someone leaves — conveys the
 * change without needing a separate indicator.
 */
@Composable
fun AnimatedCount(
    count: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    prefix: String = "",
) {
    AnimatedContent(
        targetState = count,
        transitionSpec = {
            val goingUp = targetState > initialState
            (
                slideInVertically(
                    animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
                    initialOffsetY = { height -> if (goingUp) height else -height }
                ) + fadeIn(tween(BitchatMotion.STANDARD_MS))
            ).togetherWith(
                slideOutVertically(
                    animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
                    targetOffsetY = { height -> if (goingUp) -height else height }
                ) + fadeOut(tween(BitchatMotion.QUICK_MS))
            // Clip so the outgoing digit cannot bleed past the text bounds mid-transition.
            ) using SizeTransform(clip = true)
        },
        modifier = modifier,
        label = "animatedCount"
    ) { value ->
        Text(
            text = "$prefix$value",
            style = style,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            maxLines = 1
        )
    }
}

/**
 * Cross-fades a label whose text embeds a count, e.g. `People (7)` or `3 people`.
 *
 * Used where the number is not isolated in its own composable and cannot be rolled on its own.
 * The transition is keyed on [count] rather than on [text], so a label changing for some other
 * reason — a locale switch, say — does not animate.
 */
@Composable
fun AnimatedCountLabel(
    count: Int,
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
) {
    AnimatedContent(
        targetState = count,
        transitionSpec = {
            fadeIn(tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing))
                .togetherWith(fadeOut(tween(BitchatMotion.QUICK_MS)))
                .using(SizeTransform(clip = false))
        },
        modifier = modifier,
        label = "animatedCountLabel"
    ) { state ->
        // Captured per state, so the outgoing copy keeps rendering the label it entered with.
        // Reading `text` directly would show the *new* label on both sides of the cross-fade,
        // turning the transition into a flicker between two identical strings.
        val stateText = remember(state) { text }
        Text(
            text = stateText,
            style = style,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            maxLines = 1
        )
    }
}
