package com.bitchat.android.mesh

import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.max

/**
 * Power-aware Bluetooth management for bitchat
 * Adjusts scanning, advertising, and connection behavior based on battery state
 */
class PowerManager(private val context: Context) {
    
    companion object {
        private const val TAG = "PowerManager"
        
        // Battery thresholds
        private const val CRITICAL_BATTERY = com.bitchat.android.util.AppConstants.Power.CRITICAL_BATTERY_PERCENT
        private const val LOW_BATTERY = com.bitchat.android.util.AppConstants.Power.LOW_BATTERY_PERCENT
        private const val MEDIUM_BATTERY = com.bitchat.android.util.AppConstants.Power.MEDIUM_BATTERY_PERCENT
        
        // Scan duty cycle periods (ms)
        private const val SCAN_ON_DURATION_NORMAL = com.bitchat.android.util.AppConstants.Power.SCAN_ON_DURATION_NORMAL_MS    // 8 seconds on
        private const val SCAN_OFF_DURATION_NORMAL = com.bitchat.android.util.AppConstants.Power.SCAN_OFF_DURATION_NORMAL_MS   // 2 seconds off
        private const val SCAN_ON_DURATION_POWER_SAVE = com.bitchat.android.util.AppConstants.Power.SCAN_ON_DURATION_POWER_SAVE_MS    // 2 seconds on
        private const val SCAN_OFF_DURATION_POWER_SAVE = com.bitchat.android.util.AppConstants.Power.SCAN_OFF_DURATION_POWER_SAVE_MS  // 8 seconds off
        private const val SCAN_ON_DURATION_ULTRA_LOW = com.bitchat.android.util.AppConstants.Power.SCAN_ON_DURATION_ULTRA_LOW_MS      // 1 second on
        private const val SCAN_OFF_DURATION_ULTRA_LOW = com.bitchat.android.util.AppConstants.Power.SCAN_OFF_DURATION_ULTRA_LOW_MS   // 10 seconds off
        
        // Connection limits
        private const val MAX_CONNECTIONS_NORMAL = com.bitchat.android.util.AppConstants.Power.MAX_CONNECTIONS_NORMAL
        private const val MAX_CONNECTIONS_POWER_SAVE = com.bitchat.android.util.AppConstants.Power.MAX_CONNECTIONS_POWER_SAVE
        private const val MAX_CONNECTIONS_ULTRA_LOW = com.bitchat.android.util.AppConstants.Power.MAX_CONNECTIONS_ULTRA_LOW
    }
    
    enum class PowerMode {
        PERFORMANCE,    // Full power, no restrictions
        BALANCED,       // Moderate power saving
        POWER_SAVER,    // Aggressive power saving
        ULTRA_LOW_POWER // Minimal operations only
    }
    
    private var currentMode = PowerMode.BALANCED
    private var isCharging = false
    private var batteryLevel = 100
    private var isAppInBackground = false
    
    private val powerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var dutyCycleJob: Job? = null
    
    var delegate: PowerManagerDelegate? = null
    
    // Battery monitoring
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level != -1 && scale != -1) {
                        batteryLevel = (level * 100) / scale
                    }
                    
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                status == BatteryManager.BATTERY_STATUS_FULL
                    
                    updatePowerMode()
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    isCharging = true
                    updatePowerMode()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    isCharging = false
                    updatePowerMode()
                }
            }
        }
    }
    
    init {
        registerBatteryReceiver()
        updatePowerMode()
    }
    
    fun start() {
        Log.i(TAG, "Starting power management")
        startDutyCycle()
    }
    
    fun stop() {
        Log.i(TAG, "Stopping power management")
        powerScope.cancel()
        unregisterBatteryReceiver()
    }
    
    fun setAppBackgroundState(inBackground: Boolean) {
        if (isAppInBackground != inBackground) {
            isAppInBackground = inBackground
            Log.d(TAG, "App background state changed: $inBackground")
            updatePowerMode()
        }
    }
    
    /**
     * Get scan settings optimized for current power mode
     */
    fun getScanSettings(): ScanSettings {
        val builder = ScanSettings.Builder()
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)

        when (currentMode) {
            PowerMode.PERFORMANCE -> builder
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)

            PowerMode.BALANCED -> builder
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)

            PowerMode.POWER_SAVER -> builder
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
                .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)

            PowerMode.ULTRA_LOW_POWER -> builder
                .setScanMode(ScanSettings.SCAN_MODE_OPPORTUNISTIC)
                .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
                .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
        }

        return builder.setReportDelay(0).build()
    }
    
    /**
     * Get advertising settings optimized for current power mode
     */
    fun getAdvertiseSettings(): AdvertiseSettings {
        return when (currentMode) {
            PowerMode.PERFORMANCE -> AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build()
                
            PowerMode.BALANCED -> AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .setTimeout(0)
                .build()
                
            PowerMode.POWER_SAVER -> AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
                .setConnectable(true)
                .setTimeout(0)
                .build()
                
            PowerMode.ULTRA_LOW_POWER -> AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW)
                .setConnectable(true)
                .setTimeout(0)
                .build()
        }
    }
    
    /**
     * Get maximum allowed connections for current power mode
     */
    fun getMaxConnections(): Int {
        return when (currentMode) {
            PowerMode.PERFORMANCE -> MAX_CONNECTIONS_NORMAL
            PowerMode.BALANCED -> MAX_CONNECTIONS_NORMAL
            PowerMode.POWER_SAVER -> MAX_CONNECTIONS_POWER_SAVE
            PowerMode.ULTRA_LOW_POWER -> MAX_CONNECTIONS_ULTRA_LOW
        }
    }
    
    /**
     * Get RSSI filter threshold for current power mode
     */
    fun getRSSIThreshold(): Int {
        return when (currentMode) {
            PowerMode.PERFORMANCE -> -95
            PowerMode.BALANCED -> -85
            PowerMode.POWER_SAVER -> -75
            PowerMode.ULTRA_LOW_POWER -> -65
        }
    }
    
    /**
     * Check if duty cycling should be used
     */
    fun shouldUseDutyCycle(): Boolean {
        return currentMode != PowerMode.PERFORMANCE
    }
    
    /**
     * Get current power mode information
     */
    fun getPowerInfo(): String {
        return buildString {
            appendLine("=== Power Manager Status ===")
            appendLine("Current Mode: $currentMode")
            appendLine("Battery Level: $batteryLevel%")
            appendLine("Is Charging: $isCharging")
            appendLine("App In Background: $isAppInBackground")
            appendLine("Max Connections: ${getMaxConnections()}")
            appendLine("RSSI Threshold: ${getRSSIThreshold()} dBm")
            appendLine("Use Duty Cycle: ${shouldUseDutyCycle()}")
        }
    }
    
    private fun updatePowerMode() {
        val newMode = when {
            // Always use performance mode when charging (unless in background too long)
            isCharging && !isAppInBackground -> PowerMode.PERFORMANCE
            
            // Critical battery - use ultra low power
            batteryLevel <= CRITICAL_BATTERY -> PowerMode.ULTRA_LOW_POWER
            
            // Low battery - use power saver
            batteryLevel <= LOW_BATTERY -> PowerMode.POWER_SAVER
            
            // Background app with medium battery - use power saver
            isAppInBackground && batteryLevel <= MEDIUM_BATTERY -> PowerMode.POWER_SAVER
            
            // Background app with good battery - use balanced
            isAppInBackground -> PowerMode.BALANCED
            
            // Foreground with good battery - use balanced
            else -> PowerMode.BALANCED
        }
        
        if (newMode != currentMode) {
            val oldMode = currentMode
            currentMode = newMode
            Log.i(TAG, "Power mode changed: $oldMode → $newMode (battery: $batteryLevel%, charging: $isCharging, background: $isAppInBackground)")
            
            delegate?.onPowerModeChanged(currentMode)
            
            // Restart duty cycle with new parameters
            if (shouldUseDutyCycle()) {
                startDutyCycle()
            } else {
                stopDutyCycle()
            }
        }
    }
    
    private fun startDutyCycle() {
        stopDutyCycle()
        
        if (!shouldUseDutyCycle()) {
            delegate?.onScanStateChanged(true) // Always scan in performance mode
            return
        }
        
        val (onDuration, offDuration) = when (currentMode) {
            PowerMode.BALANCED -> SCAN_ON_DURATION_NORMAL to SCAN_OFF_DURATION_NORMAL
            PowerMode.POWER_SAVER -> SCAN_ON_DURATION_POWER_SAVE to SCAN_OFF_DURATION_POWER_SAVE
            PowerMode.ULTRA_LOW_POWER -> SCAN_ON_DURATION_ULTRA_LOW to SCAN_OFF_DURATION_ULTRA_LOW
            PowerMode.PERFORMANCE -> return // No duty cycle
        }
        
        dutyCycleJob = powerScope.launch {
            while (isActive && shouldUseDutyCycle()) {
                // Scan ON period
                Log.d(TAG, "Duty cycle: Scan ON for ${onDuration}ms")
                delegate?.onScanStateChanged(true)
                delay(onDuration)
                
                // Scan OFF period (keep advertising active)
                if (isActive && shouldUseDutyCycle()) {
                    Log.d(TAG, "Duty cycle: Scan OFF for ${offDuration}ms")
                    delegate?.onScanStateChanged(false)
                    delay(offDuration)
                }
            }
        }
        
        Log.i(TAG, "Started duty cycle: ${onDuration}ms ON, ${offDuration}ms OFF")
    }
    
    private fun stopDutyCycle() {
        dutyCycleJob?.cancel()
        dutyCycleJob = null
    }
    
    private fun registerBatteryReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            context.registerReceiver(batteryReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register battery receiver: ${e.message}")
        }
    }
    
    private fun unregisterBatteryReceiver() {
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister battery receiver: ${e.message}")
        }
    }
}

/**
 * Delegate interface for power management callbacks
 */
interface PowerManagerDelegate {
    fun onPowerModeChanged(newMode: PowerManager.PowerMode)
    fun onScanStateChanged(shouldScan: Boolean)
}
