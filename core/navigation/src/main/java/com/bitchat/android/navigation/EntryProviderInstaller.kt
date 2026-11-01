package com.bitchat.android.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey

/**
 * One feature's contribution to the navigation graph.
 *
 * Features provide these into a Set via Hilt multibindings, so the host
 * assembles every destination without importing any feature's internals.
 */
typealias EntryProviderInstaller = EntryProviderScope<NavKey>.() -> Unit
