package com.bitchat.android.navigation

import androidx.navigation3.runtime.NavKey

/**
 * The navigation surface features are allowed to depend on.
 *
 * Features call this rather than touching the back stack directly, so the
 * guards below live in one place instead of at every call site.
 */
interface Navigator {

    /** Push [dest], unless it is already on top. */
    fun goTo(dest: NavKey)

    /** Pop one entry. Returns false when the stack cannot go back any further. */
    fun goBack(): Boolean

    /** Pop back to [route]. Returns false when [route] is not on the stack. */
    fun popTo(route: NavKey, inclusive: Boolean = false): Boolean

    /** Swap the current top for [dest], so Back skips the screen being replaced. */
    fun replaceCurrent(dest: NavKey)

    /** Clear the stack and start again at [dest]. */
    fun resetTo(dest: NavKey)
}
