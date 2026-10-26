package com.bitchat.android.nostr

import com.bitchat.android.geohash.LiveLocationPrivacyGate
import com.google.gson.Gson
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** All channels and relay locations in this suite are synthetic. No network is used. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GeoRelayRoutingTest {
    private val channel = "s0000000"
    private fun urls(start: Int = 1) = (start until start + 10).map { "wss://r$it.example" }

    @Test
    fun `shared golden vectors select the same bounded primary and backup set`() {
        val resource = javaClass.classLoader!!.getResourceAsStream("contracts/geo-relay-routing-vectors.json")!!
        val vectors = resource.reader().use { Gson().fromJson(it, Vectors::class.java) }
        try {
            vectors.cases.forEach { vector ->
                val csv = "relay url,lat,lon\n" + vector.entries.joinToString("\n") { "${it.host},${it.lat},${it.lon}" }
                RelayDirectory.replaceRelays(RelayDirectory.parseCsv(csv.byteInputStream()))
                assertEquals(vector.expected, RelayDirectory.routingRelaysForGeohash(vector.geohash, vector.primaryCount))
            }
        } finally {
            RelayDirectory.replaceRelays(emptyList())
        }
    }

    @Test
    fun `healthy peers publish and listen on backups and survive all primary failures`() = runTest {
        val factory = FakeFactory()
        val manager = NostrRelayManager(
            geoRelayLookup = { _, _ -> urls() }, socketFactory = { factory },
            dispatcher = StandardTestDispatcher(testScheduler), initialRelayUrls = emptyList(), observeDirectory = false
        )
        try {
            manager.connect()
            manager.subscribeForGeohash(channel, NostrFilter.geohashEphemeral(channel), "geo", {})
            runCurrent()
            assertEquals(urls().toSet(), factory.latest.keys)
            factory.latest.values.toList().forEach { it.open() }
            manager.sendEventToGeohash(event(), channel)
            runCurrent()
            factory.latest.values.forEach {
                assertTrue(it.messages.any { wire -> wire.startsWith("[\"REQ\",\"geo\"") })
                assertTrue(it.messages.any { wire -> wire.startsWith("[\"EVENT\"") })
            }
            urls().take(5).forEach { factory.latest.getValue(it).fail() }
            manager.sendEventToGeohash(event("during-outage"), channel)
            runCurrent()
            urls().drop(5).forEach {
                assertTrue(factory.latest.getValue(it).messages.any { wire -> wire.contains("during-outage") })
            }
            val attempts = factory.created.size
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(attempts + 5, factory.created.size)
            urls().take(5).forEach { factory.latest.getValue(it).open() }
            assertEquals(10, manager.getRelayStatuses().count { it.isConnected })
        } finally { manager.dispose() }
    }

    @Test
    fun `directory notifications move existing subscriptions and pending messages together`() = runTest {
        val factory = FakeFactory()
        var selected = urls()
        val manager = NostrRelayManager(
            geoRelayLookup = { _, _ -> selected }, socketFactory = { factory },
            dispatcher = StandardTestDispatcher(testScheduler), initialRelayUrls = emptyList()
        )
        try {
            manager.connect()
            val filter = NostrFilter.geohashEphemeral(channel, since = 123_000, limit = 7)
            manager.subscribeForGeohash(channel, filter, "same-subscription", {})
            manager.sendEventToGeohash(event("queued-before-refresh"), channel)
            runCurrent()
            val retired = factory.latest.getValue(urls().first())
            selected = urls(6)
            RelayDirectory.replaceRelays(listOf(RelayDirectory.RelayInfo("wss://synthetic.example", 0.0, 0.0)))
            runCurrent()
            assertEquals(selected.toSet(), manager.getActiveSubscriptions().getValue("same-subscription").targetRelayUrls)
            assertTrue(retired.closed)
            retired.open() // A late callback cannot restore a removed subscription.
            assertFalse(retired.messages.any { it.startsWith("[\"REQ\"") })
            selected.forEach { factory.latest.getValue(it).open() }
            runCurrent()
            selected.forEach {
                val wire = factory.latest.getValue(it).messages
                assertTrue(wire.any { message -> message.contains("same-subscription") && message.contains("\"since\":123") })
                assertTrue(wire.any { message -> message.contains("queued-before-refresh") })
            }
        } finally {
            manager.dispose()
            RelayDirectory.replaceRelays(emptyList())
        }
    }

    @Test
    fun `an initially empty directory preserves geo intent without publishing to default relays`() = runTest {
        val factory = FakeFactory()
        var selected = emptyList<String>()
        val manager = NostrRelayManager(
            geoRelayLookup = { _, _ -> selected }, socketFactory = { factory },
            dispatcher = StandardTestDispatcher(testScheduler), initialRelayUrls = emptyList(), observeDirectory = false
        )
        try {
            manager.connect()
            manager.subscribeForGeohash(channel, NostrFilter.geohashEphemeral(channel), "waiting", {})
            manager.sendEventToGeohash(event("waiting-for-directory"), channel)
            runCurrent()
            assertTrue(factory.created.isEmpty())
            selected = urls()
            manager.reconcileGeoRelayDirectory()
            runCurrent()
            selected.forEach { factory.latest.getValue(it).open() }
            assertTrue(factory.latest.values.all { socket -> socket.messages.any { it.contains("waiting-for-directory") } })
        } finally { manager.dispose() }
    }

    @Test
    fun `refresh after location revocation cannot recreate live subscriptions or queued events`() = runTest {
        LiveLocationPrivacyGate.update(true)
        val token = requireNotNull(LiveLocationPrivacyGate.captureToken())
        val factory = FakeFactory()
        var selected = urls()
        val manager = NostrRelayManager(
            geoRelayLookup = { _, _ -> selected }, socketFactory = { factory },
            dispatcher = StandardTestDispatcher(testScheduler), initialRelayUrls = emptyList(), observeDirectory = false
        )
        try {
            manager.connect()
            manager.subscribeForGeohash(channel, NostrFilter.geohashEphemeral(channel), "live", {}, liveLocationToken = token)
            manager.sendEventToGeohash(event(), channel, liveLocationToken = token)
            runCurrent()
            val old = factory.created.toList()
            LiveLocationPrivacyGate.update(false)
            selected = urls(20)
            manager.reconcileGeoRelayDirectory()
            runCurrent()
            old.forEach { it.open() }
            assertEquals(10, factory.created.size)
            assertTrue(manager.getActiveSubscriptions().isEmpty())
            assertTrue(manager.getRelaysForGeohash(channel).isEmpty())
            assertTrue(old.all { it.closed && it.messages.none { wire -> wire.startsWith("[\"EVENT\"") } })
        } finally {
            manager.dispose()
            LiveLocationPrivacyGate.update(false)
        }
    }

    private fun event(content: String = "synthetic-message") = NostrEvent(
        id = content, pubkey = "0".repeat(64), createdAt = 1, kind = 20000,
        tags = listOf(listOf("g", channel)), content = content
    )

    @Test
    fun `refresh preserves a manually selected subscription sharing revoked live relays`() = runTest {
        LiveLocationPrivacyGate.update(true)
        val token = requireNotNull(LiveLocationPrivacyGate.captureToken())
        val factory = FakeFactory()
        var selected = urls()
        val manager = NostrRelayManager(
            geoRelayLookup = { _, _ -> selected }, socketFactory = { factory },
            dispatcher = StandardTestDispatcher(testScheduler), initialRelayUrls = emptyList(), observeDirectory = false
        )
        try {
            manager.connect()
            manager.subscribeForGeohash(channel, NostrFilter.geohashEphemeral(channel), "live", {}, liveLocationToken = token)
            runCurrent()
            manager.subscribeForGeohash(channel, NostrFilter.geohashEphemeral(channel), "manual", {})
            LiveLocationPrivacyGate.update(false)
            selected = urls(6)
            manager.reconcileGeoRelayDirectory()
            runCurrent()
            selected.forEach { factory.latest.getValue(it).open() }
            runCurrent()
            assertEquals(setOf("manual"), manager.getActiveSubscriptions().keys)
            assertTrue(selected.all { url -> factory.latest.getValue(url).messages.any { it.contains("manual") } })
            assertEquals(selected.toSet(), manager.getActiveSubscriptions().getValue("manual").targetRelayUrls)
        } finally {
            manager.dispose()
            LiveLocationPrivacyGate.update(false)
        }
    }

    @Test
    fun `refresh cannot recreate a channel after its owner unsubscribes`() = runTest {
        val factory = FakeFactory()
        var selected = urls()
        val manager = NostrRelayManager(
            geoRelayLookup = { _, _ -> selected }, socketFactory = { factory },
            dispatcher = StandardTestDispatcher(testScheduler), initialRelayUrls = emptyList(), observeDirectory = false
        )
        try {
            manager.connect()
            manager.subscribeForGeohash(channel, NostrFilter.geohashEphemeral(channel), "removed", {})
            runCurrent()
            manager.unsubscribe("removed")
            selected = urls(20)
            manager.reconcileGeoRelayDirectory()
            runCurrent()
            factory.created.forEach { it.open() }
            assertEquals(10, factory.created.size)
            assertTrue(manager.getActiveSubscriptions().isEmpty())
            assertTrue(factory.created.all { it.closed && it.messages.none { wire -> wire.startsWith("[\"REQ\"") } })
        } finally { manager.dispose() }
    }

    @Test
    fun `a delayed publish follows migration even when an old socket remains shared`() = runTest {
        val factory = FakeFactory()
        var selected = urls()
        val manager = NostrRelayManager(
            geoRelayLookup = { _, _ -> selected }, socketFactory = { factory },
            dispatcher = StandardTestDispatcher(testScheduler), initialRelayUrls = emptyList(), observeDirectory = false
        )
        try {
            manager.connect()
            manager.subscribeForGeohash(channel, NostrFilter.geohashEphemeral(channel), "geo", {})
            manager.subscribe(NostrFilter(kinds = listOf(1059)), "private", {}, targetRelayUrls = listOf(urls().first()))
            runCurrent()
            val shared = factory.latest.getValue(urls().first())
            shared.open()
            manager.sendEventToGeohash(event("move-before-write"), channel)
            selected = urls(6)
            manager.reconcileGeoRelayDirectory()
            runCurrent()
            assertFalse(shared.closed)
            assertTrue(shared.messages.any { it.contains("private") })
            assertFalse(shared.messages.any { it.contains("move-before-write") })
            selected.forEach { factory.latest.getValue(it).open() }
            assertTrue(selected.all { url -> factory.latest.getValue(url).messages.any { it.contains("move-before-write") } })
        } finally { manager.dispose() }
    }

    private data class Vectors(val cases: List<Vector>)
    private data class Vector(val geohash: String, val primaryCount: Int, val entries: List<Entry>, val expected: List<String>)
    private data class Entry(val host: String, val lat: Double, val lon: Double)

    private class FakeFactory : WebSocket.Factory {
        val latest = linkedMapOf<String, FakeSocket>()
        val created = mutableListOf<FakeSocket>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            FakeSocket(request, listener).also {
                latest[request.url.toString().replace("https://", "wss://").removeSuffix("/")] = it
                created.add(it)
            }
    }

    private class FakeSocket(private val req: Request, private val listener: WebSocketListener) : WebSocket {
        val messages = mutableListOf<String>()
        var closed = false
        override fun request() = req
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { messages.add(text); return !closed }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?): Boolean { closed = true; return true }
        override fun cancel() { closed = true }
        fun open() = listener.onOpen(this, Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(101).message("Synthetic").build())
        fun fail() = listener.onFailure(this, IOException("Synthetic DNS outage"), null)
    }
}
