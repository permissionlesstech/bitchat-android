package com.bitchat.android.identity.scope

import com.bitchat.android.panic.PanicMarker
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
    private val marker: PanicMarker,
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
     * Ends the current identity if one was built, runs [wipe] while no identity exists, and builds
     * the next one only if [wipe] returned true and no cleanup failed. Otherwise nothing is current
     * and [Replaced.built] is false: the next read builds an identity, unless a panic marker is
     * still pending, in which case it throws.
     *
     * Reads and this call share one lock, so a reader waits for the wipe and then gets the new
     * identity. A failed cleanup never stops [wipe]. If [wipe] or the build throws, nothing ended
     * stays current, and [generation] moves only once an identity is built after this.
     */
    fun replace(wipe: () -> Boolean): Replaced = synchronized(lock) {
        val old = component
        replacementOwed = true
        val failed = try {
            if (old == null) emptyList() else ender.end(old)
        } finally {
            component = null
            _ending.value = false
        }
        val wiped = wipe()
        val built = wiped && failed.isEmpty()
        if (built) buildNext()
        Replaced(failed, built)
    }

    private fun buildNext(): IdentityComponent {
        check(!marker.pending) { "identity wipe pending" }
        val built = builder.get().build()
        component = built
        if (replacementOwed) {
            replacementOwed = false
            _generation.value += 1
        }
        return built
    }
}

/** What [IdentityHolder.replace] did: the teardown cleanups that failed, and whether the next identity was built. */
data class Replaced(val failedCleanups: List<String>, val built: Boolean)

/** Reads [T], an `@EntryPoint @InstallIn(IdentityComponent::class)` interface, from the current identity. */
inline fun <reified T : Any> IdentityHolder.entryPoint(): T = EntryPoints.get(current, T::class.java)
