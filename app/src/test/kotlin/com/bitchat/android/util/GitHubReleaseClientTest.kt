package com.bitchat.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.net.OkHttpProvider
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GitHubReleaseClientTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private var nowMillis = 1_700_000_000_000L
    private var route = OkHttpProvider.Route.DIRECT

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("apk_release_metadata", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("apk_network_cooldowns", Context.MODE_PRIVATE)
            .edit().clear().commit()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `cached metadata is conditionally refreshed with its etag`() = runTest {
        server.enqueue(successResponse(etag = "release-v1"))
        val client = client()

        val first = client.latestRelease().getOrThrow()
        assertEquals("1.7.6", first.release.versionName)
        assertFalse(first.isStale)

        nowMillis += 31 * 60_000L
        server.enqueue(
            MockResponse.Builder()
                .code(304)
                .build()
        )
        val refreshed = client.latestRelease().getOrThrow()

        assertFalse(refreshed.isStale)
        server.takeRequest()
        assertEquals("release-v1", server.takeRequest().headers["If-None-Match"])
    }

    @Test
    fun `rate limit serves stale metadata and suppresses repeated requests`() = runTest {
        server.enqueue(successResponse(etag = "release-v1"))
        val client = client()
        client.latestRelease().getOrThrow()

        nowMillis += 31 * 60_000L
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .build()
        )
        val stale = client.latestRelease().getOrThrow()
        val stillStale = client.latestRelease().getOrThrow()

        assertTrue(stale.isStale)
        assertTrue(stillStale.isStale)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `cooldown follows the actual client route`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .addHeader("Retry-After", "120")
                .build()
        )
        val client = client()
        assertTrue(client.latestRelease().isFailure)
        assertTrue(client.latestRelease().isFailure)
        assertEquals(1, server.requestCount)

        route = OkHttpProvider.Route.TOR
        server.enqueue(successResponse(etag = "release-v1"))
        assertTrue(client.latestRelease().isSuccess)
        assertEquals(2, server.requestCount)
    }

    private fun client() = GitHubReleaseClient(
        context = context,
        apiUrl = server.url("/releases/latest").toString(),
        nowMillis = { nowMillis },
        routedClient = {
            OkHttpProvider.RoutedClient(
                client = OkHttpClient.Builder().build(),
                route = route
            )
        },
        awaitRoute = { true }
    )

    private fun successResponse(etag: String): MockResponse = MockResponse.Builder()
        .code(200)
        .addHeader("ETag", etag)
        .body(
            """
            {
              "tag_name": "v1.7.6",
              "assets": [
                {
                  "name": "bitchat-android-universal.apk",
                  "browser_download_url": "https://downloads.example/bitchat-universal.apk",
                  "size": 25165824
                }
              ]
            }
            """.trimIndent()
        )
        .build()
}
