package com.bitchat.android.ui

import com.bitchat.android.di.ChatSessionBuilderEntryPoint
import com.bitchat.android.di.ChatSessionComponent
import com.bitchat.android.identity.scope.IdentityHolder
import com.bitchat.android.identity.scope.entryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the current [ChatSessionComponent]. It is built from the current identity on the first read
 * and ended when the retained lifecycle clears, which is the person leaving the app, or when a
 * panic calls [end] and [replace].
 *
 * Reads, [end] and [replace] share one lock, so a reader never sees a session that is being ended.
 */
@ActivityRetainedScoped
class ChatSessionHolder @Inject constructor(
    private val identity: IdentityHolder,
    lifecycle: ActivityRetainedLifecycle,
    private val ender: ChatSessionEnder,
) {
    private val lock = Any()
    private var session: ChatSessionComponent? = null
    private var builtFromIdentity = 0
    private var sessionsBuilt = 0
    private var cleared = false
    private val _generation = MutableStateFlow(0)

    /** Counts the sessions built after the first; it changes once the next session exists. */
    val generation: StateFlow<Int> = _generation

    init {
        lifecycle.addOnClearedListener { onCleared() }
    }

    /**
     * The current session, built if there is none or if the identity it came from has ended.
     *
     * After the retained lifecycle cleared this is the ended session, built and ended on the spot
     * if none existed. A late callback into a retained object keeps working on that dead session,
     * as it worked on a cancelled retained object, and no live session outlasts the lifecycle.
     */
    val current: ChatSessionComponent
        get() = synchronized(lock) {
            val built = session
            when {
                cleared -> built ?: build().also(ender::end)
                built != null && builtFromIdentity == identity.generation.value -> built
                else -> {
                    endCurrent()
                    build()
                }
            }
        }

    /** Ends the session if one is live. The next read builds a new one. */
    fun end() = synchronized(lock) {
        if (!cleared) endCurrent()
    }

    /**
     * Ends the session if one is live, builds the next from the current identity and returns true.
     * Once the retained lifecycle has cleared nobody is left to use a session, so it builds nothing
     * and returns false.
     */
    fun replace(): Boolean = synchronized(lock) {
        if (cleared) return false
        endCurrent()
        build()
        true
    }

    private fun onCleared() = synchronized(lock) {
        cleared = true
        session?.let(ender::end)
    }

    /** Forgets the session before ending it, so an ender that throws cannot leave it current. */
    private fun endCurrent() {
        val old = session ?: return
        session = null
        ender.end(old)
    }

    private fun build(): ChatSessionComponent {
        // Read before the identity: if a replacement lands in between, the session looks older
        // than it is and the next read rebuilds it. The other order would pin it to an ended identity.
        val generation = identity.generation.value
        val built = identity.entryPoint<ChatSessionBuilderEntryPoint>().chatSessionBuilder().build()
        session = built
        builtFromIdentity = generation
        if (sessionsBuilt++ > 0) _generation.value += 1
        return built
    }
}

/** Ends a session: [ChatSessionHolder] calls it on the session it is retiring. */
fun interface ChatSessionEnder {
    fun end(component: ChatSessionComponent)
}

/** Reads [T], an `@EntryPoint @InstallIn(ChatSessionComponent::class)` interface, from the current session. */
inline fun <reified T : Any> ChatSessionHolder.entryPoint(): T = EntryPoints.get(current, T::class.java)
