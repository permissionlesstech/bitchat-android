package com.bitchat.android.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.navigation3.runtime.NavKey
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject

/**
 * Single-stack navigator backed by a [SnapshotStateList] that NavDisplay observes.
 *
 * Scoped to [dagger.hilt.android.components.ActivityRetainedComponent], which is
 * what makes the back stack survive configuration changes — there is no
 * rememberNavBackStack here, and so no requirement that keys be serializable.
 *
 * The stack is *not* restored across process death. Nothing needs that yet:
 * onboarding re-derives its state on launch. When it is needed, the move is
 * kotlinx-serialization plus rememberNavBackStack, which is why keys are kept as
 * plain data objects that would be trivial to annotate.
 *
 * Deliberately single-stack. The multi-back-stack pattern exists to serve bottom
 * navigation; this app has no tabs, so it would be a map that only ever holds
 * one key.
 */
@Stable
@ActivityRetainedScoped
class AppNavigator @Inject constructor() : Navigator {

    val backStack: SnapshotStateList<NavKey> = mutableStateListOf()

    /**
     * Seeds the stack with [root] the first time only.
     *
     * Idempotent by design: an Activity recreated after a configuration change
     * re-runs its setup, and the retained scope still holds the real history.
     * Seeding again would discard it.
     */
    fun setRootIfEmpty(root: NavKey) {
        if (backStack.isEmpty()) backStack.add(root)
    }

    override fun goTo(dest: NavKey) {
        // A same-frame double tap pushes the same key twice. Dedup at the source
        // rather than guarding every call site.
        if (backStack.lastOrNull() != dest) backStack.add(dest)
    }

    override fun goBack(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    override fun popTo(route: NavKey, inclusive: Boolean): Boolean {
        val index = backStack.indexOfLast { it == route }
        if (index < 0) return false
        val target = if (inclusive) index else index + 1
        // Already at the requested route with inclusive = false pops nothing and
        // still succeeded.
        while (backStack.size > target) {
            backStack.removeAt(backStack.lastIndex)
        }
        return true
    }

    override fun replaceCurrent(dest: NavKey) {
        if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex)
        backStack.add(dest)
    }

    override fun resetTo(dest: NavKey) {
        backStack.clear()
        backStack.add(dest)
    }
}
