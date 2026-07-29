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
    private val random: SecureRandom = SecureRandom(),
    private val accessPointAddress: () -> String? = ::findAccessPointAddress
) {
    constructor(context: android.content.Context) : this(
        p2p = AndroidHotspotP2p(context.applicationContext),
        platform = AndroidHotspotPlatform(context.applicationContext),
        scheduler = HandlerHotspotScheduler()
    )

    companion object {
        private const val TAG = "HotspotMgr"

        private const val GROUP_INFO_POLL_INTERVAL_MILLIS = 1_000L
        private const val REMOVAL_VERIFY_INTERVAL_MILLIS = 500L
        private const val PREFLIGHT_REQUEST_TIMEOUT_MILLIS = 10_000L
        private const val GROUP_CREATION_RESULT_TIMEOUT_MILLIS = 15_000L
        private const val GROUP_FORMATION_TIMEOUT_MILLIS = 15_000L
        private const val EXISTING_REMOVAL_TIMEOUT_MILLIS = 10_000L
        private const val TEARDOWN_FORCE_CLOSE_MILLIS = 10_000L
        private const val CHANNEL_RECOVERY_RETRY_MILLIS = 1_000L
        private const val MAX_CHANNEL_RECOVERY_ATTEMPTS = 5
        private const val REQUIRED_ABSENCE_OBSERVATIONS = 2
        private const val REQUIRED_OWNERSHIP_OBSERVATIONS = 2

        private const val SSID_PREFIX = "DIRECT-BC-"
        private const val SSID_SUFFIX_LENGTH = 8
        private const val PASSWORD_LENGTH = 16
        private const val RANDOM_CHARS = "ABCDEFGHJKLMNPQRTUVWXY34679"

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
            val expectedGroupName: String?
        ) : Phase

        data class Forming(
            val session: Session,
            val expectedGroupName: String?,
            val candidateGroupName: String? = null,
            val candidateObservations: Int = 0
        ) : Phase

        data class Hosting(
            val session: Session,
            val groupName: String,
            val group: HotspotP2p.Group,
            val absentObservations: Int = 0
        ) : Phase

        data class Stopping(
            val session: Session,
            val cleanup: Cleanup,
            val pendingError: HotspotError?
        ) : Phase

        data object Closed : Phase
    }

    private sealed interface StartStep {
        data object AwaitingP2pState : StartStep
        data class Inspecting(val attempt: Int) : StartStep
        data class WaitingToRetry(val attempt: Int) : StartStep

        data class RemovingExisting(val attempt: Int) : StartStep

        data class AwaitingExistingAbsence(
            val attempt: Int,
            val submission: RemovalSubmission,
            val absentObservations: Int = 0
        ) : StartStep

        enum class RemovalSubmission {
            ACCEPTED,
            REJECTED
        }
    }

    private sealed interface Cleanup {
        data class AwaitingCreateResult(
            val expectedGroupName: String?
        ) : Cleanup

        /**
         * Why the next group snapshot is being requested.
         *
         * These are mutually exclusive lifecycle states. Keeping them explicit avoids
         * invalid combinations such as "removal accepted but still awaiting formation".
         */
        enum class Inspection {
            LOCATE_SESSION_GROUP,
            AWAIT_POSSIBLE_FORMATION,
            VERIFY_REMOVAL,
            AWAIT_POST_CLOSE_FORMATION,
            VERIFY_AFTER_CHANNEL_CLOSE
        }

        enum class OwnershipEvidence {
            NONE,
            ACCEPTED_CREATE
        }

        data class Inspecting(
            val expectedGroupName: String?,
            val purpose: Inspection,
            val absentObservations: Int = 0,
            val candidateGroupName: String? = null,
            val candidateObservations: Int = 0,
            val ownershipEvidence: OwnershipEvidence = OwnershipEvidence.NONE
        ) : Cleanup

        data class Removing(
            val expectedGroupName: String?
        ) : Cleanup

        data class RecoveringChannel(
            val expectedGroupName: String?,
            val purpose: Inspection,
            val ownershipEvidence: OwnershipEvidence,
            val attempt: Int
        ) : Cleanup
    }

    private class Session(
        val id: Long,
        var channel: HotspotP2p.Channel,
        val callback: HotspotCallback,
        val credentials: HotspotP2p.Credentials,
        val existingGroupPolicy: ExistingGroupPolicy
    ) {
        val tasks = mutableListOf<HotspotScheduler.Task>()
        val teardownCallbacks = mutableListOf<() -> Unit>()
        var channelClosed = false
        var channelGeneration = 0L
        var cleanupRecoveryAttempt = 0
        var postCloseFormationDeadlineScheduled = false
        var postCloseFormationWindowElapsed = false

        fun closeChannel() {
            if (channelClosed) return
            channelClosed = true
            channel.close()
        }
    }

    private var phase: Phase = Phase.Idle
    private var nextSessionId = 1L
    private var lastP2pState: Int? = null

    fun startHotspot(
        callback: HotspotCallback,
        existingGroupPolicy: ExistingGroupPolicy = ExistingGroupPolicy.RequireConfirmation
    ) {
        if (phase !is Phase.Idle && phase !is Phase.Closed) {
            Log.w(TAG, "Ignoring start while lifecycle is ${phase.javaClass.simpleName}")
            return
        }

        if (!p2p.available) {
            callback.onError(HotspotError.P2P_UNSUPPORTED)
            return
        }

        val missingPermissions = platform.missingPermissions()
        if (missingPermissions.isNotEmpty()) {
            val error = if (Manifest.permission.ACCESS_LOCAL_NETWORK in missingPermissions) {
                HotspotError.LOCAL_NETWORK_PERMISSION_REQUIRED
            } else {
                HotspotError.NEARBY_WIFI_PERMISSION_REQUIRED
            }
            callback.onError(error)
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
            callback.onError(HotspotError.PREPARATION_FAILED)
            return
        }

        val session = createSession(callback, existingGroupPolicy) ?: run {
            platform.deactivate()
            callback.onError(HotspotError.P2P_UNSUPPORTED)
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
                // No create command has been submitted in Starting. A replacement removal
                // already authorized by the user may still complete, but there is no group
                // created by this session that teardown needs to own.
                finishSession(current.session)
            }

            is Phase.Creating -> {
                onTeardownComplete?.let(current.session.teardownCallbacks::add)
                beginStopping(
                    session = current.session,
                    cleanup = Cleanup.AwaitingCreateResult(current.expectedGroupName),
                    pendingError = null
                )
            }

            is Phase.Forming -> {
                onTeardownComplete?.let(current.session.teardownCallbacks::add)
                beginStopping(
                    session = current.session,
                    cleanup = Cleanup.Inspecting(
                        expectedGroupName = current.expectedGroupName,
                        purpose = Cleanup.Inspection.AWAIT_POSSIBLE_FORMATION,
                        candidateGroupName = current.candidateGroupName,
                        candidateObservations = current.candidateObservations
                    ),
                    pendingError = null
                )
            }

            is Phase.Hosting -> {
                onTeardownComplete?.let(current.session.teardownCallbacks::add)
                beginStopping(
                    session = current.session,
                    cleanup = Cleanup.Inspecting(
                        expectedGroupName = current.groupName,
                        purpose = Cleanup.Inspection.LOCATE_SESSION_GROUP
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

    private fun createSession(
        callback: HotspotCallback,
        existingGroupPolicy: ExistingGroupPolicy
    ): Session? {
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
            ),
            existingGroupPolicy = existingGroupPolicy
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
                    fail(HotspotError.P2P_DISABLED)
                } else {
                    inspectBeforeCreate(session, attempt = 1)
                }
            }
        } catch (e: SecurityException) {
            fail(HotspotError.PERMISSION_REVOKED)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed to request P2P state", e)
            fail(HotspotError.START_FAILED)
        }
    }

    private fun inspectBeforeCreate(session: Session, attempt: Int) {
        val expected = Phase.Starting(session, StartStep.Inspecting(attempt))
        phase = expected
        schedulePreflightDeadline(expected)
        try {
            p2p.requestGroup(session.channel) { group ->
                if (phase !== expected) return@requestGroup
                when {
                    lastP2pState == WifiP2pManager.WIFI_P2P_STATE_DISABLED ->
                        fail(HotspotError.P2P_DISABLED)

                    group == null -> createGroup(session, attempt)

                    session.existingGroupPolicy.canReplace(group) ->
                        removeExistingGroup(session, attempt)

                    else -> reportExistingGroupConflict(session, group)
                }
            }
        } catch (e: SecurityException) {
            fail(HotspotError.PERMISSION_REVOKED)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed to inspect the current P2P group", e)
            fail(HotspotError.START_FAILED)
        }
    }

    private fun schedulePreflightDeadline(expected: Phase.Starting) {
        schedule(expected.session, PREFLIGHT_REQUEST_TIMEOUT_MILLIS) {
            if (phase === expected) {
                fail(HotspotError.PREFLIGHT_TIMEOUT)
            }
        }
    }

    private fun removeExistingGroup(session: Session, attempt: Int) {
        val expected = Phase.Starting(
            session,
            StartStep.RemovingExisting(attempt)
        )
        phase = expected
        scheduleExistingRemovalDeadline(session)

        try {
            p2p.removeGroup(session.channel, object : HotspotP2p.ActionCallback {
                override fun onAccepted() {
                    if (phase !== expected) return
                    awaitExistingGroupAbsence(
                        session = session,
                        attempt = attempt,
                        submission = StartStep.RemovalSubmission.ACCEPTED
                    )
                }

                override fun onRejected(reason: Int) {
                    if (phase !== expected) return
                    Log.w(TAG, "Existing group removal command rejected: $reason")
                    awaitExistingGroupAbsence(
                        session = session,
                        attempt = attempt,
                        submission = StartStep.RemovalSubmission.REJECTED
                    )
                }
            })
        } catch (e: SecurityException) {
            fail(HotspotError.PERMISSION_REVOKED)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed to submit existing group removal", e)
            fail(HotspotError.START_FAILED)
        }
    }

    private fun awaitExistingGroupAbsence(
        session: Session,
        attempt: Int,
        submission: StartStep.RemovalSubmission,
        absentObservations: Int = 0
    ) {
        val expected = Phase.Starting(
            session,
            StartStep.AwaitingExistingAbsence(
                attempt = attempt,
                submission = submission,
                absentObservations = absentObservations
            )
        )
        phase = expected

        try {
            p2p.requestGroup(session.channel) { group ->
                if (phase !== expected) return@requestGroup
                when {
                    group == null -> {
                        val observations = absentObservations + 1
                        if (observations >= REQUIRED_ABSENCE_OBSERVATIONS) {
                            createGroup(session, attempt)
                        } else {
                            schedule(session, REMOVAL_VERIFY_INTERVAL_MILLIS) {
                                if (phase !== expected) return@schedule
                                awaitExistingGroupAbsence(
                                    session,
                                    attempt,
                                    submission,
                                    observations
                                )
                            }
                        }
                    }

                    !session.existingGroupPolicy.canReplace(group) ->
                        reportExistingGroupConflict(session, group)

                    submission == StartStep.RemovalSubmission.ACCEPTED -> {
                        schedule(session, REMOVAL_VERIFY_INTERVAL_MILLIS) {
                            if (phase !== expected) return@schedule
                            awaitExistingGroupAbsence(
                                session,
                                attempt,
                                submission = StartStep.RemovalSubmission.ACCEPTED
                            )
                        }
                    }

                    else -> {
                        schedule(session, REMOVAL_VERIFY_INTERVAL_MILLIS) {
                            if (phase !== expected) return@schedule
                            inspectBeforeCreate(session, attempt)
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            fail(HotspotError.PERMISSION_REVOKED)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed while verifying existing group removal", e)
            fail(HotspotError.START_FAILED)
        }
    }

    private fun scheduleExistingRemovalDeadline(session: Session) {
        schedule(session, EXISTING_REMOVAL_TIMEOUT_MILLIS) {
            val current = phase as? Phase.Starting ?: return@schedule
            if (current.session !== session) return@schedule
            if (
                current.step is StartStep.RemovingExisting ||
                current.step is StartStep.AwaitingExistingAbsence
            ) {
                fail(HotspotError.EXISTING_GROUP_REMOVAL_FAILED)
            }
        }
    }

    private fun createGroup(session: Session, attempt: Int) {
        val expectedGroupName =
            session.credentials.networkName.takeIf { p2p.supportsCustomCredentials }

        val expected = Phase.Creating(session, attempt, expectedGroupName)
        phase = expected
        try {
            p2p.createGroup(
                channel = session.channel,
                credentials = session.credentials.takeIf { p2p.supportsCustomCredentials },
                callback = object : HotspotP2p.ActionCallback {
                    override fun onAccepted() {
                        when (val current = phase) {
                            expected -> beginFormation(session, expectedGroupName)
                            is Phase.Stopping -> {
                                if (
                                    current.session === session &&
                                    current.cleanup is Cleanup.AwaitingCreateResult
                                ) {
                                    beginStopping(
                                        session = session,
                                        cleanup = Cleanup.Inspecting(
                                            expectedGroupName = expectedGroupName,
                                            purpose =
                                                Cleanup.Inspection.AWAIT_POSSIBLE_FORMATION
                                        ),
                                        pendingError = current.pendingError
                                    )
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
            schedule(session, GROUP_CREATION_RESULT_TIMEOUT_MILLIS) {
                if (phase === expected) {
                    fail(HotspotError.START_FAILED)
                }
            }
        } catch (e: SecurityException) {
            if (phase === expected) {
                finishSession(session, HotspotError.PERMISSION_REVOKED)
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed to submit group creation", e)
            if (phase === expected) {
                finishSession(session, HotspotError.START_FAILED)
            }
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
                val nextAttempt = creating.attempt + 1
                val waiting = Phase.Starting(
                    session,
                    StartStep.WaitingToRetry(nextAttempt)
                )
                phase = waiting
                schedule(session, decision.delayMillis) {
                    if (phase !== waiting) return@schedule
                    inspectBeforeCreate(session, nextAttempt)
                }
            }

            is HotspotStartupPolicy.Decision.Fail -> {
                finishSession(session, decision.error)
            }
        }
    }

    private fun beginFormation(session: Session, expectedGroupName: String?) {
        val forming = Phase.Forming(session, expectedGroupName)
        phase = forming
        pollFormation(forming, delayMillis = 0L)
        schedule(session, GROUP_FORMATION_TIMEOUT_MILLIS) {
            val current = phase
            if (current is Phase.Forming && current.session === session) {
                fail(HotspotError.START_FAILED)
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
                fail(HotspotError.PERMISSION_REVOKED)
            } catch (e: RuntimeException) {
                Log.e(TAG, "Failed while observing group formation", e)
                fail(HotspotError.START_FAILED)
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
            handleUnexpectedGroup(forming.session, group)
            return
        }

        if (forming.expectedGroupName != null && name != forming.expectedGroupName) {
            handleUnexpectedGroup(forming.session, group)
            return
        }

        if (forming.expectedGroupName != null) {
            enterHosting(forming.session, name, group)
            return
        }

        // Android 8-9 chooses the group name. Require the same owner group twice so a
        // single stale framework snapshot cannot become hosting or teardown ownership.
        if (
            forming.candidateGroupName != null &&
            forming.candidateGroupName != name
        ) {
            handleUnexpectedGroup(forming.session, group)
            return
        }

        val observations = forming.candidateObservations + 1
        if (observations >= REQUIRED_OWNERSHIP_OBSERVATIONS) {
            enterHosting(forming.session, name, group)
        } else {
            val next = forming.copy(
                candidateGroupName = name,
                candidateObservations = observations
            )
            phase = next
            pollFormation(next, GROUP_INFO_POLL_INTERVAL_MILLIS)
        }
    }

    private fun enterHosting(
        session: Session,
        groupName: String,
        group: HotspotP2p.Group
    ) {
        val hosting = Phase.Hosting(session, groupName, group)
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
                fail(HotspotError.PERMISSION_REVOKED)
            } catch (e: RuntimeException) {
                Log.e(TAG, "Failed while observing active group", e)
                fail(HotspotError.GROUP_LOST)
            }
        }
    }

    private fun observeHosting(hosting: Phase.Hosting, group: HotspotP2p.Group?) {
        val name = group?.networkName
        when {
            group == null -> {
                val observations = hosting.absentObservations + 1
                if (observations >= REQUIRED_ABSENCE_OBSERVATIONS) {
                    finishSession(hosting.session, HotspotError.GROUP_LOST)
                } else {
                    val next = hosting.copy(absentObservations = observations)
                    phase = next
                    pollHosting(next, GROUP_INFO_POLL_INTERVAL_MILLIS)
                }
            }

            !group.isGroupOwner -> {
                finishSession(hosting.session, HotspotError.GROUP_LOST)
            }

            name == null -> {
                val next = hosting.copy(absentObservations = 0)
                phase = next
                pollHosting(next, GROUP_INFO_POLL_INTERVAL_MILLIS)
            }

            name != hosting.groupName -> {
                finishSession(hosting.session, HotspotError.GROUP_LOST)
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
        pendingError: HotspotError?
    ) {
        val stopping = Phase.Stopping(session, cleanup, pendingError)
        phase = stopping
        val canFinishUncloseableFormation =
            !p2p.supportsChannelClose &&
                cleanup is Cleanup.Inspecting &&
                cleanup.purpose == Cleanup.Inspection.AWAIT_POSSIBLE_FORMATION
        schedule(session, TEARDOWN_FORCE_CLOSE_MILLIS) {
            val current = phase as? Phase.Stopping ?: return@schedule
            if (current.session !== session) return@schedule
            // A fresh verification channel has its own bounded recovery deadline.
            if (session.cleanupRecoveryAttempt > 0) return@schedule

            if (
                canFinishUncloseableFormation &&
                current.cleanup is Cleanup.Inspecting &&
                current.cleanup.purpose == Cleanup.Inspection.AWAIT_POSSIBLE_FORMATION &&
                current.cleanup.hasNoOwnershipEvidence()
            ) {
                // API 26 cannot close a channel. Once createGroup() has already returned
                // success, continued null snapshots are bounded failed-formation evidence;
                // unlike AwaitingCreateResult, there is no late command callback to retain.
                Log.w(TAG, "Finishing unformed API 26 group cleanup for session ${session.id}")
                finishSession(session, current.pendingError)
            } else {
                forceCleanup(current)
            }
        }

        when (cleanup) {
            is Cleanup.Inspecting -> inspectCleanup(stopping)
            is Cleanup.AwaitingCreateResult,
            is Cleanup.Removing,
            is Cleanup.RecoveringChannel -> Unit
        }
    }

    private fun Cleanup.Inspecting.hasNoOwnershipEvidence(): Boolean =
        expectedGroupName == null &&
            candidateGroupName == null &&
            candidateObservations == 0

    private fun Cleanup.Inspecting.hasOwnershipCandidate(): Boolean =
        expectedGroupName == null &&
            candidateGroupName != null &&
            candidateObservations > 0

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

        if (group != null) {
            if (!group.isGroupOwner) {
                finishSession(stopping.session, stopping.pendingError)
                return
            }
            if (cleanup.expectedGroupName != null) {
                when {
                    actualName == null -> {
                        scheduleCleanupInspection(stopping, cleanup)
                        return
                    }

                    actualName != cleanup.expectedGroupName -> {
                        finishSession(stopping.session, stopping.pendingError)
                        return
                    }
                }
            }
        }

        if (group == null) {
            if (cleanup.purpose == Cleanup.Inspection.AWAIT_POSSIBLE_FORMATION) {
                if (!p2p.supportsChannelClose && cleanup.hasOwnershipCandidate()) {
                    val observations = cleanup.absentObservations + 1
                    if (observations >= REQUIRED_ABSENCE_OBSERVATIONS) {
                        finishSession(stopping.session, stopping.pendingError)
                    } else {
                        scheduleCleanupInspection(
                            stopping,
                            cleanup.copy(absentObservations = observations)
                        )
                    }
                    return
                }

                // A null snapshot does not prove there is no group, but removeGroup() is
                // device-scoped. Close our channel to cancel the accepted create command
                // instead of risking another app's group, then verify on a fresh channel.
                forceCleanup(stopping)
                return
            }

            if (
                cleanup.purpose ==
                Cleanup.Inspection.AWAIT_POST_CLOSE_FORMATION
            ) {
                scheduleCleanupInspection(
                    stopping,
                    cleanup.copy(absentObservations = 0)
                )
                return
            }

            val observations = cleanup.absentObservations + 1
            if (observations >= REQUIRED_ABSENCE_OBSERVATIONS) {
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

        val observedName = actualName

        if (
            cleanup.expectedGroupName == null &&
            cleanup.purpose.isPostCloseVerification() &&
            cleanup.ownershipEvidence != Cleanup.OwnershipEvidence.ACCEPTED_CREATE
        ) {
            // Closing the original pre-Q channel discards the only link between its
            // accepted create command and a later group. Never adopt a group first seen
            // on the fresh verification channel.
            finishSession(stopping.session, stopping.pendingError)
            return
        }

        if (observedName == null) {
            scheduleCleanupInspection(stopping, cleanup)
            return
        }

        val expectedName = cleanup.expectedGroupName ?: run {
            if (
                cleanup.candidateGroupName != null &&
                cleanup.candidateGroupName != observedName
            ) {
                finishSession(stopping.session, stopping.pendingError)
                return
            }

            val observations = cleanup.candidateObservations + 1
            if (observations < REQUIRED_OWNERSHIP_OBSERVATIONS) {
                scheduleCleanupInspection(
                    stopping,
                    cleanup.copy(
                        candidateGroupName = observedName,
                        candidateObservations = observations
                    )
                )
                return
            }
            observedName
        }

        if (cleanup.purpose == Cleanup.Inspection.VERIFY_REMOVAL) {
            val next = stopping.copy(
                cleanup = cleanup.copy(
                    expectedGroupName = expectedName,
                    absentObservations = 0
                )
            )
            phase = next
            schedule(stopping.session, REMOVAL_VERIFY_INTERVAL_MILLIS) {
                inspectCleanup(next)
            }
        } else {
            val nextCleanup = cleanup.copy(expectedGroupName = expectedName)
            issueRemoval(
                stopping.copy(cleanup = nextCleanup),
                nextCleanup
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
            if (phase !== next) return@schedule
            inspectCleanup(next)
        }
    }

    private fun Cleanup.Inspection.isPostCloseVerification(): Boolean =
        this == Cleanup.Inspection.AWAIT_POST_CLOSE_FORMATION ||
            this == Cleanup.Inspection.VERIFY_AFTER_CHANNEL_CLOSE

    private fun Cleanup.Inspection.requiresFormationWait(): Boolean =
        this == Cleanup.Inspection.AWAIT_POSSIBLE_FORMATION ||
            this == Cleanup.Inspection.AWAIT_POST_CLOSE_FORMATION

    private fun issueRemoval(
        stopping: Phase.Stopping,
        cleanup: Cleanup.Inspecting
    ) {
        val removing = stopping.copy(
            cleanup = Cleanup.Removing(
                expectedGroupName = cleanup.expectedGroupName
            )
        )
        phase = removing

        try {
            p2p.removeGroup(stopping.session.channel, object : HotspotP2p.ActionCallback {
                override fun onAccepted() {
                    if (phase !== removing) return
                    val next = removing.copy(
                        cleanup = Cleanup.Inspecting(
                            expectedGroupName = cleanup.expectedGroupName,
                            purpose = Cleanup.Inspection.VERIFY_REMOVAL
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
                            expectedGroupName = cleanup.expectedGroupName,
                            purpose = Cleanup.Inspection.LOCATE_SESSION_GROUP
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

        val expectedGroupName: String?
        val verificationPurpose: Cleanup.Inspection
        val ownershipEvidence: Cleanup.OwnershipEvidence
        when (val cleanup = stopping.cleanup) {
            is Cleanup.AwaitingCreateResult -> {
                expectedGroupName = cleanup.expectedGroupName
                verificationPurpose =
                    Cleanup.Inspection.AWAIT_POST_CLOSE_FORMATION
                ownershipEvidence = Cleanup.OwnershipEvidence.NONE
            }

            is Cleanup.Inspecting -> {
                expectedGroupName = cleanup.expectedGroupName
                verificationPurpose =
                    if (cleanup.purpose.requiresFormationWait()) {
                        Cleanup.Inspection.AWAIT_POST_CLOSE_FORMATION
                    } else {
                        Cleanup.Inspection.VERIFY_AFTER_CHANNEL_CLOSE
                    }
                ownershipEvidence =
                    if (
                        cleanup.purpose == Cleanup.Inspection.AWAIT_POSSIBLE_FORMATION ||
                        cleanup.ownershipEvidence ==
                        Cleanup.OwnershipEvidence.ACCEPTED_CREATE
                    ) {
                        Cleanup.OwnershipEvidence.ACCEPTED_CREATE
                    } else {
                        Cleanup.OwnershipEvidence.NONE
                    }
            }

            is Cleanup.Removing -> {
                expectedGroupName = cleanup.expectedGroupName
                verificationPurpose = Cleanup.Inspection.VERIFY_AFTER_CHANNEL_CLOSE
                ownershipEvidence = Cleanup.OwnershipEvidence.NONE
            }

            is Cleanup.RecoveringChannel -> return
        }
        val recovering = Phase.Stopping(
            session = session,
            cleanup = Cleanup.RecoveringChannel(
                expectedGroupName,
                verificationPurpose,
                ownershipEvidence,
                nextAttempt
            ),
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
            expectedGroupName = expectedGroupName,
            verificationPurpose = verificationPurpose,
            ownershipEvidence = ownershipEvidence,
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
                if (cleanup.purpose != Cleanup.Inspection.AWAIT_POSSIBLE_FORMATION) {
                    return false
                }
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
        expectedGroupName: String?,
        verificationPurpose: Cleanup.Inspection,
        ownershipEvidence: Cleanup.OwnershipEvidence,
        pendingError: HotspotError?,
        attempt: Int
    ) {
        val recovering = Phase.Stopping(
            session = session,
            cleanup = Cleanup.RecoveringChannel(
                expectedGroupName,
                verificationPurpose,
                ownershipEvidence,
                attempt
            ),
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
            val effectivePurpose =
                if (
                    verificationPurpose ==
                    Cleanup.Inspection.AWAIT_POST_CLOSE_FORMATION &&
                    session.postCloseFormationWindowElapsed
                ) {
                    Cleanup.Inspection.VERIFY_AFTER_CHANNEL_CLOSE
                } else {
                    verificationPurpose
                }
            val verifying = recovering.copy(
                cleanup = Cleanup.Inspecting(
                    expectedGroupName = expectedGroupName,
                    purpose = effectivePurpose,
                    ownershipEvidence = ownershipEvidence
                )
            )
            phase = verifying
            schedule(session, REMOVAL_VERIFY_INTERVAL_MILLIS) {
                if (phase !== verifying) return@schedule
                inspectCleanup(verifying)
            }
            if (
                effectivePurpose ==
                Cleanup.Inspection.AWAIT_POST_CLOSE_FORMATION
            ) {
                if (!session.postCloseFormationDeadlineScheduled) {
                    session.postCloseFormationDeadlineScheduled = true
                    schedule(session, GROUP_FORMATION_TIMEOUT_MILLIS) {
                        session.postCloseFormationWindowElapsed = true
                        val current = phase as? Phase.Stopping ?: return@schedule
                        val inspection =
                            current.cleanup as? Cleanup.Inspecting ?: return@schedule
                        if (
                            current.session === session &&
                            inspection.purpose ==
                            Cleanup.Inspection.AWAIT_POST_CLOSE_FORMATION
                        ) {
                            // This deadline belongs to the accepted create command, so it
                            // intentionally survives verification-channel replacement.
                            val finalVerification = current.copy(
                                cleanup = inspection.copy(
                                    purpose = Cleanup.Inspection.VERIFY_AFTER_CHANNEL_CLOSE,
                                    absentObservations = 0
                                )
                            )
                            phase = finalVerification
                            inspectCleanup(finalVerification)
                        }
                    }
                    schedule(
                        session,
                        GROUP_FORMATION_TIMEOUT_MILLIS + TEARDOWN_FORCE_CLOSE_MILLIS
                    ) {
                        val current = phase as? Phase.Stopping ?: return@schedule
                        if (current.session === session) forceCleanup(current)
                    }
                }
            } else {
                schedule(session, TEARDOWN_FORCE_CLOSE_MILLIS) {
                    val current = phase as? Phase.Stopping ?: return@schedule
                    if (
                        current.session === session &&
                        session.cleanupRecoveryAttempt == attempt
                    ) {
                        forceCleanup(current)
                    }
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
                recoverCleanupChannel(
                    session,
                    recovery.expectedGroupName,
                    recovery.purpose,
                    recovery.ownershipEvidence,
                    current.pendingError,
                    attempt + 1
                )
            }
        }
    }

    private fun fail(error: HotspotError) {
        when (val current = phase) {
            Phase.Idle, Phase.Closed -> Unit

            is Phase.Starting -> finishSession(current.session, error)

            is Phase.Creating ->
                beginStopping(
                    current.session,
                    Cleanup.AwaitingCreateResult(current.expectedGroupName),
                    error
                )

            is Phase.Forming ->
                beginStopping(
                    current.session,
                    Cleanup.Inspecting(
                        expectedGroupName = current.expectedGroupName,
                        purpose = Cleanup.Inspection.AWAIT_POSSIBLE_FORMATION,
                        candidateGroupName = current.candidateGroupName,
                        candidateObservations = current.candidateObservations
                    ),
                    error
                )

            is Phase.Hosting ->
                beginStopping(
                    current.session,
                    Cleanup.Inspecting(
                        expectedGroupName = current.groupName,
                        purpose = Cleanup.Inspection.LOCATE_SESSION_GROUP
                    ),
                    error
                )

            is Phase.Stopping -> {
                if (current.pendingError == null) {
                    phase = current.copy(pendingError = error)
                }
            }
        }
    }

    private fun onP2pStateChanged(state: Int) {
        lastP2pState = state
        if (state != WifiP2pManager.WIFI_P2P_STATE_DISABLED) return

        when (val current = phase) {
            Phase.Idle, Phase.Closed -> Unit
            is Phase.Stopping ->
                finishAfterP2pDisabled(current.session, current.pendingError)

            else -> currentSession()?.let { session ->
                finishAfterP2pDisabled(
                    session,
                    HotspotError.P2P_DISABLED
                )
            }
        }
    }

    /**
     * A disabled P2P service cannot retain or subsequently form a group. This is also the
     * only terminal evidence available on API 26 when an accepted create callback is lost,
     * because that release has no Channel.close().
     */
    private fun finishAfterP2pDisabled(session: Session, error: HotspotError?) {
        finishSession(session, error)
    }

    private fun onConnectionChanged() {
        when (val current = phase) {
            is Phase.Forming -> pollFormation(current, delayMillis = 0L)
            is Phase.Hosting -> pollHosting(current, delayMillis = 0L)
            is Phase.Starting -> {
                val step = current.step as? StartStep.AwaitingExistingAbsence ?: return
                awaitExistingGroupAbsence(
                    session = current.session,
                    attempt = step.attempt,
                    submission = step.submission,
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
            else -> fail(HotspotError.P2P_SERVICE_DISCONNECTED)
        }
    }

    private fun handleUnexpectedGroup(session: Session, group: HotspotP2p.Group) {
        if (session.existingGroupPolicy is ExistingGroupPolicy.RequireConfirmation) {
            reportExistingGroupConflict(session, group)
        } else {
            finishSession(session, HotspotError.P2P_BUSY)
        }
    }

    private fun reportExistingGroupConflict(
        session: Session,
        group: HotspotP2p.Group
    ) {
        if (currentSession() !== session) return
        val identity = ExistingGroupIdentity.from(group)
        if (identity == null) {
            finishSession(session, HotspotError.P2P_BUSY)
            return
        }
        finishSession(session)
        session.callback.onExistingGroupConflict(identity)
    }

    private fun finishSession(
        session: Session,
        error: HotspotError? = null
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
        SSID_PREFIX + randomString(SSID_SUFFIX_LENGTH)

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

    data class ExistingGroupIdentity(
        val networkName: String,
        val isGroupOwner: Boolean
    ) {
        internal fun matches(group: HotspotP2p.Group): Boolean =
            networkName == group.networkName && isGroupOwner == group.isGroupOwner

        internal companion object {
            fun from(group: HotspotP2p.Group): ExistingGroupIdentity? =
                group.networkName?.let { ExistingGroupIdentity(it, group.isGroupOwner) }
        }
    }

    sealed interface ExistingGroupPolicy {
        data object RequireConfirmation : ExistingGroupPolicy

        data class ReplaceConfirmed(
            val group: ExistingGroupIdentity
        ) : ExistingGroupPolicy
    }

    interface HotspotCallback {
        fun onHotspotStarted()
        fun onConnectionInfoUpdated(info: ConnectionInfo?)
        fun onExistingGroupConflict(group: ExistingGroupIdentity)
        fun onError(error: HotspotError)
    }

    private fun ExistingGroupPolicy.canReplace(group: HotspotP2p.Group): Boolean =
        this is ExistingGroupPolicy.ReplaceConfirmed && this.group.matches(group)
}
