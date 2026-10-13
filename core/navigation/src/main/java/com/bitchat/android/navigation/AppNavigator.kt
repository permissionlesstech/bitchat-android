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
 * The stack is restored across process death by MainActivity, which saves
 * [snapshot] through rememberSaveable, encoding it with NavKeySerializer into
 * a Bundle. Keys must therefore be @Serializable.
 *
 * Deliberately single-stack. The multi-back-stack pattern exists to serve bottom
 * navigation; this app has no tabs, so it would be a map that only ever holds
 * one key.
 */
@Stable
@ActivityRetainedScoped
class AppNavigator @Inject constructor() : Navigator {

    val backStack: SnapshotStateList<NavKey> = mutableStateListOf()

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

    /** The current stack, in a form the saved-state encoder can write to a Bundle. */
    fun snapshot(): ArrayList<NavKey> = ArrayList(backStack)

    /**
     * Replaces the stack with a restored one.
     *
     * An empty list is ignored rather than applied: a first launch has nothing
     * saved, and clearing the stack there would undo the seeding effect that
     * just ran.
     */
    fun restore(keys: List<NavKey>) {
        if (keys.isEmpty()) return
        backStack.clear()
        backStack.addAll(keys)
    }
}
