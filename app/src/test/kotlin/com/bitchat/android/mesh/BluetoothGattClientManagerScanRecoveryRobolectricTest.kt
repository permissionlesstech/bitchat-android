package com.bitchat.android.mesh

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BluetoothGattClientManagerScanRecoveryRobolectricTest {
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler))
    private val scanner = mock<BluetoothLeScanner>()
    private lateinit var manager: BluetoothGattClientManager

    @Before
    fun setUp() {
        val adapter = mock<BluetoothAdapter> {
            on { isEnabled } doReturn true
            on { bluetoothLeScanner } doReturn scanner
        }
        val bluetoothManager = mock<BluetoothManager> {
            on { this.adapter } doReturn adapter
        }
        val context = mock<Context> {
            on { getSystemService(Context.BLUETOOTH_SERVICE) } doReturn bluetoothManager
        }
        val permissionManager = mock<BluetoothPermissionManager> {
            on { hasBluetoothPermissions() } doReturn true
        }
        val resolved = PowerProfileResolver.resolve(
            batteryLevel = 100,
            isCharging = true,
            isBackground = false,
            hasDirectPeers = false
        )
        val continuousProfile = resolved.copy(ble = resolved.ble.copy(continuousScan = true))
        val powerManager = mock<PowerManager> {
            on { profile } doReturn MutableStateFlow(continuousProfile)
            on { getScanSettings() } doReturn ScanSettings.Builder().build()
        }
        manager = BluetoothGattClientManager(
            context,
            scope,
            mock(),
            permissionManager,
            powerManager,
            null
        )

        manager.start()
        scheduler.runCurrent()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `already started failure stops the registered scan before restarting it`() {
        val callback = startedCallback()

        callback.onScanFailed(ScanCallback.SCAN_FAILED_ALREADY_STARTED)
        allowNextScanStart()
        scheduler.advanceTimeBy(ALREADY_STARTED_RETRY_MS + 1)

        inOrder(scanner) {
            verify(scanner).startScan(any<List<ScanFilter>>(), any(), any<ScanCallback>())
            verify(scanner).stopScan(callback)
            verify(scanner).startScan(any<List<ScanFilter>>(), any(), any<ScanCallback>())
        }
    }

    @Test
    fun `restarted scan keeps the callback the stack has registered`() {
        val first = startedCallback()

        manager.onScanStateChanged(false)
        allowNextScanStart()
        manager.onScanStateChanged(true)

        val started = argumentCaptor<ScanCallback>()
        verify(scanner, times(2)).startScan(any<List<ScanFilter>>(), any(), started.capture())
        assertSame(first, started.secondValue)
    }

    @Test
    fun `failure reported after registration releases the scan`() {
        val callback = startedCallback()

        callback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)

        verify(scanner).stopScan(callback)
    }

    private fun startedCallback(): ScanCallback {
        val started = argumentCaptor<ScanCallback>()
        verify(scanner).startScan(any<List<ScanFilter>>(), any(), started.capture())
        return started.firstValue
    }

    /** The rate limit reads the wall clock, so clear the last start instead of sleeping past it. */
    private fun allowNextScanStart() {
        BluetoothGattClientManager::class.java
            .getDeclaredField("lastScanStartTime")
            .apply { isAccessible = true }
            .setLong(manager, 0L)
    }

    private companion object {
        const val ALREADY_STARTED_RETRY_MS = 3_000L
    }
}
