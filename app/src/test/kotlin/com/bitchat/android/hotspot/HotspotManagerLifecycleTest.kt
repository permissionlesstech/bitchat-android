package com.bitchat.android.hotspot

import android.net.wifi.p2p.WifiP2pManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.ArrayDeque

class HotspotManagerLifecycleTest {

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
        assertFalse(fixture.p2p.channels.first().closed)

        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(fixture.ownedGroup())
        assertEquals(0, completed)

        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)
        assertEquals(0, completed)

        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)

        assertEquals(1, completed)
        assertEquals("Closed", fixture.manager.lifecycleName())
        assertTrue(fixture.p2p.channels.first().closed)
    }

    @Test
    fun `stuck creation is actively closed and verified through a fresh channel`() {
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
        assertEquals(0, completed)

        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)

        assertEquals(1, completed)
        assertTrue(fixture.p2p.channels.last().closed)
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
        assertEquals(1, fixture.p2p.channels.size)
        assertFalse(fixture.p2p.channels.first().closed)

        fixture.p2p.acceptCreate()
        fixture.p2p.answerGroup(null)
        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)

        assertEquals(0, completed)
        assertEquals(0, fixture.p2p.removeRequests.size)

        val lateGroup = HotspotP2p.Group(
            "DIRECT-ab-Android",
            "generated-password",
            0,
            isGroupOwner = true
        )
        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(lateGroup)
        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(lateGroup)
        fixture.p2p.acceptRemove()
        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)
        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)

        assertEquals(1, completed)
        assertEquals("Closed", fixture.manager.lifecycleName())
    }

    @Test
    fun `late disconnect from the closed channel cannot replace the verification channel`() {
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
    fun `a hung verification query closes that channel and retries verification`() {
        val fixture = Fixture()
        fixture.startUntilCreateRequested()
        var completed = 0

        fixture.manager.stopHotspot { completed++ }
        fixture.scheduler.advanceBy(10_000)
        val firstVerificationChannel = fixture.p2p.channels.last()
        fixture.scheduler.advanceBy(10_000)

        assertTrue(firstVerificationChannel.closed)
        assertEquals(3, fixture.p2p.channels.size)
        assertEquals(0, completed)

        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null) // stale answer for the channel just closed
        fixture.p2p.answerGroup(null) // first answer for the current channel
        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)

        assertEquals(1, completed)
    }

    @Test
    fun `cleanup remains bounded after actively closing every unresponsive channel`() {
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
        assertTrue(
            "the marker is retained so a later session can remove a possible orphan",
            fixture.store.name?.startsWith(HotspotStartupPolicy.SSID_PREFIX) == true
        )
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
    fun `an active group disappearing lapses ownership without removing a replacement`() {
        val fixture = Fixture()
        fixture.startHosting()

        fixture.scheduler.advanceBy(1_000)
        fixture.p2p.answerGroup(null)
        fixture.scheduler.advanceBy(1_000)
        fixture.p2p.answerGroup(null)

        assertEquals("Closed", fixture.manager.lifecycleName())
        assertEquals(0, fixture.p2p.removeRequests.size)
        assertNull(fixture.store.name)
        assertEquals(listOf("The Wi-Fi Direct hotspot disconnected. Please try again."), fixture.callback.errors)
    }

    @Test
    fun `a foreign replacement is never passed to device scoped remove`() {
        val fixture = Fixture()
        fixture.startHosting()

        fixture.scheduler.advanceBy(1_000)
        fixture.p2p.answerGroup(
            HotspotP2p.Group(
                networkName = "DIRECT-xy-Chromecast",
                passphrase = "foreign-password",
                clientCount = 0,
                isGroupOwner = true
            )
        )

        assertEquals("Closed", fixture.manager.lifecycleName())
        assertEquals(0, fixture.p2p.removeRequests.size)
        assertNull(fixture.store.name)
    }

    @Test
    fun `a nameless group is not removed without exact ownership evidence`() {
        val fixture = Fixture()
        fixture.startHosting()

        fixture.manager.stopHotspot()
        fixture.p2p.answerGroup(
            HotspotP2p.Group(
                networkName = null,
                passphrase = null,
                clientCount = 0,
                isGroupOwner = true
            )
        )

        assertEquals(0, fixture.p2p.removeRequests.size)
        assertEquals("Stopping", fixture.manager.lifecycleName())

        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(fixture.ownedGroup())

        assertEquals(1, fixture.p2p.removeRequests.size)
    }

    @Test
    fun `an existing client-side group is foreign even when its name matches our marker`() {
        val fixture = Fixture()
        fixture.store.name = "DIRECT-BC-OLDGROUP"

        fixture.manager.startHotspot(fixture.callback)
        fixture.p2p.answerP2pState(WifiP2pManager.WIFI_P2P_STATE_ENABLED)
        fixture.p2p.answerGroup(
            HotspotP2p.Group(
                "DIRECT-BC-OLDGROUP",
                "foreign-password",
                0,
                isGroupOwner = false
            )
        )

        assertEquals("Closed", fixture.manager.lifecycleName())
        assertEquals(0, fixture.p2p.removeRequests.size)
        assertEquals(
            listOf(HotspotStartupPolicy.FOREIGN_GROUP_MESSAGE),
            fixture.callback.errors
        )
    }

    @Test
    fun `stale removal waits for observed absence before creating`() {
        val fixture = Fixture()
        fixture.store.name = "DIRECT-BC-OLDGROUP"

        fixture.manager.startHotspot(fixture.callback)
        fixture.p2p.answerP2pState(WifiP2pManager.WIFI_P2P_STATE_ENABLED)
        fixture.p2p.answerGroup(
            HotspotP2p.Group("DIRECT-BC-OLDGROUP", "old-password", 0, true)
        )
        fixture.p2p.acceptRemove()

        fixture.p2p.answerGroup(
            HotspotP2p.Group("DIRECT-BC-OLDGROUP", "old-password", 0, true)
        )
        assertEquals(0, fixture.p2p.createRequests.size)

        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)
        assertEquals(0, fixture.p2p.createRequests.size)

        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)
        assertEquals(1, fixture.p2p.createRequests.size)
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

        assertEquals(0, completed)
        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)
        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(null)

        assertEquals(1, completed)
    }

    @Test
    fun `pre Q group name must be stable before ownership is recorded`() {
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
            HotspotP2p.Group("DIRECT-ab-Android", "generated-password", 0, true)
        )

        assertEquals("Forming", fixture.manager.lifecycleName())
        assertNull(fixture.store.name)

        fixture.scheduler.advanceBy(1_000)
        fixture.p2p.answerGroup(
            HotspotP2p.Group("DIRECT-ab-Android", "generated-password", 0, true)
        )

        assertEquals("Hosting", fixture.manager.lifecycleName())
        assertEquals("DIRECT-ab-Android", fixture.store.name)
    }

    @Test
    fun `pre Q formation rejects a changed ownership candidate`() {
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
            HotspotP2p.Group("DIRECT-ab-First", "first-password", 0, true)
        )
        fixture.scheduler.advanceBy(1_000)
        fixture.p2p.answerGroup(
            HotspotP2p.Group("DIRECT-cd-Replacement", "foreign-password", 0, true)
        )

        assertEquals("Closed", fixture.manager.lifecycleName())
        assertNull(fixture.store.name)
        assertEquals(0, fixture.p2p.removeRequests.size)
        assertEquals(
            listOf(HotspotStartupPolicy.FOREIGN_GROUP_MESSAGE),
            fixture.callback.errors
        )
    }

    @Test
    fun `pre Q cleanup never removes a changed ownership candidate`() {
        val fixture = Fixture(
            p2p = FakeP2p(
                supportsP2pStateQuery = false,
                supportsCustomCredentials = false
            )
        )
        var completed = 0

        fixture.manager.startHotspot(fixture.callback)
        fixture.p2p.answerGroup(null)
        fixture.p2p.acceptCreate()
        fixture.manager.stopHotspot { completed++ }
        fixture.p2p.answerGroup(
            HotspotP2p.Group("DIRECT-ab-First", "first-password", 0, true)
        )
        fixture.scheduler.advanceBy(500)
        fixture.p2p.answerGroup(
            HotspotP2p.Group("DIRECT-cd-Replacement", "foreign-password", 0, true)
        )

        assertEquals(1, completed)
        assertEquals("Closed", fixture.manager.lifecycleName())
        assertNull(fixture.store.name)
        assertEquals(0, fixture.p2p.removeRequests.size)
    }

    @Test
    fun `pre Q client-side group is never adopted as the created hotspot`() {
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
                "DIRECT-ab-Android",
                "foreign-password",
                0,
                isGroupOwner = false
            )
        )

        assertEquals("Closed", fixture.manager.lifecycleName())
        assertNull(fixture.store.name)
        assertEquals(0, fixture.p2p.removeRequests.size)
    }

    private class Fixture(
        val p2p: FakeP2p = FakeP2p()
    ) {
        val scheduler = FakeScheduler()
        val platform = FakePlatform()
        val store = FakeOwnedGroupStore()
        val callback = RecordingCallback()
        val manager = HotspotManager(
            p2p = p2p,
            platform = platform,
            scheduler = scheduler,
            ownedGroups = store,
            random = SecureRandom(byteArrayOf(1, 2, 3, 4)),
            accessPointAddress = { "192.168.49.1" }
        )

        fun startUntilCreateRequested() {
            manager.startHotspot(callback)
            p2p.answerP2pState(WifiP2pManager.WIFI_P2P_STATE_ENABLED)
            p2p.answerGroup(null)
            assertEquals(1, p2p.createRequests.size)
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
            networkName = p2p.createRequests.firstOrNull()
                ?.credentials
                ?.networkName
                ?: store.name,
            passphrase = "test-password",
            clientCount = 0,
            isGroupOwner = true
        )
    }

    private class RecordingCallback : HotspotManager.HotspotCallback {
        var started = 0
        val errors = mutableListOf<String>()

        override fun onHotspotStarted() {
            started++
        }

        override fun onConnectionInfoUpdated(info: HotspotManager.ConnectionInfo?) = Unit

        override fun onError(message: String) {
            errors += message
        }
    }

    private class FakeOwnedGroupStore : OwnedGroupStore {
        override var name: String? = null
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

        fun acceptRemove() {
            removeRequests.removeFirst().onAccepted()
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
}
