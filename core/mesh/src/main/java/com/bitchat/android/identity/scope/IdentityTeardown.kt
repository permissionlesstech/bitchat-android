package com.bitchat.android.identity.scope

import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import javax.inject.Inject

/**
 * The cleanups an identity's classes registered for what lives outside its coroutine scope
 * (listeners, receivers, process-wide registries). One per identity; [runAll] runs them when the
 * identity ends.
 */
@IdentityScoped
class IdentityTeardown @Inject constructor() {
    private val lock = Any()
    private val cleanups = ArrayList<Pair<String, () -> Unit>>()
    private var ran = false

    /**
     * Registers [cleanup] to run when the identity ends. Cleanups run newest first.
     *
     * A cleanup runs while the holder's lock is held, so it must not read [IdentityHolder] (or
     * anything that does): that would deadlock. Capture what it needs when it is registered.
     *
     * Registering after the identity has ended runs [cleanup] at once, so a late registration
     * does not outlive the identity.
     */
    fun register(name: String, cleanup: () -> Unit) {
        val runNow = synchronized(lock) {
            if (ran) true else {
                cleanups += name to cleanup
                false
            }
        }
        if (runNow) runCatching(cleanup)
    }

    /**
     * Runs every cleanup, newest first. A cleanup that throws does not stop the others. Returns
     * the names of the ones that failed. Runs once; later calls do nothing and return empty.
     */
    fun runAll(): List<String> {
        val pending = synchronized(lock) {
            if (ran) return emptyList()
            ran = true
            cleanups.toList().also { cleanups.clear() }
        }
        val failed = ArrayList<String>()
        for ((name, cleanup) in pending.asReversed()) {
            try {
                cleanup()
            } catch (_: Throwable) {
                failed += name
            }
        }
        return failed
    }
}

/** What ending an identity needs from inside its component. */
@EntryPoint
@InstallIn(IdentityComponent::class)
interface IdentityLifetimeEntryPoint {
    fun teardown(): IdentityTeardown

    @IdentityCoroutineScope
    fun scope(): CoroutineScope
}

/** Ends an identity: [IdentityHolder.replace] calls it on the component it is retiring. */
fun interface IdentityEnder {
    /** Returns the names of the cleanups that failed. */
    fun end(component: IdentityComponent): List<String>
}

/** Runs the identity's cleanups, then cancels its coroutine scope. */
class DefaultIdentityEnder @Inject constructor() : IdentityEnder {
    override fun end(component: IdentityComponent): List<String> {
        val lifetime = EntryPoints.get(component, IdentityLifetimeEntryPoint::class.java)
        val failed = lifetime.teardown().runAll()
        lifetime.scope().cancel()
        return failed
    }
}
