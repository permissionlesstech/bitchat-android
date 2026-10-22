package com.bitchat.android.ui

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LookaheadScope

/**
 * Motion for a row moving to a new position, or resizing in place.
 *
 * People lists reorder themselves constantly and without user input — someone sends a DM and jumps
 * to the top, a peer drops off the mesh, a favourite comes online. Rows teleporting between
 * positions makes the list feel unreliable and costs the reader their place in it.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
private val RowBoundsTransform = BoundsTransform { _, _ ->
    spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = Rect.VisibilityThreshold
    )
}

/** Entry fade for a row that was not previously in the list. */
private val RowEnterSpec: FiniteAnimationSpec<Float> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 900f)

/**
 * A vertical list whose rows animate when they are added, removed, or reordered.
 *
 * Deliberately not a `LazyColumn`: both people lists live *inside* an outer `LazyColumn` item, where
 * nesting another lazy list is not possible. [LookaheadScope] plus [Modifier.animateBounds] gives
 * the same reorder-and-resize animation that `LazyItemScope.animateItem` provides, without the list
 * needing to be lazy. These lists are bounded (and the geohash one is explicitly capped), so
 * nothing is lost by composing every row.
 *
 * Rows are keyed so identity survives reordering. Without stable keys a row that moved would look
 * like a different row appearing, and would fade instead of sliding.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun <T> AnimatedRowColumn(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    row: @Composable (index: Int, item: T) -> Unit
) {
    LookaheadScope {
        Column(modifier = modifier) {
            items.forEachIndexed { index, item ->
                key(key(item)) {
                    // Fades the row in on the composition it first appears, then never again —
                    // reordering an existing row must slide, not blink.
                    val enter = remember { Animatable(0f) }
                    LaunchedEffect(Unit) { enter.animateTo(1f, RowEnterSpec) }

                    Box(
                        modifier = Modifier
                            .animateBounds(
                                lookaheadScope = this@LookaheadScope,
                                boundsTransform = RowBoundsTransform
                            )
                            .graphicsLayer { alpha = enter.value }
                    ) {
                        row(index, item)
                    }
                }
            }
        }
    }
}
