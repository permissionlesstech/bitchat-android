package com.bitchat.android.hotspot

import android.net.wifi.p2p.WifiP2pManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.ArrayDeque

class HotspotManagerLifecycleTest {

    @Test
    fun `missing P2P state callback fails the startup preflight`() {
        val fixture = Fixture()

        fixture.manager.startHotspot(fixture.callback)
        fixture.scheduler.advanceBy(10_000)

        assertEquals("Closed", fixture.manager.lifecycleName())
        assertFalse(fixture.platform.active)
        assertTrue(fixture.p2p.channels.first().closed)
        assertEquals(listOf(HotspotError.PREFLIGHT_TIMEOUT), fixture.callback.errors)

        fixture.p2p.answerP2pState(WifiP2pManager.WIFI_P2P_STATE_ENABLED)
        assertEquals(0, fixture.p2p.groupRequests.size)
    }

    @Test
    fun `missing initial group callback fails the startup preflight`() {
        val fixture = Fixture()

        fixture.manager.startHotspot(fixture.callback)
        fixture.p2p.answerP2pState(WifiP2pManager.WIFI_P2P_STATE_ENABLED)
        fixture.scheduler.advanceBy(10_000)

        assertEquals("Closed", fixture.manager.lifecycleName())
        assertFalse(fixture.platform.active)
        assertEquals(listOf(HotspotError.PREFLIGHT_TIMEOUT), fixture.callback.errors)

        fixture.p2p.answerGroup(null)
        assertEquals(0, fixture.p2p.createRequests.size)
    }

    @Test
    fun `existing group requires confirmation and is never removed implicitly`() {
        val fixture = Fixture()

        fixture.manager.startHotspot(fixture.callback)
        fixture.p2p.answerP2pState(WifiP2pManager.WIFI_P2P_STATE_ENABLED)
        fixture.p2p.answerGroup(foreignGroup())

        assertEquals("Closed", fixture.manager.lifecycleName())
        assertEquals(1, fixture.callback.conflicts)
        assertEquals(
            HotspotManager.ExistingGroupIdentity(
                networkName = "DIRECT-xy-Chromecast",
                isGroupOwner = true
            ),
            fixture.callback.conflictGroups.single()
        )
        assertEquals(0, fixture.callback.errors.size)
        assertEquals(0, fixture.p2p.removeRequests.size)
        assertFalse(fixture.platform.active)
    }

    @Test
    fun `replacement consent does not authorize a different group`() {
        val fixture = Fixture()
        fixture.manager.startHotspot(
            fixture.callback,
            HotspotManager.ExistingGroupPolicy.ReplaceConfirmed(
                HotspotManager.ExistingGroupIdentity(
                    networkName = "DIRECT-ab-Android",
                    isGroupOwner = true
                )
            )
        )

        fixture.p2p.answerP2pState(WifiP2pManager.WIFI_P2P_STATE_ENABLED)
        fixture.p2p.answerGroup(foreignGroup())

        assertEquals("Closed", fixture.manager.lifecycleName())
        assertEquals(1, fixture.callback.conflicts)
        assertEquals(
            "DIRECT-xy-Chromecast",
            fixture.callback.conflictGroups.single().networkName
        )
        assertEquals(0, fixture.p2p.removeRequests.size)
    }

    @Test
    fun `confirmed replacement waits for observed absence before creating`() {
        val fixture = Fixture()
        fixture.startReplacingExistingGroup()

        fixture.p2p.acceptRemove()
        fixture.p2p.answerGroup(foreignGroup())
        assertEquals(0, fixture.p2p.createRequests.size)

        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)
        assertEquals(0, fixture.p2p.createRequests.size)

        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)

        assertEquals(1, fixture.p2p.createRequests.size)
        assertEquals("Creating", fixture.manager.lifecycleName())
    }

    @Test
    fun `confirmed replacement retries a rejected removal while a group remains`() {
        val fixture = Fixture()
        fixture.startReplacingExistingGroup()

        fixture.p2p.rejectRemove(WifiP2pManager.BUSY)
        fixture.p2p.answerGroup(foreignGroup())
        fixture.scheduler.advanceBy(500)
        assertEquals(0, fixture.p2p.removeRequests.size)

        fixture.p2p.answerGroup(foreignGroup())

        assertEquals(1, fixture.p2p.removeRequests.size)
        assertEquals("Starting", fixture.manager.lifecycleName())
    }

    @Test
    fun `stale replacement timer cannot overwrite group creation`() {
        val fixture = Fixture()
        fixture.startReplacingExistingGroup()

        fixture.p2p.acceptRemove()
        fixture.p2p.answerGroup(null)
        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(foreignGroup())

        fixture.platform.onConnectionChanged?.invoke()
        fixture.p2p.answerGroup(null)
        assertEquals("Creating", fixture.manager.lifecycleName())

        fixture.scheduler.advanceBy(500)
        assertEquals("Creating", fixture.manager.lifecycleName())

        fixture.p2p.acceptCreate()
        fixture.scheduler.runCurrent()
        fixture.p2p.answerGroup(fixture.ownedGroup())

        assertEquals("Hosting", fixture.manager.lifecycleName())
        assertEquals(1, fixture.callback.started)
        assertEquals(0, fixture.callback.conflicts)
    }

    @Test
    fun `stop during confirmed replacement ignores its late callback`() {
        val fixture = Fixture()
        fixture.startReplacingExistingGroup()
        var completed = 0

        fixture.manager.stopHotspot { completed++ }
        fixture.p2p.acceptRemove()

        assertEquals(1, completed)
        assertEquals("Closed", fixture.manager.lifecycleName())
        assertEquals(0, fixture.p2p.createRequests.size)
        assertEquals(0, fixture.p2p.groupRequests.size)
    }

    @Test
    fun `remove command acceptance is not treated as teardown completion`() {
        val fixture = Fixture()
        fixture.startHosting()
        var completed = 0

        fixture.manager.stopHotspot { completed++ }
        fixture.p2p.answerGroup(fixture.ownedGroup())
        fixture.p2p.acceptRemove()

        assertEquals(0, completed)
        assertEquals("Stopping", fixture.manager.lifecycleName())

        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(fixture.ownedGroup())
        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)
        assertEquals(0, completed)
        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)

        assertEquals(1, completed)
        assertEquals("Closed", fixture.manager.lifecycleName())
    }

    @Test
    fun `stuck creation is closed and verified through a fresh channel`() {
        val fixture = Fixture()
        fixture.startUntilCreateRequested()
        var completed = 0

        fixture.manager.stopHotspot { completed++ }
        fixture.scheduler.advanceBy(10_000)

        assertEquals(0, completed)
        assertTrue(fixture.p2p.channels.first().closed)
        assertEquals(2, fixture.p2p.channels.size)

        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)
        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)

        assertEquals(1, completed)
        assertTrue(fixture.p2p.channels.last().closed)
    }

    @Test
    fun `missing create callback enters bounded cleanup`() {
        val fixture = Fixture()
        fixture.startUntilCreateRequested()

        fixture.scheduler.advanceBy(15_000)
        assertEquals("Stopping", fixture.manager.lifecycleName())
        assertTrue(fixture.callback.errors.isEmpty())

        fixture.scheduler.advanceBy(10_000)
        assertEquals(2, fixture.p2p.channels.size)
        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)
        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)

        assertEquals("Closed", fixture.manager.lifecycleName())
        assertFalse(fixture.platform.active)
        assertEquals(listOf(HotspotError.START_FAILED), fixture.callback.errors)
    }

    @Test
    fun `API 26 keeps pending creation alive until its late group is removed`() {
        val fixture = Fixture(
            p2p = FakeP2p(
                supportsP2pStateQuery = false,
                supportsCustomCredentials = false,
                supportsChannelClose = false
            )
        )
        var completed = 0

        fixture.manager.startHotspot(fixture.callback)
        fixture.p2p.answerGroup(null)
        fixture.manager.stopHotspot { completed++ }
        fixture.scheduler.advanceBy(10_000)

        assertEquals(0, completed)
        assertFalse(fixture.p2p.channels.first().closed)

        fixture.p2p.acceptCreate()
        fixture.p2p.answerGroup(null)
        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(livePreQGroup())
        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(livePreQGroup())
        fixture.p2p.acceptRemove()
        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)
        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)

        assertEquals(1, completed)
        assertEquals("Closed", fixture.manager.lifecycleName())
    }

    @Test
    fun `API 26 P2P disable finishes when create callback is dropped`() {
        val fixture = Fixture(
            p2p = FakeP2p(
                supportsP2pStateQuery = false,
                supportsCustomCredentials = false,
                supportsChannelClose = false
            )
        )

        fixture.manager.startHotspot(fixture.callback)
        fixture.p2p.answerGroup(null)
        fixture.platform.onStateChanged?.invoke(WifiP2pManager.WIFI_P2P_STATE_DISABLED)

        assertEquals("Closed", fixture.manager.lifecycleName())
        assertFalse(fixture.platform.active)
        assertEquals(listOf(HotspotError.P2P_DISABLED), fixture.callback.errors)
    }

    @Test
    fun `API 26 accepted creation that never forms has bounded cleanup`() {
        val fixture = Fixture(
            p2p = FakeP2p(
                supportsP2pStateQuery = false,
                supportsCustomCredentials = false,
                supportsChannelClose = false
            )
        )

        fixture.manager.startHotspot(fixture.callback)
        fixture.p2p.answerGroup(null)
        fixture.p2p.acceptCreate()
        fixture.scheduler.runCurrent()

        fixture.scheduler.advanceBy(15_000)
        assertEquals("Stopping", fixture.manager.lifecycleName())

        // Discard the stale formation response, then report no group to cleanup.
        fixture.p2p.answerGroup(null)
        fixture.p2p.answerGroup(null)
        fixture.scheduler.advanceBy(10_000)

        assertEquals("Closed", fixture.manager.lifecycleName())
        assertFalse(fixture.platform.active)
        assertEquals(listOf(HotspotError.START_FAILED), fixture.callback.errors)
        assertEquals(0, fixture.p2p.removeRequests.size)
    }

    @Test
    fun `API 26 rejected cleanup removal survives the formation deadline`() {
        val fixture = Fixture(
            p2p = FakeP2p(
                supportsP2pStateQuery = false,
                supportsCustomCredentials = false,
                supportsChannelClose = false
            )
        )

        fixture.manager.startHotspot(fixture.callback)
        fixture.p2p.answerGroup(null)
        fixture.p2p.acceptCreate()
        fixture.scheduler.runCurrent()
        fixture.manager.stopHotspot()

        // Discard the stale formation response, then establish stable cleanup ownership.
        fixture.p2p.answerGroup(livePreQGroup())
        fixture.p2p.answerGroup(livePreQGroup())
        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(livePreQGroup())
        fixture.p2p.rejectRemove(WifiP2pManager.BUSY)

        fixture.scheduler.advanceBy(9_500)

        assertEquals("Stopping", fixture.manager.lifecycleName())
        assertTrue(fixture.platform.active)
        assertTrue(fixture.callback.errors.isEmpty())
        assertEquals(2, fixture.p2p.channels.size)
    }

    @Test
    fun `API 26 cleanup finishes after a candidate group disappears`() {
        val fixture = Fixture(
            p2p = FakeP2p(
                supportsP2pStateQuery = false,
                supportsCustomCredentials = false,
                supportsChannelClose = false
            )
        )
        var completed = 0

        fixture.manager.startHotspot(fixture.callback)
        fixture.p2p.answerGroup(null)
        fixture.p2p.acceptCreate()
        fixture.scheduler.runCurrent()
        fixture.p2p.answerGroup(livePreQGroup())
        fixture.manager.stopHotspot { completed++ }

        fixture.p2p.answerGroup(null)
        assertEquals(0, completed)
        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)

        assertEquals(1, completed)
        assertEquals("Closed", fixture.manager.lifecycleName())
        assertFalse(fixture.platform.active)
        assertEquals(0, fixture.p2p.removeRequests.size)
    }

    @Test
    fun `API 26 cleanup keeps candidate ownership after one absent snapshot`() {
        val fixture = Fixture(
            p2p = FakeP2p(
                supportsP2pStateQuery = false,
                supportsCustomCredentials = false,
                supportsChannelClose = false
            )
        )

        fixture.manager.startHotspot(fixture.callback)
        fixture.p2p.answerGroup(null)
        fixture.p2p.acceptCreate()
        fixture.scheduler.runCurrent()
        fixture.p2p.answerGroup(livePreQGroup())
        fixture.manager.stopHotspot()

        fixture.p2p.answerGroup(null)
        fixture.scheduler.advanceBy(10_000)

        assertEquals("Stopping", fixture.manager.lifecycleName())
        assertTrue(fixture.platform.active)
        assertTrue(fixture.callback.errors.isEmpty())
        assertEquals(1, fixture.p2p.channels.size)
    }

    @Test
    fun `fresh channel does not adopt an unknown pre Q group`() {
        val fixture = Fixture(
            p2p = FakeP2p(
                supportsP2pStateQuery = false,
                supportsCustomCredentials = false
            )
        )
        var completed = 0

        fixture.manager.startHotspot(fixture.callback)
        fixture.p2p.answerGroup(null)
        fixture.manager.stopHotspot { completed++ }
        fixture.scheduler.advanceBy(10_000)

        assertEquals(2, fixture.p2p.channels.size)
        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(livePreQGroup())

        assertEquals(1, completed)
        assertEquals("Closed", fixture.manager.lifecycleName())
        assertEquals(0, fixture.p2p.removeRequests.size)
    }

    @Test
    fun `stop during create retry backoff completes immediately`() {
        val fixture = Fixture(
            p2p = FakeP2p(
                supportsP2pStateQuery = false,
                supportsCustomCredentials = false,
                supportsChannelClose = false
            )
        )
        var completed = 0

        fixture.manager.startHotspot(fixture.callback)
        fixture.p2p.answerGroup(null)
        fixture.p2p.rejectCreate(WifiP2pManager.BUSY)
        fixture.manager.stopHotspot { completed++ }

        assertEquals(1, completed)
        fixture.scheduler.advanceBy(1_000)
        assertEquals(0, fixture.p2p.createRequests.size)
    }

    @Test
    fun `late disconnect from closed channel cannot replace verification channel`() {
        val fixture = Fixture()
        fixture.startUntilCreateRequested()

        fixture.manager.stopHotspot()
        fixture.scheduler.advanceBy(10_000)
        val verificationChannel = fixture.p2p.channels.last()
        fixture.p2p.channels.first().disconnect()

        assertEquals(2, fixture.p2p.channels.size)
        assertFalse(verificationChannel.closed)
        assertEquals("Stopping", fixture.manager.lifecycleName())
    }

    @Test
    fun `hung verification query closes that channel and retries`() {
        val fixture = Fixture()
        fixture.startUntilCreateRequested()
        var completed = 0

        fixture.manager.stopHotspot { completed++ }
        fixture.scheduler.advanceBy(10_000)
        val firstVerificationChannel = fixture.p2p.channels.last()
        fixture.scheduler.advanceBy(10_000)

        assertTrue(firstVerificationChannel.closed)
        assertEquals(3, fixture.p2p.channels.size)

        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)
        fixture.p2p.answerGroup(null)
        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)

        assertEquals(1, completed)
    }

    @Test
    fun `cleanup remains bounded after unresponsive verification channels`() {
        val fixture = Fixture()
        fixture.startUntilCreateRequested()
        var completed = 0

        fixture.manager.stopHotspot { completed++ }
        repeat(6) {
            fixture.scheduler.advanceBy(10_000)
        }

        assertEquals(1, completed)
        assertEquals("Closed", fixture.manager.lifecycleName())
        assertTrue(fixture.p2p.channels.all { it.closed })
        assertFalse(fixture.platform.active)
    }

    @Test
    fun `repeated stops join one teardown and complete every waiter once`() {
        val fixture = Fixture()
        fixture.startHosting()
        var first = 0
        var second = 0

        fixture.manager.stopHotspot { first++ }
        fixture.manager.stopHotspot { second++ }
        fixture.p2p.answerGroup(null)
        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)

        assertEquals(1, first)
        assertEquals(1, second)
        assertEquals(0, fixture.p2p.removeRequests.size)
    }

    @Test
    fun `active group disappearance closes without removing a replacement`() {
        val fixture = Fixture()
        fixture.startHosting()

        fixture.scheduler.advanceBy(1_000)
        fixture.p2p.answerGroup(null)
        fixture.scheduler.advanceBy(1_000)
        fixture.p2p.answerGroup(null)

        assertEquals("Closed", fixture.manager.lifecycleName())
        assertEquals(0, fixture.p2p.removeRequests.size)
        assertEquals(listOf(HotspotError.GROUP_LOST), fixture.callback.errors)
    }

    @Test
    fun `nameless owner snapshot does not end hosting`() {
        val fixture = Fixture()
        fixture.startHosting()

        fixture.scheduler.advanceBy(1_000)
        fixture.p2p.answerGroup(
            HotspotP2p.Group(
                networkName = null,
                passphrase = "test-password",
                clientCount = 0,
                isGroupOwner = true
            )
        )

        assertEquals("Hosting", fixture.manager.lifecycleName())
        assertTrue(fixture.callback.errors.isEmpty())
        assertTrue(fixture.platform.active)

        fixture.scheduler.advanceBy(1_000)
        fixture.p2p.answerGroup(fixture.ownedGroup())
        assertEquals("Hosting", fixture.manager.lifecycleName())
    }

    @Test
    fun `foreign replacement is never passed to device scoped remove`() {
        val fixture = Fixture()
        fixture.startHosting()

        fixture.scheduler.advanceBy(1_000)
        fixture.p2p.answerGroup(foreignGroup())

        assertEquals("Closed", fixture.manager.lifecycleName())
        assertEquals(0, fixture.p2p.removeRequests.size)
    }

    @Test
    fun `stop before create callback removes a late group before completing`() {
        val fixture = Fixture()
        fixture.startUntilCreateRequested()
        var completed = 0

        fixture.manager.stopHotspot { completed++ }
        fixture.p2p.acceptCreate()
        fixture.p2p.answerGroup(fixture.ownedGroup())
        fixture.p2p.acceptRemove()
        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)
        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)

        assertEquals(1, completed)
    }

    @Test
    fun `pre Q group identity is kept only for the live session`() {
        val fixture = Fixture(
            p2p = FakeP2p(
                supportsP2pStateQuery = false,
                supportsCustomCredentials = false
            )
        )

        fixture.manager.startHotspot(fixture.callback)
        fixture.p2p.answerGroup(null)
        fixture.p2p.acceptCreate()
        fixture.scheduler.runCurrent()
        fixture.p2p.answerGroup(livePreQGroup())

        assertEquals("Forming", fixture.manager.lifecycleName())
        assertEquals(0, fixture.callback.started)

        fixture.scheduler.advanceBy(1_000)
        fixture.p2p.answerGroup(livePreQGroup())

        assertEquals("Hosting", fixture.manager.lifecycleName())
        assertEquals(1, fixture.callback.started)

        fixture.manager.stopHotspot()
        fixture.p2p.answerGroup(foreignGroup())

        assertEquals("Closed", fixture.manager.lifecycleName())
        assertEquals(0, fixture.p2p.removeRequests.size)
    }

    @Test
    fun `pre Q ownership candidate must remain stable before hosting`() {
        val fixture = Fixture(
            p2p = FakeP2p(
                supportsP2pStateQuery = false,
                supportsCustomCredentials = false
            )
        )

        fixture.manager.startHotspot(fixture.callback)
        fixture.p2p.answerGroup(null)
        fixture.p2p.acceptCreate()
        fixture.scheduler.runCurrent()
        fixture.p2p.answerGroup(livePreQGroup())
        fixture.scheduler.advanceBy(1_000)
        fixture.p2p.answerGroup(foreignGroup())

        assertEquals("Closed", fixture.manager.lifecycleName())
        assertEquals(0, fixture.callback.started)
        assertEquals(1, fixture.callback.conflicts)
        assertEquals(0, fixture.p2p.removeRequests.size)
    }

    @Test
    fun `pre Q client group after create is surfaced as a conflict`() {
        val fixture = Fixture(
            p2p = FakeP2p(
                supportsP2pStateQuery = false,
                supportsCustomCredentials = false
            )
        )

        fixture.manager.startHotspot(fixture.callback)
        fixture.p2p.answerGroup(null)
        fixture.p2p.acceptCreate()
        fixture.scheduler.runCurrent()
        fixture.p2p.answerGroup(
            HotspotP2p.Group(
                networkName = "DIRECT-ab-Android",
                passphrase = "foreign-password",
                clientCount = 0,
                isGroupOwner = false
            )
        )

        assertEquals("Closed", fixture.manager.lifecycleName())
        assertEquals(1, fixture.callback.conflicts)
        assertEquals(0, fixture.p2p.removeRequests.size)
    }

    private class Fixture(
        val p2p: FakeP2p = FakeP2p()
    ) {
        val scheduler = FakeScheduler()
        val platform = FakePlatform()
        val callback = RecordingCallback()
        val manager = HotspotManager(
            p2p = p2p,
            platform = platform,
            scheduler = scheduler,
            random = SecureRandom(byteArrayOf(1, 2, 3, 4)),
            accessPointAddress = { "192.168.49.1" }
        )

        fun startUntilCreateRequested(
            policy: HotspotManager.ExistingGroupPolicy =
                HotspotManager.ExistingGroupPolicy.RequireConfirmation
        ) {
            manager.startHotspot(callback, policy)
            p2p.answerP2pState(WifiP2pManager.WIFI_P2P_STATE_ENABLED)
            p2p.answerGroup(null)
            assertEquals(1, p2p.createRequests.size)
        }

        fun startReplacingExistingGroup() {
            manager.startHotspot(
                callback,
                HotspotManager.ExistingGroupPolicy.ReplaceConfirmed(
                    HotspotManager.ExistingGroupIdentity(
                        networkName = "DIRECT-xy-Chromecast",
                        isGroupOwner = true
                    )
                )
            )
            p2p.answerP2pState(WifiP2pManager.WIFI_P2P_STATE_ENABLED)
            p2p.answerGroup(foreignGroup())
            assertEquals(1, p2p.removeRequests.size)
        }

        fun startHosting() {
            startUntilCreateRequested()
            p2p.acceptCreate()
            scheduler.runCurrent()
            p2p.answerGroup(ownedGroup())
            assertEquals("Hosting", manager.lifecycleName())
            assertEquals(1, callback.started)
        }

        fun ownedGroup() = HotspotP2p.Group(
            networkName = p2p.submittedCredentials.last()?.networkName,
            passphrase = "test-password",
            clientCount = 0,
            isGroupOwner = true
        )
    }

    private class RecordingCallback : HotspotManager.HotspotCallback {
        var started = 0
        var conflicts = 0
        val conflictGroups = mutableListOf<HotspotManager.ExistingGroupIdentity>()
        val errors = mutableListOf<HotspotError>()

        override fun onHotspotStarted() {
            started++
        }

        override fun onConnectionInfoUpdated(info: HotspotManager.ConnectionInfo?) = Unit

        override fun onExistingGroupConflict(group: HotspotManager.ExistingGroupIdentity) {
            conflicts++
            conflictGroups += group
        }

        override fun onError(error: HotspotError) {
            errors += error
        }
    }

    private class FakePlatform : HotspotPlatform {
        var active = false
        var onStateChanged: ((Int) -> Unit)? = null
        var onConnectionChanged: (() -> Unit)? = null

        override fun missingPermissions(): Set<String> = emptySet()

        override fun activate(
            onP2pStateChanged: (Int) -> Unit,
            onConnectionChanged: () -> Unit
        ) {
            active = true
            this.onStateChanged = onP2pStateChanged
            this.onConnectionChanged = onConnectionChanged
        }

        override fun deactivate() {
            active = false
        }
    }

    private class FakeP2p(
        override val supportsP2pStateQuery: Boolean = true,
        override val supportsCustomCredentials: Boolean = true,
        override val supportsChannelClose: Boolean = true
    ) : HotspotP2p {
        override val available: Boolean = true

        data class CreateRequest(
            val credentials: HotspotP2p.Credentials?,
            val callback: HotspotP2p.ActionCallback
        )

        val channels = mutableListOf<FakeChannel>()
        val p2pStateRequests = ArrayDeque<(Int) -> Unit>()
        val groupRequests = ArrayDeque<(HotspotP2p.Group?) -> Unit>()
        val createRequests = ArrayDeque<CreateRequest>()
        val removeRequests = ArrayDeque<HotspotP2p.ActionCallback>()
        val submittedCredentials = mutableListOf<HotspotP2p.Credentials?>()

        override fun initialize(onDisconnected: () -> Unit): HotspotP2p.Channel =
            FakeChannel(onDisconnected, supportsChannelClose).also(channels::add)

        override fun requestP2pState(
            channel: HotspotP2p.Channel,
            callback: (Int) -> Unit
        ) {
            p2pStateRequests += callback
        }

        override fun requestGroup(
            channel: HotspotP2p.Channel,
            callback: (HotspotP2p.Group?) -> Unit
        ) {
            groupRequests += callback
        }

        override fun createGroup(
            channel: HotspotP2p.Channel,
            credentials: HotspotP2p.Credentials?,
            callback: HotspotP2p.ActionCallback
        ) {
            submittedCredentials += credentials
            createRequests += CreateRequest(credentials, callback)
        }

        override fun removeGroup(
            channel: HotspotP2p.Channel,
            callback: HotspotP2p.ActionCallback
        ) {
            removeRequests += callback
        }

        fun answerP2pState(state: Int) {
            p2pStateRequests.removeFirst().invoke(state)
        }

        fun answerGroup(group: HotspotP2p.Group?) {
            groupRequests.removeFirst().invoke(group)
        }

        fun acceptCreate() {
            createRequests.removeFirst().callback.onAccepted()
        }

        fun rejectCreate(reason: Int) {
            createRequests.removeFirst().callback.onRejected(reason)
        }

        fun acceptRemove() {
            removeRequests.removeFirst().onAccepted()
        }

        fun rejectRemove(reason: Int) {
            removeRequests.removeFirst().onRejected(reason)
        }
    }

    private class FakeChannel(
        private val disconnected: () -> Unit,
        private val supportsClose: Boolean
    ) : HotspotP2p.Channel {
        var closed = false

        override fun close() {
            if (supportsClose) {
                closed = true
            }
        }

        fun disconnect() {
            disconnected()
        }
    }

    private class FakeScheduler : HotspotScheduler {
        private data class Scheduled(
            val dueAt: Long,
            val order: Long,
            val action: () -> Unit,
            var cancelled: Boolean = false
        ) : HotspotScheduler.Task {
            override fun cancel() {
                cancelled = true
            }
        }

        private val scheduled = mutableListOf<Scheduled>()
        private var now = 0L
        private var nextOrder = 0L

        override fun schedule(
            delayMillis: Long,
            action: () -> Unit
        ): HotspotScheduler.Task = Scheduled(
            dueAt = now + delayMillis,
            order = nextOrder++,
            action = action
        ).also(scheduled::add)

        fun runCurrent() {
            advanceBy(0)
        }

        fun advanceBy(millis: Long) {
            val target = now + millis
            while (true) {
                val next = scheduled
                    .asSequence()
                    .filter { !it.cancelled && it.dueAt <= target }
                    .minWithOrNull(compareBy<Scheduled> { it.dueAt }.thenBy { it.order })
                    ?: break
                next.cancelled = true
                now = next.dueAt
                next.action()
            }
            now = target
        }
    }

    private companion object {
        fun foreignGroup() = HotspotP2p.Group(
            networkName = "DIRECT-xy-Chromecast",
            passphrase = "foreign-password",
            clientCount = 0,
            isGroupOwner = true
        )

        fun livePreQGroup() = HotspotP2p.Group(
            networkName = "DIRECT-ab-Android",
            passphrase = "generated-password",
            clientCount = 0,
            isGroupOwner = true
        )
    }
}
