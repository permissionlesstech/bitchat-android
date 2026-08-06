package com.bitchat.android.core.ui.component.text

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow

internal data class ClickedAnnotation(
    val tag: String,
    val item: String,
)

internal fun findAnnotationAt(
    text: AnnotatedString,
    offset: Int,
    annotationTags: List<String>,
): ClickedAnnotation? {
    for (tag in annotationTags) {
        text.getStringAnnotations(tag = tag, start = offset, end = offset)
            .firstOrNull()
            ?.let { annotation ->
                return ClickedAnnotation(tag = tag, item = annotation.item)
            }
    }
    return null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AnnotatedClickableText(
    text: AnnotatedString,
    annotationTags: List<String>,
    onAnnotationClick: (tag: String, item: String) -> Boolean,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    color: Color = Color.Unspecified,
    fontFamily: FontFamily? = null,
    softWrap: Boolean = true,
    overflow: TextOverflow = TextOverflow.Clip,
    style: TextStyle = LocalTextStyle.current,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val currentOnAnnotationClick by rememberUpdatedState(onAnnotationClick)
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    Text(
        text = text,
        modifier = modifier.pointerInput(text, annotationTags, onLongPress != null) {
            detectTapGestures(
                onTap = { position ->
                    val offset = layoutResult
                        ?.getOffsetForPosition(position)
                        ?: return@detectTapGestures

                    var remainingTags = annotationTags
                    while (remainingTags.isNotEmpty()) {
                        val annotation = findAnnotationAt(
                            text = text,
                            offset = offset,
                            annotationTags = remainingTags,
                        ) ?: break
                        if (currentOnAnnotationClick(annotation.tag, annotation.item)) {
                            return@detectTapGestures
                        }
                        remainingTags = remainingTags.drop(
                            remainingTags.indexOf(annotation.tag) + 1
                        )
                    }
                },
                onLongPress = currentOnLongPress?.let { callback ->
                    { callback() }
                },
            )
        },
        color = color,
        fontFamily = fontFamily,
        softWrap = softWrap,
        overflow = overflow,
        style = style,
        onTextLayout = { result ->
            layoutResult = result
            onTextLayout?.invoke(result)
        },
    )
}
