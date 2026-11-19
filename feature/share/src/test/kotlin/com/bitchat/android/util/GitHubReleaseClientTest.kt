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

    /** How far the clock advances while awaitRoute() waits for Tor to finish bootstrapping. */
    private var routeWaitMillis = 0L

    /** How far the clock advances after the server responds but before the client observes it. */
    private var responseWaitMillis = 0L

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

    /**
     * A Tor cold start can hold the request for the full 60-second route timeout, which is longer
     * than the relative delay GitHub asks for. The cooldown has to outlast the wait that preceded
     * it, so it is anchored at the response rather than at the start of the attempt.
     */
    @Test
    fun `a slow route wait does not shorten a relative retry-after cooldown`() = runTest {
        routeWaitMillis = 90_000L
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .addHeader("Retry-After", "60")
                .build()
        )
        val client = client()

        assertTrue(client.latestRelease().isFailure)
        routeWaitMillis = 0L
        assertTrue(client.latestRelease().isFailure)

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a slow route wait does not shorten the header-less fallback cooldown`() = runTest {
        routeWaitMillis = 90_000L
        server.enqueue(MockResponse.Builder().code(429).build())
        val client = client()

        assertTrue(client.latestRelease().isFailure)
        routeWaitMillis = 0L
        assertTrue(client.latestRelease().isFailure)

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a slow response does not shorten a relative retry-after cooldown`() = runTest {
        responseWaitMillis = 90_000L
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .addHeader("Retry-After", "60")
                .build()
        )
        val client = client()

        assertTrue(client.latestRelease().isFailure)
        responseWaitMillis = 0L
        assertTrue(client.latestRelease().isFailure)

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a cooldown that expires while the route becomes ready does not suppress the request`() =
        runTest {
            server.enqueue(
                MockResponse.Builder()
                    .code(429)
                    .addHeader("Retry-After", "60")
                    .build()
            )
            val client = client()

            assertTrue(client.latestRelease().isFailure)

            routeWaitMillis = 90_000L
            server.enqueue(successResponse(etag = "release-after-cooldown"))
            assertTrue(client.latestRelease().isSuccess)

            assertEquals(2, server.requestCount)
        }

    private fun client() = GitHubReleaseClient(
        context = context,
        apiUrl = server.url("/releases/latest").toString(),
        nowMillis = { nowMillis },
        routedClient = {
            OkHttpProvider.RoutedClient(
                client = OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        chain.proceed(chain.request()).also {
                            nowMillis += responseWaitMillis
                        }
                    }
                    .build(),
                route = route
            )
        },
        awaitRoute = {
            nowMillis += routeWaitMillis
            true
        }
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
