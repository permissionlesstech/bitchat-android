package com.bitchat.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A vertical list whose rows animate when they are added, removed, or reordered.
 *
 * Participant cards sit inside an outer lazy-list item. A bounded viewport lets the inner lazy
 * list virtualize rows safely, including when thousands of people are present. Small cards still
 * wrap their content; large cards scroll within the sheet without eagerly composing every member.
 *
 * Rows are keyed so identity survives reordering. Without stable keys a row that moved would look
 * like a different row appearing, and would fade instead of sliding.
 */
@Composable
fun <T> AnimatedRowColumn(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    row: @Composable (index: Int, item: T) -> Unit
) {
    LazyColumn(modifier = modifier.heightIn(max = 400.dp)) {
        itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
            Box(modifier = Modifier.animateItem()) {
                row(index, item)
            }
        }
    }
}
