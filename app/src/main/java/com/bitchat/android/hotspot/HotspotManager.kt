package com.bitchat.android.hotspot

import android.Manifest
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * Manages the Wi-Fi P2P group used for offline APK sharing.
 *
 * The Android API is command-oriented: action listeners acknowledge that create/remove
 * commands were accepted, while group state changes arrive later. This class therefore
 * keeps one explicit lifecycle phase and only completes teardown after observing that the
 * owned group is absent (or has been replaced).
 */
class HotspotManager @VisibleForTesting internal constructor(
    private val p2p: HotspotP2p,
    private val platform: HotspotPlatform,
    private val scheduler: HotspotScheduler,
    private val ownedGroups: OwnedGroupStore,
    private val random: SecureRandom = SecureRandom(),
    private val accessPointAddress: () -> String? = ::findAccessPointAddress
) {
    constructor(context: android.content.Context) : this(
        p2p = AndroidHotspotP2p(context.applicationContext),
        platform = AndroidHotspotPlatform(context.applicationContext),
        scheduler = HandlerHotspotScheduler(),
        ownedGroups = SharedPreferencesOwnedGroupStore(context.applicationContext)
    )

    companion object {
        private const val TAG = "HotspotMgr"

        private const val GROUP_INFO_POLL_INTERVAL_MILLIS = 1_000L
        private const val REMOVAL_VERIFY_INTERVAL_MILLIS = 500L
        private const val PREFLIGHT_REQUEST_TIMEOUT_MILLIS = 10_000L
        private const val GROUP_FORMATION_TIMEOUT_MILLIS = 15_000L
        private const val STALE_REMOVAL_TIMEOUT_MILLIS = 10_000L
        private const val TEARDOWN_FORCE_CLOSE_MILLIS = 10_000L
        private const val CHANNEL_RECOVERY_RETRY_MILLIS = 1_000L
        private const val MAX_CHANNEL_RECOVERY_ATTEMPTS = 5
        private const val REQUIRED_ABSENT_OBSERVATIONS = 2

        private const val SSID_SUFFIX_LENGTH = 8
        private const val PASSWORD_LENGTH = 16
        private const val RANDOM_CHARS = "ABCDEFGHJKLMNPQRTUVWXY34679"

        private const val PERMISSION_REVOKED_MESSAGE =
            "A required Wi-Fi or local network permission was revoked. Grant it and try again."
        private const val GROUP_LOST_MESSAGE =
            "The Wi-Fi Direct hotspot disconnected. Please try again."
        private const val PREFLIGHT_TIMEOUT_MESSAGE =
            "Wi-Fi Direct did not respond. Please try again."

        private fun findAccessPointAddress(): String? {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val network = interfaces.nextElement()
                    if (!network.name.startsWith("p2p")) continue
                    network.interfaceAddresses
                        .firstOrNull { it.address.address.size == 4 }
                        ?.address
                        ?.hostAddress
                        ?.let { return it }
                }
                null
            } catch (e: Exception) {
                Log.e(TAG, "Error getting P2P access point address", e)
                null
            }
        }
    }

    private sealed interface Phase {
        data object Idle : Phase

        data class Starting(
            val session: Session,
            val step: StartStep
        ) : Phase

        data class Creating(
            val session: Session,
            val attempt: Int,
            val ownership: Ownership
        ) : Phase

        data class Forming(
            val session: Session,
            val ownership: Ownership,
            val candidateName: String? = null,
            val candidateObservations: Int = 0
        ) : Phase

        data class Hosting(
            val session: Session,
            val ownership: Ownership.Known,
            val group: HotspotP2p.Group,
            val absentObservations: Int = 0
        ) : Phase

        data class Stopping(
            val session: Session,
            val cleanup: Cleanup,
            val pendingError: String?
        ) : Phase

        data object Closed : Phase
    }

    private sealed interface StartStep {
        data object AwaitingP2pState : StartStep
        data class Inspecting(val attempt: Int) : StartStep

        data class RemovingStale(
            val attempt: Int,
            val groupName: String
        ) : StartStep

        data class AwaitingStaleAbsence(
            val attempt: Int,
            val groupName: String,
            val removalAccepted: Boolean,
            val absentObservations: Int = 0
        ) : StartStep
    }

    private sealed interface Ownership {
        data class Known(val name: String) : Ownership

        /**
         * Android 8-9 chooses the group name. The accepted create command is the only
         * ownership evidence until the first stable group observation supplies the name.
         */
        data object FrameworkGenerated : Ownership
    }

    private sealed interface Cleanup {
        data class AwaitingCreateResult(
            val ownership: Ownership
        ) : Cleanup

        data class Inspecting(
            val ownership: Ownership,
            val mayStillForm: Boolean,
            val removalAccepted: Boolean = false,
            val absentObservations: Int = 0,
            val forced: Boolean = false,
            val candidateName: String? = null,
            val candidateObservations: Int = 0
        ) : Cleanup

        data class Removing(
            val ownership: Ownership,
            val mayStillForm: Boolean,
            val forced: Boolean
        ) : Cleanup

        data class RecoveringChannel(
            val ownership: Ownership,
            val attempt: Int
        ) : Cleanup
    }

    private class Session(
        val id: Long,
        var channel: HotspotP2p.Channel,
        val callback: HotspotCallback,
        val credentials: HotspotP2p.Credentials
    ) {
        val tasks = mutableListOf<HotspotScheduler.Task>()
        val teardownCallbacks = mutableListOf<() -> Unit>()
        var channelClosed = false
        var channelGeneration = 0L
        var cleanupRecoveryAttempt = 0

        fun closeChannel() {
            if (channelClosed) return
            channelClosed = true
            channel.close()
        }
    }

    private var phase: Phase = Phase.Idle
    private var nextSessionId = 1L
    private var lastP2pState: Int? = null

    fun startHotspot(callback: HotspotCallback) {
        if (phase !is Phase.Idle && phase !is Phase.Closed) {
            Log.w(TAG, "Ignoring start while lifecycle is ${phase.javaClass.simpleName}")
            return
        }

        if (!p2p.available) {
            callback.onError("Wi-Fi Direct not supported on this device")
            return
        }

        val missingPermissions = platform.missingPermissions()
        if (missingPermissions.isNotEmpty()) {
            val message = if (Manifest.permission.ACCESS_LOCAL_NETWORK in missingPermissions) {
                "Local network permission is required to share the app over the hotspot"
            } else {
                "Nearby Wi-Fi permission is required to start the hotspot"
            }
            callback.onError(message)
            return
        }

        try {
            platform.activate(
                onP2pStateChanged = ::onP2pStateChanged,
                onConnectionChanged = ::onConnectionChanged
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unable to acquire hotspot resources", e)
            try {
                platform.deactivate()
            } catch (cleanupError: Exception) {
                Log.w(TAG, "Unable to release partially acquired hotspot resources", cleanupError)
            }
            callback.onError("Unable to prepare the Wi-Fi Direct hotspot")
            return
        }

        val session = createSession(callback) ?: run {
            platform.deactivate()
            callback.onError(HotspotStartupPolicy.P2P_UNSUPPORTED_MESSAGE)
            return
        }

        Log.d(TAG, "Starting hotspot session ${session.id}")
        if (p2p.supportsP2pStateQuery) {
            requestP2pState(session)
        } else {
            inspectBeforeCreate(session, attempt = 1)
        }
    }

    /**
     * Stop is idempotent. Every caller waits on the same [Phase.Stopping] lifecycle
     * instead of overwriting a single completion callback.
     */
    fun stopHotspot(onTeardownComplete: (() -> Unit)? = null) {
        when (val current = phase) {
            Phase.Idle, Phase.Closed -> onTeardownComplete?.invoke()

            is Phase.Stopping -> {
                onTeardownComplete?.let(current.session.teardownCallbacks::add)
            }

            is Phase.Starting -> {
                onTeardownComplete?.let(current.session.teardownCallbacks::add)
                when (val step = current.step) {
                    is StartStep.RemovingStale ->
                        beginStopping(
                            session = current.session,
                            cleanup = Cleanup.Inspecting(
                                ownership = Ownership.Known(step.groupName),
                                mayStillForm = false
                            ),
                            pendingError = null
                        )

                    is StartStep.AwaitingStaleAbsence ->
                        beginStopping(
                            session = current.session,
                            cleanup = Cleanup.Inspecting(
                                ownership = Ownership.Known(step.groupName),
                                mayStillForm = false,
                                removalAccepted = step.removalAccepted,
                                absentObservations = step.absentObservations
                            ),
                            pendingError = null
                        )

                    else -> finishSession(current.session)
                }
            }

            is Phase.Creating -> {
                onTeardownComplete?.let(current.session.teardownCallbacks::add)
                beginStopping(
                    session = current.session,
                    cleanup = Cleanup.AwaitingCreateResult(current.ownership),
                    pendingError = null
                )
            }

            is Phase.Forming -> {
                onTeardownComplete?.let(current.session.teardownCallbacks::add)
                beginStopping(
                    session = current.session,
                    cleanup = Cleanup.Inspecting(
                        ownership = current.ownership,
                        mayStillForm = true
                    ),
                    pendingError = null
                )
            }

            is Phase.Hosting -> {
                onTeardownComplete?.let(current.session.teardownCallbacks::add)
                beginStopping(
                    session = current.session,
                    cleanup = Cleanup.Inspecting(
                        ownership = current.ownership,
                        mayStillForm = false
                    ),
                    pendingError = null
                )
            }
        }
    }

    fun getConnectionInfo(): ConnectionInfo? {
        val hosting = phase as? Phase.Hosting ?: return null
        return connectionInfo(hosting.group, hosting.session)
    }

    @VisibleForTesting
    internal fun lifecycleName(): String = when (phase) {
        Phase.Idle -> "Idle"
        is Phase.Starting -> "Starting"
        is Phase.Creating -> "Creating"
        is Phase.Forming -> "Forming"
        is Phase.Hosting -> "Hosting"
        is Phase.Stopping -> "Stopping"
        Phase.Closed -> "Closed"
    }

    private fun createSession(callback: HotspotCallback): Session? {
        var session: Session? = null
        val generation = 0L
        val channel = try {
            p2p.initialize {
                session?.let { onChannelDisconnected(it, generation) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize P2P channel", e)
            null
        } ?: return null

        val created = Session(
            id = nextSessionId++,
            channel = channel,
            callback = callback,
            credentials = HotspotP2p.Credentials(
                networkName = generateSsid(),
                passphrase = generatePassword()
            )
        )
        session = created
        return created
    }

    private fun requestP2pState(session: Session) {
        val expected = Phase.Starting(session, StartStep.AwaitingP2pState)
        phase = expected
        schedulePreflightDeadline(expected)
        try {
            p2p.requestP2pState(session.channel) { state ->
                if (phase !== expected) return@requestP2pState
                lastP2pState = state
                if (state == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                    fail(HotspotStartupPolicy.P2P_DISABLED_MESSAGE)
                } else {
                    inspectBeforeCreate(session, attempt = 1)
                }
            }
        } catch (e: SecurityException) {
            fail(PERMISSION_REVOKED_MESSAGE)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed to request P2P state", e)
            fail(HotspotStartupPolicy.GENERIC_FAILURE_MESSAGE)
        }
    }

    private fun inspectBeforeCreate(session: Session, attempt: Int) {
        val expected = Phase.Starting(session, StartStep.Inspecting(attempt))
        phase = expected
        schedulePreflightDeadline(expected)
        try {
            p2p.requestGroup(session.channel) { group ->
                if (phase !== expected) return@requestGroup
                if (group != null && !group.isGroupOwner) {
                    fail(HotspotStartupPolicy.FOREIGN_GROUP_MESSAGE)
                    return@requestGroup
                }
                when (
                    val action = HotspotStartupPolicy.startAction(
                        p2pState = lastP2pState,
                        existingGroupName = group?.networkName,
                        ownedGroupName = ownedGroups.name
                    )
                ) {
                    is HotspotStartupPolicy.StartAction.Fail -> fail(action.message)
                    HotspotStartupPolicy.StartAction.Create -> createGroup(session, attempt)
                    HotspotStartupPolicy.StartAction.RemoveStaleGroupThenCreate -> {
                        val name = group?.networkName ?: ownedGroups.name
                        if (name == null) {
                            createGroup(session, attempt)
                        } else {
                            removeStaleGroup(session, attempt, name)
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            fail(PERMISSION_REVOKED_MESSAGE)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed to inspect the current P2P group", e)
            fail(HotspotStartupPolicy.GENERIC_FAILURE_MESSAGE)
        }
    }

    private fun schedulePreflightDeadline(expected: Phase.Starting) {
        schedule(expected.session, PREFLIGHT_REQUEST_TIMEOUT_MILLIS) {
            if (phase === expected) {
                fail(PREFLIGHT_TIMEOUT_MESSAGE)
            }
        }
    }

    private fun removeStaleGroup(session: Session, attempt: Int, groupName: String) {
        val expected = Phase.Starting(
            session,
            StartStep.RemovingStale(attempt, groupName)
        )
        phase = expected
        scheduleStaleRemovalDeadline(session, groupName)

        try {
            p2p.removeGroup(session.channel, object : HotspotP2p.ActionCallback {
                override fun onAccepted() {
                    if (phase !== expected) return
                    awaitStaleGroupAbsence(
                        session = session,
                        attempt = attempt,
                        groupName = groupName,
                        removalAccepted = true
                    )
                }

                override fun onRejected(reason: Int) {
                    if (phase !== expected) return
                    Log.w(TAG, "Stale group removal command rejected: $reason")
                    awaitStaleGroupAbsence(
                        session = session,
                        attempt = attempt,
                        groupName = groupName,
                        removalAccepted = false
                    )
                }
            })
        } catch (e: SecurityException) {
            fail(PERMISSION_REVOKED_MESSAGE)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed to submit stale group removal", e)
            fail(HotspotStartupPolicy.GENERIC_FAILURE_MESSAGE)
        }
    }

    private fun awaitStaleGroupAbsence(
        session: Session,
        attempt: Int,
        groupName: String,
        removalAccepted: Boolean,
        absentObservations: Int = 0
    ) {
        val expected = Phase.Starting(
            session,
            StartStep.AwaitingStaleAbsence(
                attempt = attempt,
                groupName = groupName,
                removalAccepted = removalAccepted,
                absentObservations = absentObservations
            )
        )
        phase = expected

        try {
            p2p.requestGroup(session.channel) { group ->
                if (phase !== expected) return@requestGroup
                val actualName = group?.networkName
                when {
                    actualName != null && actualName != groupName -> {
                        ownedGroups.name = null
                        fail(HotspotStartupPolicy.FOREIGN_GROUP_MESSAGE)
                    }

                    group == null -> {
                        val observations = absentObservations + 1
                        if (observations >= REQUIRED_ABSENT_OBSERVATIONS) {
                            ownedGroups.name = null
                            createGroup(session, attempt)
                        } else {
                            schedule(session, REMOVAL_VERIFY_INTERVAL_MILLIS) {
                                awaitStaleGroupAbsence(
                                    session,
                                    attempt,
                                    groupName,
                                    removalAccepted,
                                    observations
                                )
                            }
                        }
                    }

                    removalAccepted -> {
                        schedule(session, REMOVAL_VERIFY_INTERVAL_MILLIS) {
                            awaitStaleGroupAbsence(
                                session,
                                attempt,
                                groupName,
                                removalAccepted = true
                            )
                        }
                    }

                    else -> {
                        schedule(session, REMOVAL_VERIFY_INTERVAL_MILLIS) {
                            removeStaleGroup(session, attempt, groupName)
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            fail(PERMISSION_REVOKED_MESSAGE)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed while verifying stale group removal", e)
            fail(HotspotStartupPolicy.GENERIC_FAILURE_MESSAGE)
        }
    }

    private fun scheduleStaleRemovalDeadline(session: Session, groupName: String) {
        schedule(session, STALE_REMOVAL_TIMEOUT_MILLIS) {
            val current = phase as? Phase.Starting ?: return@schedule
            if (current.session !== session) return@schedule
            val stillRemovingName = when (val step = current.step) {
                is StartStep.RemovingStale -> step.groupName
                is StartStep.AwaitingStaleAbsence -> step.groupName
                else -> null
            }
            if (stillRemovingName == groupName) {
                fail("The previous Wi-Fi Direct group could not be removed. Please try again.")
            }
        }
    }

    private fun createGroup(session: Session, attempt: Int) {
        val ownership = if (p2p.supportsCustomCredentials) {
            ownedGroups.name = session.credentials.networkName
            Ownership.Known(session.credentials.networkName)
        } else {
            Ownership.FrameworkGenerated
        }

        val expected = Phase.Creating(session, attempt, ownership)
        phase = expected
        try {
            p2p.createGroup(
                channel = session.channel,
                credentials = session.credentials.takeIf { p2p.supportsCustomCredentials },
                callback = object : HotspotP2p.ActionCallback {
                    override fun onAccepted() {
                        when (val current = phase) {
                            expected -> beginFormation(session, ownership)
                            is Phase.Stopping -> {
                                if (
                                    current.session === session &&
                                    current.cleanup is Cleanup.AwaitingCreateResult
                                ) {
                                    val next = current.copy(
                                        cleanup = Cleanup.Inspecting(
                                            ownership = ownership,
                                            mayStillForm = true
                                        )
                                    )
                                    phase = next
                                    inspectCleanup(next)
                                }
                            }

                            else -> Unit
                        }
                    }

                    override fun onRejected(reason: Int) {
                        when (val current = phase) {
                            expected -> handleGroupCreationFailure(expected, reason)

                            is Phase.Stopping -> {
                                if (
                                    current.session === session &&
                                    current.cleanup is Cleanup.AwaitingCreateResult
                                ) {
                                    finishSession(session, current.pendingError)
                                }
                            }

                            else -> Unit
                        }
                    }
                }
            )
        } catch (e: SecurityException) {
            clearMarker(ownership)
            fail(PERMISSION_REVOKED_MESSAGE)
        } catch (e: RuntimeException) {
            clearMarker(ownership)
            Log.e(TAG, "Failed to submit group creation", e)
            fail(HotspotStartupPolicy.GENERIC_FAILURE_MESSAGE)
        }
    }

    private fun handleGroupCreationFailure(
        creating: Phase.Creating,
        reason: Int
    ) {
        val session = creating.session
        when (
            val decision = HotspotStartupPolicy.decide(
                reason,
                creating.attempt,
                lastP2pState
            )
        ) {
            is HotspotStartupPolicy.Decision.Retry -> {
                schedule(session, decision.delayMillis) {
                    if (phase !== creating) return@schedule
                    inspectBeforeCreate(session, creating.attempt + 1)
                }
            }

            is HotspotStartupPolicy.Decision.Fail -> {
                clearMarker(creating.ownership)
                fail(decision.message)
            }
        }
    }

    private fun beginFormation(session: Session, ownership: Ownership) {
        val forming = Phase.Forming(session, ownership)
        phase = forming
        pollFormation(forming, delayMillis = 0L)
        schedule(session, GROUP_FORMATION_TIMEOUT_MILLIS) {
            val current = phase
            if (current is Phase.Forming && current.session === session) {
                fail("Hotspot failed to start. Please try again.")
            }
        }
    }

    private fun pollFormation(forming: Phase.Forming, delayMillis: Long) {
        schedule(forming.session, delayMillis) {
            if (phase !== forming) return@schedule
            try {
                p2p.requestGroup(forming.session.channel) { group ->
                    if (phase !== forming) return@requestGroup
                    observeFormation(forming, group)
                }
            } catch (e: SecurityException) {
                fail(PERMISSION_REVOKED_MESSAGE)
            } catch (e: RuntimeException) {
                Log.e(TAG, "Failed while observing group formation", e)
                fail(HotspotStartupPolicy.GENERIC_FAILURE_MESSAGE)
            }
        }
    }

    private fun observeFormation(forming: Phase.Forming, group: HotspotP2p.Group?) {
        val name = group?.networkName
        if (group == null || name == null) {
            pollFormation(forming, GROUP_INFO_POLL_INTERVAL_MILLIS)
            return
        }
        if (!group.isGroupOwner) {
            clearMarker(forming.ownership)
            finishSession(
                forming.session,
                HotspotStartupPolicy.FOREIGN_GROUP_MESSAGE
            )
            return
        }

        when (val ownership = forming.ownership) {
            is Ownership.Known -> {
                if (name != ownership.name) {
                    clearMarker(ownership)
                    finishSession(
                        forming.session,
                        HotspotStartupPolicy.FOREIGN_GROUP_MESSAGE
                    )
                    return
                }
                enterHosting(forming.session, ownership, group)
            }

            Ownership.FrameworkGenerated -> {
                if (forming.candidateName != null && forming.candidateName != name) {
                    ownedGroups.name = null
                    finishSession(
                        forming.session,
                        HotspotStartupPolicy.FOREIGN_GROUP_MESSAGE
                    )
                    return
                }
                val observations = forming.candidateObservations + 1
                if (observations >= REQUIRED_ABSENT_OBSERVATIONS) {
                    val known = Ownership.Known(name)
                    ownedGroups.name = name
                    enterHosting(forming.session, known, group)
                } else {
                    val next = forming.copy(
                        candidateName = name,
                        candidateObservations = observations
                    )
                    phase = next
                    pollFormation(next, GROUP_INFO_POLL_INTERVAL_MILLIS)
                }
            }
        }
    }

    private fun enterHosting(
        session: Session,
        ownership: Ownership.Known,
        group: HotspotP2p.Group
    ) {
        val hosting = Phase.Hosting(session, ownership, group)
        phase = hosting
        session.callback.onHotspotStarted()
        pollHosting(hosting, GROUP_INFO_POLL_INTERVAL_MILLIS)
    }

    private fun pollHosting(hosting: Phase.Hosting, delayMillis: Long) {
        schedule(hosting.session, delayMillis) {
            if (phase !== hosting) return@schedule
            try {
                p2p.requestGroup(hosting.session.channel) { group ->
                    if (phase !== hosting) return@requestGroup
                    observeHosting(hosting, group)
                }
            } catch (e: SecurityException) {
                fail(PERMISSION_REVOKED_MESSAGE)
            } catch (e: RuntimeException) {
                Log.e(TAG, "Failed while observing active group", e)
                fail(GROUP_LOST_MESSAGE)
            }
        }
    }

    private fun observeHosting(hosting: Phase.Hosting, group: HotspotP2p.Group?) {
        val name = group?.networkName
        when {
            group == null -> {
                val observations = hosting.absentObservations + 1
                if (observations >= REQUIRED_ABSENT_OBSERVATIONS) {
                    clearMarker(hosting.ownership)
                    finishSession(hosting.session, GROUP_LOST_MESSAGE)
                } else {
                    val next = hosting.copy(absentObservations = observations)
                    phase = next
                    pollHosting(next, GROUP_INFO_POLL_INTERVAL_MILLIS)
                }
            }

            name != hosting.ownership.name || !group.isGroupOwner -> {
                clearMarker(hosting.ownership)
                finishSession(hosting.session, GROUP_LOST_MESSAGE)
            }

            else -> {
                val next = hosting.copy(group = group, absentObservations = 0)
                phase = next
                hosting.session.callback.onConnectionInfoUpdated(
                    connectionInfo(group, hosting.session)
                )
                pollHosting(next, GROUP_INFO_POLL_INTERVAL_MILLIS)
            }
        }
    }

    private fun beginStopping(
        session: Session,
        cleanup: Cleanup,
        pendingError: String?
    ) {
        val stopping = Phase.Stopping(session, cleanup, pendingError)
        phase = stopping
        schedule(session, TEARDOWN_FORCE_CLOSE_MILLIS) {
            val current = phase as? Phase.Stopping ?: return@schedule
            if (current.session === session) forceCleanup(current)
        }

        when (cleanup) {
            is Cleanup.Inspecting -> inspectCleanup(stopping)
            is Cleanup.AwaitingCreateResult,
            is Cleanup.Removing,
            is Cleanup.RecoveringChannel -> Unit
        }
    }

    private fun inspectCleanup(stopping: Phase.Stopping) {
        val cleanup = stopping.cleanup as? Cleanup.Inspecting ?: return
        try {
            p2p.requestGroup(stopping.session.channel) { group ->
                if (phase !== stopping) return@requestGroup
                observeCleanup(stopping, cleanup, group)
            }
        } catch (e: SecurityException) {
            forceCleanup(stopping)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed while verifying hotspot cleanup", e)
            forceCleanup(stopping)
        }
    }

    private fun observeCleanup(
        stopping: Phase.Stopping,
        cleanup: Cleanup.Inspecting,
        group: HotspotP2p.Group?
    ) {
        val actualName = group?.networkName
        val known = cleanup.ownership as? Ownership.Known

        if (group != null) {
            if (!group.isGroupOwner) {
                known?.let(::clearMarker)
                finishSession(stopping.session, stopping.pendingError)
                return
            }
            if (known != null) {
                when {
                    actualName == null -> {
                        scheduleCleanupInspection(stopping, cleanup)
                        return
                    }

                    actualName != known.name -> {
                        clearMarker(known)
                        finishSession(stopping.session, stopping.pendingError)
                        return
                    }
                }
            }
        }

        if (group == null) {
            if (cleanup.mayStillForm && !cleanup.removalAccepted && !cleanup.forced) {
                // A null snapshot does not prove there is no group, but removeGroup() is
                // device-scoped. Close our channel to cancel the accepted create command
                // instead of risking another app's group, then verify on a fresh channel.
                forceCleanup(stopping)
                return
            }

            val observations = cleanup.absentObservations + 1
            if (observations >= REQUIRED_ABSENT_OBSERVATIONS) {
                clearMarker(cleanup.ownership)
                finishSession(stopping.session, stopping.pendingError)
            } else {
                val next = stopping.copy(
                    cleanup = cleanup.copy(absentObservations = observations)
                )
                phase = next
                schedule(stopping.session, REMOVAL_VERIFY_INTERVAL_MILLIS) {
                    inspectCleanup(next)
                }
            }
            return
        }

        val observedOwnership = if (cleanup.ownership is Ownership.FrameworkGenerated) {
            val name = actualName
            if (name == null) {
                scheduleCleanupInspection(stopping, cleanup)
                return
            }
            if (cleanup.candidateName != null && cleanup.candidateName != name) {
                ownedGroups.name = null
                finishSession(stopping.session, stopping.pendingError)
                return
            }
            val observations = cleanup.candidateObservations + 1
            if (observations < REQUIRED_ABSENT_OBSERVATIONS) {
                scheduleCleanupInspection(
                    stopping,
                    cleanup.copy(
                        candidateName = name,
                        candidateObservations = observations
                    )
                )
                return
            }
            Ownership.Known(name).also { ownedGroups.name = name }
        } else {
            cleanup.ownership
        }

        if (cleanup.removalAccepted) {
            val next = stopping.copy(
                cleanup = cleanup.copy(
                    ownership = observedOwnership,
                    absentObservations = 0
                )
            )
            phase = next
            schedule(stopping.session, REMOVAL_VERIFY_INTERVAL_MILLIS) {
                inspectCleanup(next)
            }
        } else {
            issueRemoval(
                stopping.copy(
                    cleanup = cleanup.copy(ownership = observedOwnership)
                ),
                cleanup.copy(ownership = observedOwnership)
            )
        }
    }

    private fun scheduleCleanupInspection(
        stopping: Phase.Stopping,
        cleanup: Cleanup.Inspecting
    ) {
        val next = stopping.copy(cleanup = cleanup)
        phase = next
        schedule(stopping.session, REMOVAL_VERIFY_INTERVAL_MILLIS) {
            inspectCleanup(next)
        }
    }

    private fun issueRemoval(
        stopping: Phase.Stopping,
        cleanup: Cleanup.Inspecting
    ) {
        val removing = stopping.copy(
            cleanup = Cleanup.Removing(
                ownership = cleanup.ownership,
                mayStillForm = cleanup.mayStillForm,
                forced = cleanup.forced
            )
        )
        phase = removing

        try {
            p2p.removeGroup(stopping.session.channel, object : HotspotP2p.ActionCallback {
                override fun onAccepted() {
                    if (phase !== removing) return
                    val next = removing.copy(
                        cleanup = Cleanup.Inspecting(
                            ownership = cleanup.ownership,
                            mayStillForm = false,
                            removalAccepted = true,
                            forced = cleanup.forced
                        )
                    )
                    phase = next
                    schedule(stopping.session, REMOVAL_VERIFY_INTERVAL_MILLIS) {
                        inspectCleanup(next)
                    }
                }

                override fun onRejected(reason: Int) {
                    if (phase !== removing) return
                    Log.w(TAG, "Group removal command rejected: $reason")
                    val next = removing.copy(
                        cleanup = Cleanup.Inspecting(
                            ownership = cleanup.ownership,
                            mayStillForm = cleanup.mayStillForm,
                            removalAccepted = false,
                            forced = cleanup.forced
                        )
                    )
                    phase = next
                    schedule(stopping.session, REMOVAL_VERIFY_INTERVAL_MILLIS) {
                        inspectCleanup(next)
                    }
                }
            })
        } catch (e: SecurityException) {
            forceCleanup(removing)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed to submit group removal", e)
            forceCleanup(removing)
        }
    }

    /**
     * A framework callback that never arrives cannot be the only cleanup path. Closing the
     * channel actively asks the framework to remove this app's connections, then a fresh
     * channel verifies group state before the Aware hold is released.
     */
    private fun forceCleanup(stopping: Phase.Stopping) {
        if (phase !== stopping || stopping.cleanup is Cleanup.RecoveringChannel) return
        if (preservePendingCreateWhenChannelCannotClose(stopping)) return

        val session = stopping.session
        val nextAttempt = session.cleanupRecoveryAttempt + 1
        if (nextAttempt > MAX_CHANNEL_RECOVERY_ATTEMPTS) {
            Log.e(TAG, "Could not verify P2P cleanup after closing app channels")
            finishSession(session, stopping.pendingError)
            return
        }

        val ownership = when (val cleanup = stopping.cleanup) {
            is Cleanup.AwaitingCreateResult -> cleanup.ownership
            is Cleanup.Inspecting -> cleanup.ownership
            is Cleanup.Removing -> cleanup.ownership
            is Cleanup.RecoveringChannel -> return
        }
        val recovering = Phase.Stopping(
            session = session,
            cleanup = Cleanup.RecoveringChannel(ownership, nextAttempt),
            pendingError = stopping.pendingError
        )
        phase = recovering
        session.cleanupRecoveryAttempt = nextAttempt
        session.channelGeneration++

        Log.w(
            TAG,
            "Forcing P2P cleanup for session ${session.id} (attempt $nextAttempt)"
        )
        try {
            session.closeChannel()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close stuck P2P channel", e)
        }

        recoverCleanupChannel(
            session = session,
            ownership = ownership,
            pendingError = stopping.pendingError,
            attempt = nextAttempt
        )
    }

    /**
     * API 26 has no Channel.close(), so a submitted create command cannot be cancelled.
     * Null group snapshots are not terminal while that command can still form a group;
     * keep the Aware lease and original channel alive until the create callback resolves.
     */
    private fun preservePendingCreateWhenChannelCannotClose(
        stopping: Phase.Stopping
    ): Boolean {
        if (p2p.supportsChannelClose) return false

        val delayMillis = when (val cleanup = stopping.cleanup) {
            is Cleanup.AwaitingCreateResult -> {
                Log.w(
                    TAG,
                    "Waiting for an uncancellable API 26 create callback in session " +
                        stopping.session.id
                )
                TEARDOWN_FORCE_CLOSE_MILLIS
            }

            is Cleanup.Inspecting -> {
                if (!cleanup.mayStillForm) return false
                REMOVAL_VERIFY_INTERVAL_MILLIS
            }

            is Cleanup.Removing,
            is Cleanup.RecoveringChannel -> return false
        }

        schedule(stopping.session, delayMillis) {
            if (phase !== stopping) return@schedule
            when (stopping.cleanup) {
                is Cleanup.AwaitingCreateResult -> forceCleanup(stopping)
                is Cleanup.Inspecting -> inspectCleanup(stopping)
                is Cleanup.Removing,
                is Cleanup.RecoveringChannel -> Unit
            }
        }
        return true
    }

    private fun recoverCleanupChannel(
        session: Session,
        ownership: Ownership,
        pendingError: String?,
        attempt: Int
    ) {
        val recovering = Phase.Stopping(
            session = session,
            cleanup = Cleanup.RecoveringChannel(ownership, attempt),
            pendingError = pendingError
        )
        phase = recovering

        val generation = session.channelGeneration
        val replacement = try {
            p2p.initialize { onChannelDisconnected(session, generation) }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to initialize cleanup verification channel", e)
            null
        }

        if (replacement != null) {
            if (phase !== recovering || session.channelGeneration != generation) {
                replacement.close()
                return
            }
            session.channel = replacement
            session.channelClosed = false
            val verifying = recovering.copy(
                cleanup = Cleanup.Inspecting(
                    ownership = ownership,
                    mayStillForm = false,
                    forced = true
                )
            )
            phase = verifying
            schedule(session, REMOVAL_VERIFY_INTERVAL_MILLIS) {
                inspectCleanup(verifying)
            }
            schedule(session, TEARDOWN_FORCE_CLOSE_MILLIS) {
                val current = phase as? Phase.Stopping ?: return@schedule
                if (
                    current.session === session &&
                    session.cleanupRecoveryAttempt == attempt
                ) {
                    forceCleanup(current)
                }
            }
            return
        }

        if (attempt >= MAX_CHANNEL_RECOVERY_ATTEMPTS) {
            Log.e(TAG, "Could not verify P2P cleanup after closing the app channel")
            finishSession(session, pendingError)
            return
        }

        schedule(session, CHANNEL_RECOVERY_RETRY_MILLIS) {
            val current = phase as? Phase.Stopping ?: return@schedule
            val recovery = current.cleanup as? Cleanup.RecoveringChannel ?: return@schedule
            if (current.session === session && recovery.attempt == attempt) {
                session.cleanupRecoveryAttempt = attempt + 1
                session.channelGeneration++
                recoverCleanupChannel(session, ownership, pendingError, attempt + 1)
            }
        }
    }

    private fun fail(message: String) {
        when (val current = phase) {
            Phase.Idle, Phase.Closed -> Unit

            is Phase.Starting -> when (val step = current.step) {
                is StartStep.RemovingStale ->
                    beginStopping(
                        current.session,
                        Cleanup.Inspecting(
                            ownership = Ownership.Known(step.groupName),
                            mayStillForm = false
                        ),
                        message
                    )

                is StartStep.AwaitingStaleAbsence ->
                    beginStopping(
                        current.session,
                        Cleanup.Inspecting(
                            ownership = Ownership.Known(step.groupName),
                            mayStillForm = false,
                            removalAccepted = step.removalAccepted,
                            absentObservations = step.absentObservations
                        ),
                        message
                    )

                else -> finishSession(current.session, message)
            }

            is Phase.Creating ->
                beginStopping(
                    current.session,
                    Cleanup.AwaitingCreateResult(current.ownership),
                    message
                )

            is Phase.Forming ->
                beginStopping(
                    current.session,
                    Cleanup.Inspecting(
                        ownership = current.ownership,
                        mayStillForm = true
                    ),
                    message
                )

            is Phase.Hosting ->
                beginStopping(
                    current.session,
                    Cleanup.Inspecting(
                        ownership = current.ownership,
                        mayStillForm = false
                    ),
                    message
                )

            is Phase.Stopping -> {
                if (current.pendingError == null) {
                    phase = current.copy(pendingError = message)
                }
            }
        }
    }

    private fun onP2pStateChanged(state: Int) {
        lastP2pState = state
        if (state != WifiP2pManager.WIFI_P2P_STATE_DISABLED) return

        when (val current = phase) {
            is Phase.Stopping -> forceCleanup(current)
            Phase.Idle, Phase.Closed -> Unit
            else -> fail(HotspotStartupPolicy.P2P_DISABLED_MESSAGE)
        }
    }

    private fun onConnectionChanged() {
        when (val current = phase) {
            is Phase.Forming -> pollFormation(current, delayMillis = 0L)
            is Phase.Hosting -> pollHosting(current, delayMillis = 0L)
            is Phase.Starting -> {
                val step = current.step as? StartStep.AwaitingStaleAbsence ?: return
                awaitStaleGroupAbsence(
                    session = current.session,
                    attempt = step.attempt,
                    groupName = step.groupName,
                    removalAccepted = step.removalAccepted,
                    absentObservations = step.absentObservations
                )
            }

            is Phase.Stopping -> {
                if (current.cleanup is Cleanup.Inspecting) inspectCleanup(current)
            }

            else -> Unit
        }
    }

    private fun onChannelDisconnected(session: Session, generation: Long) {
        if (
            currentSession() !== session ||
            session.channelGeneration != generation
        ) {
            return
        }
        when (val current = phase) {
            is Phase.Stopping -> {
                if (current.cleanup !is Cleanup.RecoveringChannel) {
                    forceCleanup(current)
                }
            }
            else -> fail("The Wi-Fi Direct service disconnected. Please try again.")
        }
    }

    private fun finishSession(
        session: Session,
        error: String? = null
    ) {
        if (currentSession() !== session) return
        phase = Phase.Closed

        session.tasks.forEach(HotspotScheduler.Task::cancel)
        session.tasks.clear()
        try {
            session.closeChannel()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing P2P channel", e)
        }
        try {
            platform.deactivate()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing hotspot platform resources", e)
        }

        val teardownCallbacks = session.teardownCallbacks.toList()
        session.teardownCallbacks.clear()
        teardownCallbacks.forEach { callback ->
            try {
                callback()
            } catch (e: Exception) {
                Log.e(TAG, "Hotspot teardown callback failed", e)
            }
        }

        if (error != null) {
            session.callback.onError(error)
        }
    }

    private fun currentSession(): Session? = when (val current = phase) {
        is Phase.Starting -> current.session
        is Phase.Creating -> current.session
        is Phase.Forming -> current.session
        is Phase.Hosting -> current.session
        is Phase.Stopping -> current.session
        Phase.Idle, Phase.Closed -> null
    }

    private fun schedule(session: Session, delayMillis: Long, action: () -> Unit) {
        lateinit var task: HotspotScheduler.Task
        task = scheduler.schedule(delayMillis) {
            session.tasks.remove(task)
            action()
        }
        session.tasks += task
    }

    private fun clearMarker(ownership: Ownership) {
        val known = ownership as? Ownership.Known ?: return
        if (ownedGroups.name == known.name) {
            ownedGroups.name = null
        }
    }

    private fun connectionInfo(
        group: HotspotP2p.Group,
        session: Session
    ): ConnectionInfo = ConnectionInfo(
        ssid = group.networkName ?: session.credentials.networkName,
        password = group.passphrase ?: session.credentials.passphrase,
        ipAddress = accessPointAddress() ?: "192.168.49.1",
        connectedPeers = group.clientCount
    )

    private fun generateSsid(): String =
        HotspotStartupPolicy.SSID_PREFIX + randomString(SSID_SUFFIX_LENGTH)

    private fun generatePassword(): String = randomString(PASSWORD_LENGTH)

    private fun randomString(length: Int): String =
        buildString(length) {
            repeat(length) {
                append(RANDOM_CHARS[random.nextInt(RANDOM_CHARS.length)])
            }
        }

    data class ConnectionInfo(
        val ssid: String,
        val password: String,
        val ipAddress: String,
        val connectedPeers: Int
    )

    interface HotspotCallback {
        fun onHotspotStarted()
        fun onConnectionInfoUpdated(info: ConnectionInfo?)
        fun onError(message: String)
    }
}
