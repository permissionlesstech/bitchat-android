package com.bitchat.android.identity.scope

import dagger.hilt.EntryPoints
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the process's current [IdentityComponent]. It is built on the first read and the same one
 * is returned afterwards, until [replace] ends it and builds the next.
 *
 * Reads and [replace] share one lock, so a reader never sees a component that is being ended.
 */
@Singleton
class IdentityHolder @Inject constructor(
    private val builder: Provider<IdentityComponentBuilder>,
    private val ender: IdentityEnder,
) {
    private val lock = Any()
    private var component: IdentityComponent? = null
    private val _generation = MutableStateFlow(0)

    /** Counts the identities ended so far; it changes after [replace] has built the next one. */
    val generation: StateFlow<Int> = _generation

    val current: IdentityComponent
        get() = synchronized(lock) { component ?: builder.get().build().also { component = it } }

    /**
     * Ends the current identity if one was built, builds the next and bumps [generation]. Returns
     * the names of the cleanups that failed; a failure never stops the replacement.
     */
    fun replace(): List<String> = synchronized(lock) {
        val old = component
        val failed = if (old == null) emptyList() else ender.end(old)
        component = builder.get().build()
        _generation.value += 1
        failed
    }
}

/** Reads [T], an `@EntryPoint @InstallIn(IdentityComponent::class)` interface, from the current identity. */
inline fun <reified T : Any> IdentityHolder.entryPoint(): T = EntryPoints.get(current, T::class.java)
