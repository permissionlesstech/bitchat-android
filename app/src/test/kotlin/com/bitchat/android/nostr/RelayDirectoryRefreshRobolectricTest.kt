package com.bitchat.android.nostr

import android.app.Application
import android.content.Context
import android.content.res.AssetManager
import java.io.IOException
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.net.OkHttpProvider
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RelayDirectoryRefreshRobolectricTest {
    @get:Rule val temporary = TemporaryFolder()
    private val application = mock<Application>()
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("relay-refresh-test", Context.MODE_PRIVATE)
    private val scheduler = TestCoroutineScheduler()
    private val refreshScope = TestScope(StandardTestDispatcher(scheduler))
    private var requests = 0
    private val now = 172_800_000L
    private lateinit var files: File
    private lateinit var cache: File
    private val previous = "Relay URL,Latitude,Longitude\nprevious.example,0,0\n"
    private val update = previous + "updated.example,0,0\n" +
        (1..48).joinToString("\n", postfix = "\n") { "other-$it.example,1,1" }

    @Before
    fun setUp() {
        RelayDirectory::class.java.getDeclaredField("initialized").apply { isAccessible = true }
            .setBoolean(RelayDirectory, false)
        files = temporary.newFolder("files")
        cache = temporary.newFolder("cache")
        whenever(application.filesDir).thenReturn(files)
        whenever(application.cacheDir).thenReturn(cache)
        whenever(application.getSharedPreferences("relay_directory_prefs", Context.MODE_PRIVATE)).thenReturn(prefs)
        prefs.edit().clear().putLong("last_update_ms", 123L)
            .putString("source_url", RelayDirectory.ASSET_FILE_URL).commit()
        val file = File(files, "nostr_relays_latest.csv").apply { writeText(previous) }
        RelayDirectory::class.java.getDeclaredMethod("loadFromFile", File::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(RelayDirectory, file, "synthetic")
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        refreshScope.cancel()
        scheduler.runCurrent()
        RelayDirectory::class.java.getDeclaredField("initialized").apply { isAccessible = true }
            .setBoolean(RelayDirectory, false)
        val field = RelayDirectory::class.java.getDeclaredField("relays").apply { isAccessible = true }
        (field.get(RelayDirectory) as MutableList<*>).clear()
        prefs.edit().clear().commit()
    }

    private fun withResponse(code: Int, body: String, block: () -> Unit) {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("synthetic").body(body.toResponseBody()).build()
        }.build()
        val field = OkHttpProvider::class.java.getDeclaredField("httpClientRef").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val reference = field.get(OkHttpProvider) as AtomicReference<OkHttpProvider.RoutedClient?>
        val saved = reference.getAndSet(OkHttpProvider.RoutedClient(client, OkHttpProvider.Route.DIRECT))
        try {
            block()
        } finally {
            reference.set(saved)
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun fetch(code: Int, body: String) = withResponse(code, body) {
        RelayDirectory::class.java.getDeclaredMethod("fetchAndMaybeSwap", Application::class.java, Function0::class.java)
            .apply { isAccessible = true }.invoke(RelayDirectory, application, { now })
    }

    @Test
    fun `stale initialization uses one refresh worker and one request per interval`() {
        withResponse(503, "unavailable") {
            RelayDirectory.initialize(application, refreshScope) { now }
            RelayDirectory.initialize(application, refreshScope) { now }
            assertEquals(1, refreshScope.coroutineContext.job.children.count())
            scheduler.runCurrent()
            assertEquals(1, requests)
            scheduler.advanceTimeBy(59_999)
            scheduler.runCurrent()
            assertEquals(1, requests)
            scheduler.advanceTimeBy(1)
            scheduler.runCurrent()
            assertEquals(2, requests)
        }
    }

    @Test
    fun `successful initialization records injected time and refreshes at the day boundary`() {
        var clock = now
        val day = 86_400_000L
        withResponse(200, update) {
            RelayDirectory.initialize(application, refreshScope) { clock }
            scheduler.runCurrent()
            assertEquals(1, requests)
            assertEquals(clock, prefs.getLong("last_update_ms", 0L))

            clock = now + day - 1
            scheduler.advanceTimeBy(60_000)
            scheduler.runCurrent()
            assertEquals("no refresh before the injected day boundary", 1, requests)
            assertEquals(now, prefs.getLong("last_update_ms", 0L))

            clock = now + day
            scheduler.advanceTimeBy(60_000)
            scheduler.runCurrent()
            assertEquals("refresh at the injected day boundary", 2, requests)
            assertEquals(clock, prefs.getLong("last_update_ms", 0L))
        }
    }

    private fun assertPrevious() {
        assertEquals(previous, File(files, "nostr_relays_latest.csv").readText())
        assertEquals(listOf("wss://previous.example"), RelayDirectory.closestRelaysForGeohash("s0000", 5))
        assertEquals(123L, prefs.getLong("last_update_ms", 0L))
        assertTrue(cache.list()!!.isEmpty())
    }

    @Test
    fun `asset hash failure reports the exception text`() {
        val assets = mock<AssetManager>()
        whenever(application.assets).thenReturn(assets)
        whenever(assets.open("nostr_relays.csv")).thenAnswer { throw IOException("synthetic asset failure") }
        RelayDirectory::class.java.getDeclaredMethod("loadFromAssets", Application::class.java)
            .apply { isAccessible = true }.invoke(RelayDirectory, application)
        assertTrue(ShadowLog.getLogsForTag("RelayDirectory").any {
            it.msg.contains("sha256=error:synthetic asset failure")
        })
    }

    @Test
    fun `HTTP failure retains state cleans download and reports status code`() {
        fetch(503, "unavailable")
        assertPrevious()
        assertTrue(ShadowLog.getLogsForTag("RelayDirectory").any { it.msg.contains("HTTP 503") })
    }

    @Test
    fun `unusable destination retains state and cleans temporary download`() {
        whenever(application.filesDir).thenReturn(File(files, "missing-parent"))
        fetch(200, update)
        assertPrevious()
        assertTrue(ShadowLog.getLogsForTag("RelayDirectory").any { it.msg.startsWith("Failed to fetch and swap") })
    }

    @Test
    fun `rejected empty download retains state and cleans temporary download`() {
        fetch(200, "")
        assertPrevious()
    }

    @Test
    fun `successful refresh installs cache updates selection and timestamp`() {
        fetch(200, update)
        assertEquals(update, File(files, "nostr_relays_latest.csv").readText())
        assertEquals(listOf("wss://previous.example", "wss://updated.example"), RelayDirectory.closestRelaysForGeohash("s0000", 2))
        assertEquals(now, prefs.getLong("last_update_ms", 0L))
        assertTrue(cache.list()!!.isEmpty())
    }

    private fun directory(rows: List<String>) =
        "Relay URL,Latitude,Longitude\n" + rows.joinToString("\n", postfix = "\n")

    private fun checkRemoteAdmission(retained: Int, total: Int, accepted: Boolean) {
        val baselineRows = (1..51).map { "baseline-$it.example,0,0" }
        val baseline = directory(baselineRows)
        val replacementRows = baselineRows.take(retained) +
            (1..(total - retained)).map { "replacement-$it.example,0,0" }
        val replacement = directory(replacementRows)
        val destination = File(files, "nostr_relays_latest.csv")
        destination.writeText(baseline)
        withResponse(200, replacement) {
            RelayDirectory.initialize(application, refreshScope) { now }
            assertEquals(
                baselineRows.map { "wss://" + it.substringBefore(',') }.sorted(),
                RelayDirectory.closestRelaysForGeohash("s0000", 100)
            )
            scheduler.runCurrent()
            assertEquals(1, requests)
        }
        val expectedRows = if (accepted) replacementRows else baselineRows
        assertEquals("remote admission must gate cache replacement",
            if (accepted) replacement else baseline, destination.readText())
        assertEquals("remote admission must gate the loaded selection",
            expectedRows.map { "wss://" + it.substringBefore(',') }.sorted(),
            RelayDirectory.closestRelaysForGeohash("s0000", 100))
        assertEquals(if (accepted) now else 123L, prefs.getLong("last_update_ms", 0L))
        assertEquals(RelayDirectory.ASSET_FILE_URL, prefs.getString("source_url", null))
        assertTrue("download temporary files must be removed", cache.list()!!.isEmpty())
        assertEquals(listOf("nostr_relays_latest.csv"), files.list()!!.toList())
    }

    @Test
    fun `refresh rejects 49 retained rows below the remote floor`() {
        checkRemoteAdmission(retained = 49, total = 49, accepted = false)
    }

    @Test
    fun `refresh rejects 50 rows retaining only 25 of 51 baseline entries`() {
        checkRemoteAdmission(retained = 25, total = 50, accepted = false)
    }

    @Test
    fun `refresh accepts 50 rows retaining 26 of 51 baseline entries`() {
        checkRemoteAdmission(retained = 26, total = 50, accepted = true)
    }

}
