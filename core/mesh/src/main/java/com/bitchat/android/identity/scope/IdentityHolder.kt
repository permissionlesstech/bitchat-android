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
    private var replacementOwed = false
    private val _generation = MutableStateFlow(0)
    private val _ending = MutableStateFlow(false)

    /** Counts the identities ended so far; it changes after [replace] has built the next one. */
    val generation: StateFlow<Int> = _generation

    /**
     * True from when a panic starts on this identity until it is replaced or the panic keeps it.
     * Nothing new should start on an identity while this is set, because it is being wiped.
     */
    val ending: StateFlow<Boolean> = _ending

    val current: IdentityComponent
        get() = synchronized(lock) { component ?: buildNext() }

    /** Says that this identity is being wiped. [replace] or [cancelEnding] ends that. */
    fun beginEnding() {
        _ending.value = true
    }

    /** The wipe failed and this identity stays, as it does when the conversation database cannot be erased. */
    fun cancelEnding() {
        _ending.value = false
    }

    /**
     * Ends the current identity if one was built, builds the next and bumps [generation]. Returns
     * the names of the cleanups that failed; a failure never stops the replacement.
     *
     * If the build throws, nothing ended stays current: the next read builds the identity and only
     * then does [generation] move, so what follows it never moves onto an identity that is not there.
     */
    fun replace(): List<String> = synchronized(lock) {
        val old = component
        replacementOwed = true
        val failed = try {
            if (old == null) emptyList() else ender.end(old)
        } finally {
            component = null
            _ending.value = false
        }
        buildNext()
        failed
    }

    private fun buildNext(): IdentityComponent {
        val built = builder.get().build()
        component = built
        if (replacementOwed) {
            replacementOwed = false
            _generation.value += 1
        }
        return built
    }
}

/** Reads [T], an `@EntryPoint @InstallIn(IdentityComponent::class)` interface, from the current identity. */
inline fun <reified T : Any> IdentityHolder.entryPoint(): T = EntryPoints.get(current, T::class.java)
