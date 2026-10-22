package com.bitchat.android.geohash

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide, fail-closed consent gate for accessing live device location.
 *
 * A generation token prevents callbacks that were started under an older consent
 * state from being accepted after location access is disabled or re-enabled.
 */
internal class LiveLocationAccessPolicy(
    initialEnabled: Boolean = DEFAULT_LIVE_LOCATION_ENABLED,
) {
    private val accessLock = ReentrantReadWriteLock()
    private val generation = AtomicLong(0L)
    private val _enabled = MutableStateFlow(initialEnabled)
    private var accessAvailable = initialEnabled

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    val isEnabled: Boolean
        get() = _enabled.value

    fun update(enabled: Boolean) {
        accessLock.write {
            generation.incrementAndGet()
            _enabled.value = enabled
            accessAvailable = enabled
        }
    }

    fun invalidate() {
        accessLock.write {
            generation.incrementAndGet()
            accessAvailable = false
        }
    }

    fun resumeAccess() {
        accessLock.write {
            if (_enabled.value && !accessAvailable) {
                generation.incrementAndGet()
                accessAvailable = true
            }
        }
    }

    fun captureToken(): Long? =
        accessLock.read {
            val capturedGeneration = generation.get()
            capturedGeneration.takeIf {
                _enabled.value &&
                    accessAvailable &&
                    generation.get() == capturedGeneration
            }
        }

    fun accepts(token: Long): Boolean =
        accessLock.read {
            _enabled.value && accessAvailable && generation.get() == token
        }

    fun runIfAllowed(token: Long, action: () -> Unit): Boolean =
        accessLock.read {
            if (!_enabled.value || !accessAvailable || generation.get() != token) {
                false
            } else {
                action()
                true
            }
        }
}

internal const val DEFAULT_LIVE_LOCATION_ENABLED = false

internal object LiveLocationPrivacyGate {
    private val policy = LiveLocationAccessPolicy()
    private val revocationListeners = CopyOnWriteArraySet<() -> Unit>()

    val enabled: StateFlow<Boolean> = policy.enabled
    val isEnabled: Boolean
        get() = policy.isEnabled

    fun update(enabled: Boolean) {
        policy.update(enabled)
        notifyRevoked()
    }

    fun invalidate() {
        policy.invalidate()
        notifyRevoked()
    }

    fun captureToken(): Long? = policy.captureToken()
    fun resumeAccess() = policy.resumeAccess()
    fun accepts(token: Long): Boolean = policy.accepts(token)
    fun runIfAllowed(token: Long, action: () -> Unit): Boolean =
        policy.runIfAllowed(token, action)

    fun addRevocationListener(listener: () -> Unit) {
        revocationListeners.add(listener)
    }

    fun removeRevocationListener(listener: () -> Unit) {
        revocationListeners.remove(listener)
    }

    private fun notifyRevoked() {
        revocationListeners.forEach { listener ->
            runCatching(listener)
        }
    }
}
