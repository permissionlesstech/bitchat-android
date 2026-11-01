package com.bitchat.android.testhook

import com.bitchat.android.onboarding.DeviceReadiness
import com.bitchat.android.onboarding.Readiness
import com.bitchat.android.onboarding.SystemEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.merge

/**
 * Fakes what onboarding reads from the device, for device tests. Debug builds
 * only.
 *
 * While [snapshot] is set it replaces the device's readiness whole: nothing is
 * read from the radios, battery or permissions, so a test never depends on the
 * device's own Bluetooth or location settings. Radio changes are sent with
 * [emit]; the device's own still arrive alongside them.
 *
 * Set it before the Activity launches and [clear] it when the test is done.
 */
object ReadinessOverride {

    @Volatile
    var snapshot: Readiness? = null

    // Two collectors listen while the app runs (the Activity and onboarding's
    // view model); the buffer lets a test emit without suspending.
    private val _events = MutableSharedFlow<SystemEvent>(extraBufferCapacity = 16)
    val events: Flow<SystemEvent> = _events

    /** Sends [event] to whoever collects the readiness's system events right now. */
    fun emit(event: SystemEvent) {
        check(_events.tryEmit(event)) { "ReadinessOverride event buffer full" }
    }

    fun clear() {
        snapshot = null
    }
}

/** The device's readiness, unless [ReadinessOverride] says otherwise. */
class OverridableDeviceReadiness(
    private val system: DeviceReadiness,
) : DeviceReadiness by system {

    override fun snapshot(): Readiness = ReadinessOverride.snapshot ?: system.snapshot()

    override val systemEvents: Flow<SystemEvent> =
        merge(system.systemEvents, ReadinessOverride.events)
}
