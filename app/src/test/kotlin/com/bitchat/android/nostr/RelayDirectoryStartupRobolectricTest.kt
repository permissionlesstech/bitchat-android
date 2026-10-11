package com.bitchat.android.nostr

import android.app.Application
import android.content.Context
import android.content.res.AssetManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import com.bitchat.android.net.OkHttpProvider
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
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
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class RelayDirectoryStartupRobolectricTest {
    @get:Rule val temporary = TemporaryFolder()
    @get:Rule val timeout: Timeout = Timeout.seconds(30)
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("relay-startup-test", Context.MODE_PRIVATE)
    private val application = mock<Application>()
    private val assets = mock<AssetManager>()
    private val scheduler = TestCoroutineScheduler()
    private val refreshScope = TestScope(StandardTestDispatcher(scheduler))
    private var nowMillis = 172_800_000L
    private val initialized = RelayDirectory::class.java.getDeclaredField("initialized")
        .apply { isAccessible = true }
    private val bundled = "Relay URL,Latitude,Longitude\nbundled.example,0,0\n"

    @Before
    fun setUp() {
        initialized.setBoolean(RelayDirectory, false)
        prefs.edit().clear().putLong("last_update_ms", 123L).commit()
        whenever(application.filesDir).thenReturn(temporary.root)
        whenever(application.cacheDir).thenReturn(temporary.root)
        whenever(application.getSharedPreferences("relay_directory_prefs", Context.MODE_PRIVATE)).thenReturn(prefs)
        whenever(application.assets).thenReturn(assets)
        whenever(assets.open("nostr_relays.csv")).thenAnswer { bundled.byteInputStream() }
    }

    @After
    fun clearDirectory() {
        refreshScope.cancel()
        scheduler.runCurrent()
        assertEquals(0, refreshScope.coroutineContext.job.children.count())
        initialized.setBoolean(RelayDirectory, false)
        whenever(assets.open("nostr_relays.csv")).thenAnswer { ByteArray(0).inputStream() }
        RelayDirectory.loadInitialDirectory(application, File(temporary.root, "absent"), prefs)
        prefs.edit().clear().commit()
    }

    private fun cache(undeletable: Boolean = false, valid: Boolean = true): File {
        val diskFile = File(temporary.root, "nostr_relays_latest.csv")
        diskFile.writeText(if (valid) "Relay URL,Latitude,Longitude\ncached.example,0,0\n" else "invalid")
        return if (undeletable) object : File(diskFile.path) {
            override fun delete() = false
        } else diskFile
    }

    private fun selected() = RelayDirectory.closestRelaysForGeohash("s0000", 5)

    @Test
    fun `escaped port refreshes preserve the loaded directory cache and timestamp`() {
        val header = "Relay URL,Latitude,Longitude\n"
        val rows = (0 until 50).map { "relay-$it.example,1,2" }
        val baseline = header + rows.joinToString("\n") + "\n"
        val file = File(temporary.root, "nostr_relays_latest.csv")
        file.writeText(baseline)
        prefs.edit().putString("source_url", RelayDirectory.ASSET_FILE_URL)
            .putLong("last_update_ms", 123L).commit()
        RelayDirectory.loadInitialDirectory(application, file, prefs)
        val previousSelection = RelayDirectory.closestRelaysForGeohash("s0000", 50)
        val previousPrefs = prefs.all.toMap()
        for (port in listOf("%34%34%33", "4%34%33")) {
            val update = header + (listOf("wss://updated.example:$port,1,2") + rows.drop(1)).joinToString("\n")
            fetchWithResponse(200, update)
            assertEquals(port, previousSelection, RelayDirectory.closestRelaysForGeohash("s0000", 50))
            assertEquals(port, baseline, file.readText())
            assertEquals(port, previousPrefs, prefs.all)
        }
        val control = header + (listOf("wss://%72elay-0.example:443,1,2") + rows.drop(1)).joinToString("\n")
        fetchWithResponse(200, control)
        assertEquals(control, file.readText())
        assertEquals(nowMillis, prefs.getLong("last_update_ms", 0L))
        assertEquals(previousSelection, RelayDirectory.closestRelaysForGeohash("s0000", 50))
    }

    @Test
    fun `a surviving cache from an old source does not replace bundled entries`() {
        val file = cache(undeletable = true)
        prefs.edit().putString("source_url", "https://old.example/directory.csv").commit()
        RelayDirectory.loadInitialDirectory(application, file, prefs)
        assertTrue(file.exists())
        assertEquals(listOf("wss://bundled.example"), selected())
        assertFalse(prefs.contains("last_update_ms"))
    }

    @Test
    fun `a surviving cache without source metadata does not replace bundled entries`() {
        val file = cache(undeletable = true)
        RelayDirectory.loadInitialDirectory(application, file, prefs)
        assertTrue(file.exists())
        assertEquals(listOf("wss://bundled.example"), selected())
        assertFalse(prefs.contains("last_update_ms"))
    }

    @Test
    fun `deleting an old source cache leaves bundled entries selected`() {
        val file = cache()
        prefs.edit().putString("source_url", "https://old.example/directory.csv").commit()
        RelayDirectory.loadInitialDirectory(application, file, prefs)
        assertFalse(file.exists())
        assertEquals(listOf("wss://bundled.example"), selected())
        assertFalse(prefs.contains("last_update_ms"))
    }

    @Test
    fun `a valid current source cache is selected without clearing its timestamp`() {
        val file = cache(undeletable = true)
        prefs.edit().putString("source_url", RelayDirectory.ASSET_FILE_URL).commit()
        RelayDirectory.loadInitialDirectory(application, file, prefs)
        assertEquals(listOf("wss://cached.example"), selected())
        assertEquals(123L, prefs.getLong("last_update_ms", 0L))
        assertTrue(file.exists())
        val previousContents = file.readText()
        fetchWithResponse(200, "invalid")
        assertEquals(listOf("wss://cached.example"), selected())
        assertEquals(123L, prefs.getLong("last_update_ms", 0L))
        assertEquals(previousContents, file.readText())
    }

    @Test
    fun `an invalid current source cache falls back to bundled entries`() {
        val file = cache(valid = false)
        prefs.edit().putString("source_url", RelayDirectory.ASSET_FILE_URL).commit()
        RelayDirectory.loadInitialDirectory(application, file, prefs)
        assertEquals(listOf("wss://bundled.example"), selected())
    }

    @Test
    fun `a missing cache loads bundled entries`() {
        RelayDirectory.loadInitialDirectory(application, File(temporary.root, "absent"), prefs)
        assertEquals(listOf("wss://bundled.example"), selected())
    }
    @Test
    fun `initialize loads a current source cache before returning`() {
        cache()
        prefs.edit().putString("source_url", RelayDirectory.ASSET_FILE_URL)
            .putLong("last_update_ms", nowMillis).commit()
        var requests = 0
        withResponse {
            requests++
            "unavailable".toResponseBody()
        }.useClient {
            RelayDirectory.initialize(application, refreshScope) { nowMillis }
            assertEquals(listOf("wss://cached.example"), selected())
            scheduler.runCurrent()
            assertEquals(0, requests)
            nowMillis += 86_399_999L
            scheduler.advanceTimeBy(60_000)
            scheduler.runCurrent()
            assertEquals(0, requests)
            nowMillis++
            scheduler.advanceTimeBy(60_000)
            scheduler.runCurrent()
            assertEquals(1, requests)
        }
    }

    private fun fetchWithResponse(code: Int, body: String) = fetchWithBody(code, body.toResponseBody())

    private fun withResponse(code: Int = 503, body: () -> ResponseBody): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("synthetic response").body(body()).build()
        }.build()

    private fun OkHttpClient.useClient(block: () -> Unit) {
        val field = OkHttpProvider::class.java.getDeclaredField("httpClientRef").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val reference = field.get(OkHttpProvider) as AtomicReference<OkHttpProvider.RoutedClient?>
        val previous = reference.getAndSet(OkHttpProvider.RoutedClient(this, OkHttpProvider.Route.DIRECT))
        try {
            block()
        } finally {
            reference.set(previous)
            dispatcher.executorService.shutdown()
            connectionPool.evictAll()
        }
    }

    private fun fetchWithBody(code: Int, body: ResponseBody) {
        withResponse(code) { body }.useClient {
            RelayDirectory::class.java.getDeclaredMethod(
                "fetchAndMaybeSwap", Application::class.java, Function0::class.java
            ).apply { isAccessible = true }.invoke(RelayDirectory, application, { nowMillis })
        }
    }

    @Test
    fun `failed and rejected refreshes preserve the bundled fallback after source invalidation`() {
        val file = cache(undeletable = true)
        RelayDirectory.loadInitialDirectory(application, file, prefs)
        fetchWithResponse(503, "unavailable")
        assertEquals(listOf("wss://bundled.example"), selected())
        assertFalse(prefs.contains("last_update_ms"))
        fetchWithResponse(200, "invalid")
        assertEquals(listOf("wss://bundled.example"), selected())
        assertFalse(prefs.contains("last_update_ms"))
        assertFalse(prefs.contains("source_url"))
        assertTrue(file.exists())
    }

    @Test
    fun `a validated refresh replaces the fallback and records the current source`() {
        RelayDirectory.loadInitialDirectory(application, cache(undeletable = true), prefs)
        val update = bundled + (1 until 50).joinToString("\n") { "updated-$it.example,1,1" } + "\n"
        fetchWithResponse(200, update)
        assertEquals(50, RelayDirectory.closestRelaysForGeohash("s0000", 50).size)
        assertTrue(RelayDirectory.closestRelaysForGeohash("s0000", 50).contains("wss://updated-1.example"))
        assertEquals(RelayDirectory.ASSET_FILE_URL, prefs.getString("source_url", null))
        assertEquals(nowMillis, prefs.getLong("last_update_ms", 0L))
        assertEquals(update, File(temporary.root, "nostr_relays_latest.csv").readText())
    }


    private fun oversizedRefresh(declaredLength: Boolean) {
        val file = cache()
        prefs.edit().putString("source_url", RelayDirectory.ASSET_FILE_URL).commit()
        RelayDirectory.loadInitialDirectory(application, file, prefs)
        val previousContents = file.readText()
        val previousPrefs = prefs.all.toMap()
        val previousSelection = selected()
        val bytes = ByteArray(RelayDirectory.MAX_DIRECTORY_BYTES + 16_384) { ' '.code.toByte() }
        val valid = "Relay URL,Latitude,Longitude\n" +
            "cached.example,0,0\n" +
            (1 until 50).joinToString("\n") { "updated-$it.example,1,1" } + "\n"
        valid.toByteArray().copyInto(bytes)
        var bytesRead = 0L
        val input = object : ForwardingSource(Buffer().write(bytes)) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val count = super.read(sink, byteCount)
                if (count > 0) bytesRead += count
                return count
            }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): okhttp3.MediaType? = null
            override fun contentLength(): Long = if (declaredLength) bytes.size.toLong() else -1L
            override fun source(): BufferedSource = input
        }
        fetchWithBody(200, body)
        if (declaredLength) {
            assertEquals(0L, bytesRead)
        } else {
            assertTrue(bytesRead > RelayDirectory.MAX_DIRECTORY_BYTES)
            assertTrue(bytesRead <= RelayDirectory.MAX_DIRECTORY_BYTES + 8_192L)
        }
        assertEquals(previousContents, file.readText())
        assertEquals(previousPrefs, prefs.all)
        assertEquals(previousSelection, selected())
    }

    @Test
    fun `declared oversized downloads are rejected before reading the body`() {
        oversizedRefresh(declaredLength = true)
    }

    @Test
    fun `unknown length downloads stop after crossing the streaming byte cap`() {
        oversizedRefresh(declaredLength = false)
    }


    @Test
    fun `stale initialization starts one refresh worker`() {
        prefs.edit().putString("source_url", RelayDirectory.ASSET_FILE_URL).putLong("last_update_ms", 0L).commit()
        var requests = 0
        withResponse {
            requests++
            "unavailable".toResponseBody()
        }.useClient {
            RelayDirectory.initialize(application, refreshScope) { nowMillis }
            assertEquals(1, refreshScope.coroutineContext.job.children.count())
            assertEquals(0, requests)
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
}
