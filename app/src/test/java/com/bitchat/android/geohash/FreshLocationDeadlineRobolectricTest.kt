package com.bitchat.android.geohash

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.tasks.CancellationToken
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.TaskCompletionSource
import java.time.Duration
import java.util.concurrent.Executor
import java.util.function.Consumer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [30])
@LooperMode(LooperMode.Mode.PAUSED)
class FreshLocationDeadlineRobolectricTest {
    private lateinit var context: Context
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var systemFallback: SystemLocationProvider
    private lateinit var fusedResult: TaskCompletionSource<Location>
    private lateinit var provider: FusedLocationProvider
    private lateinit var taskCancellation: CancellationTokenSource

    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        context = application
        LiveLocationPrivacyGate.update(true)
        fusedClient = mock()
        systemFallback = mock()
        taskCancellation = CancellationTokenSource()
        fusedResult = TaskCompletionSource(taskCancellation.token)
        whenever(fusedClient.getCurrentLocation(any<CurrentLocationRequest>(), any<CancellationToken>()))
            .thenReturn(fusedResult.task)
        provider = FusedLocationProvider(context, fusedClient, systemFallback)
    }

    @After
    fun tearDown() {
        provider.cancel()
        LiveLocationPrivacyGate.update(false)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `fused null after twenty seconds gives fallback the original deadline`() {
        val startedAt = SystemClock.elapsedRealtime()
        val results = mutableListOf<Location?>()
        provider.requestFreshLocation(results::add)
        advanceSeconds(20)
        fusedResult.setResult(null)
        shadowOf(Looper.getMainLooper()).idle()

        val deadline = argumentCaptor<Long>()
        verify(systemFallback).requestFreshLocation(deadline.capture(), any())
        assertEquals(startedAt + 30_000L, deadline.firstValue)
        assertEquals(10_000L, deadline.firstValue - SystemClock.elapsedRealtime())
        assertTrue(results.isEmpty())

        advanceSeconds(9)
        assertTrue(results.isEmpty())
        advanceSeconds(1)
        assertEquals(listOf<Location?>(null), results)
    }

    @Test
    fun `fused cancellation before deadline uses only remaining time`() {
        val startedAt = SystemClock.elapsedRealtime()
        provider.requestFreshLocation {}
        advanceSeconds(25)
        taskCancellation.cancel()
        shadowOf(Looper.getMainLooper()).idle()

        val deadline = argumentCaptor<Long>()
        verify(systemFallback).requestFreshLocation(deadline.capture(), any())
        assertEquals(startedAt + 30_000L, deadline.firstValue)
        assertEquals(5_000L, deadline.firstValue - SystemClock.elapsedRealtime())
    }

    @Test
    fun `fused failure preserves the original deadline`() {
        val startedAt = SystemClock.elapsedRealtime()
        provider.requestFreshLocation {}
        advanceSeconds(29)
        fusedResult.setException(IllegalStateException("Synthetic failure"))
        shadowOf(Looper.getMainLooper()).idle()

        val deadline = argumentCaptor<Long>()
        verify(systemFallback).requestFreshLocation(deadline.capture(), any())
        assertEquals(startedAt + 30_000L, deadline.firstValue)
        assertEquals(1_000L, deadline.firstValue - SystemClock.elapsedRealtime())
    }

    @Test
    fun `late fallback result cannot complete an expired request again`() {
        val results = mutableListOf<Location?>()
        provider.requestFreshLocation(results::add)
        advanceSeconds(20)
        fusedResult.setResult(null)
        shadowOf(Looper.getMainLooper()).idle()
        val fallbackCallback = argumentCaptor<(Location?) -> Unit>()
        verify(systemFallback).requestFreshLocation(any<Long>(), fallbackCallback.capture())

        advanceSeconds(10)
        assertEquals(listOf<Location?>(null), results)
        fallbackCallback.firstValue(Location("synthetic"))
        assertEquals(listOf<Location?>(null), results)
    }

    @Test
    fun `hung fused request finishes once at deadline and ignores late success`() {
        val results = mutableListOf<Location?>()
        provider.requestFreshLocation(results::add)
        val token = argumentCaptor<CancellationToken>()
        verify(fusedClient).getCurrentLocation(any<CurrentLocationRequest>(), token.capture())

        advanceSeconds(30)
        assertEquals(listOf<Location?>(null), results)
        assertTrue(token.firstValue.isCancellationRequested)
        fusedResult.setResult(Location("synthetic"))
        shadowOf(Looper.getMainLooper()).idle()
        advanceSeconds(30)
        assertEquals(listOf<Location?>(null), results)
        verifyNoInteractions(systemFallback)
    }

    @Test
    fun `explicit cancellation suppresses timeout and late fallback`() {
        val results = mutableListOf<Location?>()
        provider.requestFreshLocation(results::add)
        provider.cancel()
        fusedResult.setResult(null)
        shadowOf(Looper.getMainLooper()).idle()
        advanceSeconds(60)

        assertTrue(results.isEmpty())
        verify(systemFallback).cancel()
        verify(systemFallback, org.mockito.kotlin.never()).requestFreshLocation(any<Long>(), any())
    }

    @Test
    fun `early success removes watchdog without a second completion`() {
        val results = mutableListOf<Location?>()
        val syntheticLocation = Location("synthetic")
        provider.requestFreshLocation(results::add)
        fusedResult.setResult(syntheticLocation)
        shadowOf(Looper.getMainLooper()).idle()
        advanceSeconds(60)

        assertEquals(listOf(syntheticLocation), results)
        verifyNoInteractions(systemFallback)
    }

    @Test
    fun `system gps and network consume only the remaining shared budget`() {
        val manager = mock<LocationManager>()
        whenever(manager.isProviderEnabled(any())).thenReturn(true)
        val cancellations = mutableListOf<CancellationSignal>()
        val providers = mutableListOf<String>()
        val consumers = mutableListOf<Consumer<Location>>()
        doAnswer { invocation ->
            providers += invocation.getArgument<String>(0)
            cancellations += invocation.getArgument<CancellationSignal>(1)
            consumers += invocation.getArgument<Consumer<Location>>(3)
            null
        }.whenever(manager).getCurrentLocation(
            any<String>(), any<CancellationSignal>(), any<Executor>(), any<Consumer<Location>>()
        )
        val system = SystemLocationProvider(object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.LOCATION_SERVICE) manager else super.getSystemService(name)
        })
        val results = mutableListOf<Location?>()
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        advanceSeconds(20)
        system.requestFreshLocation(deadline, results::add)

        assertEquals(listOf(LocationManager.GPS_PROVIDER), providers)
        advanceSeconds(5)
        assertEquals(listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER), providers)
        assertTrue(cancellations[0].isCanceled)
        assertFalse(cancellations[1].isCanceled)
        assertTrue(results.isEmpty())
        advanceSeconds(5)
        assertEquals(listOf<Location?>(null), results)
        assertTrue(cancellations.all { it.isCanceled })

        consumers.forEach { it.accept(Location("synthetic")) }
        assertEquals(listOf<Location?>(null), results)
        system.cancel()
    }

    @Test
    fun `expired system deadline registers no provider`() {
        val manager = mock<LocationManager>()
        val system = SystemLocationProvider(object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.LOCATION_SERVICE) manager else super.getSystemService(name)
        })
        val results = mutableListOf<Location?>()
        system.requestFreshLocation(SystemClock.elapsedRealtime(), results::add)

        assertEquals(listOf<Location?>(null), results)
        verifyNoInteractions(manager)
        system.cancel()
    }

    private fun advanceSeconds(seconds: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
    }
}
