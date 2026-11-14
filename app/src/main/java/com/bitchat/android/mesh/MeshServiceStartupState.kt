package com.bitchat.android.mesh

/** Tracks an asynchronous mesh start without treating the request itself as completion. */
internal class MeshServiceStartupState {
    internal enum class Phase {
        STOPPED,
        STARTING,
        ACTIVE,
        FAILED
    }

    private var phase = Phase.STOPPED
    private var generation = 0L

    val isActive: Boolean
        @Synchronized get() = phase == Phase.STARTING || phase == Phase.ACTIVE

    val isStarting: Boolean
        @Synchronized get() = phase == Phase.STARTING

    val isOperational: Boolean
        @Synchronized get() = phase == Phase.ACTIVE

    val isFailed: Boolean
        @Synchronized get() = phase == Phase.FAILED

    @Synchronized
    fun beginStart(): Long? {
        if (phase == Phase.STARTING || phase == Phase.ACTIVE) return null
        generation += 1
        phase = Phase.STARTING
        return generation
    }

    @Synchronized
    fun markStarted(attempt: Long): Boolean {
        if (attempt != generation || phase != Phase.STARTING) return false
        phase = Phase.ACTIVE
        return true
    }

    @Synchronized
    fun markFailed(attempt: Long): Boolean {
        if (attempt != generation || phase != Phase.STARTING) return false
        phase = Phase.FAILED
        return true
    }

    @Synchronized
    fun invalidateIfManagerStopped(managerActive: Boolean): Boolean {
        if (phase != Phase.ACTIVE || managerActive) return false
        generation += 1
        phase = Phase.FAILED
        return true
    }

    @Synchronized
    fun markStopped() {
        generation += 1
        phase = Phase.STOPPED
    }
}
