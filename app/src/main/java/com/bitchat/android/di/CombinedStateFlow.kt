package com.bitchat.android.di

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * A StateFlow computed from other StateFlows, with no scope of its own: [value]
 * reads the sources each time, and collecting combines them. So a reader sees
 * the sources' current values exactly, as if it had read each one itself.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class CombinedStateFlow<T>(
    private val read: () -> T,
    private val changes: Flow<T>,
) : StateFlow<T> {
    override val value: T get() = read()
    override val replayCache: List<T> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        changes.distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}

internal fun <A, B, R> combineStates(
    a: StateFlow<A>,
    b: StateFlow<B>,
    transform: (A, B) -> R,
): StateFlow<R> = CombinedStateFlow(
    read = { transform(a.value, b.value) },
    changes = combine(a, b) { x, y -> transform(x, y) },
)

internal fun <A, B, C, D, R> combineStates(
    a: StateFlow<A>,
    b: StateFlow<B>,
    c: StateFlow<C>,
    d: StateFlow<D>,
    transform: (A, B, C, D) -> R,
): StateFlow<R> = CombinedStateFlow(
    read = { transform(a.value, b.value, c.value, d.value) },
    changes = combine(a, b, c, d) { w, x, y, z -> transform(w, x, y, z) },
)
