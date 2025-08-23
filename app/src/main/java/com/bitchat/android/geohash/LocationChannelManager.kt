package com.bitchat.android.geohash

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*
import java.util.*
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Manages location permissions, one-shot location retrieval, and computing geohash channels.
 * Direct port from iOS LocationChannelManager for 100% compatibility
 */
class LocationChannelManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "LocationChannelManager"
        
        @Volatile
        private var INSTANCE: LocationChannelManager? = null
        
        fun getInstance(context: Context): LocationChannelManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LocationChannelManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // State enum matching iOS
    enum class PermissionState {
        NOT_DETERMINED,
        DENIED,
        RESTRICTED,
        AUTHORIZED
    }

    private val locationManager: LocationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val geocoder: Geocoder = Geocoder(context, Locale.getDefault())
    private var lastLocation: Location? = null
    private var refreshTimer: Job? = null
    private var isGeocoding: Boolean = false
    private val gson = Gson()
    private var dataManager: com.bitchat.android.ui.DataManager? = null

    // Published state for UI bindings (matching iOS @Published properties)
    private val _permissionState = MutableLiveData(PermissionState.NOT_DETERMINED)
    val permissionState: LiveData<PermissionState> = _permissionState

    private val _availableChannels = MutableLiveData<List<GeohashChannel>>(emptyList())
    val availableChannels: LiveData<List<GeohashChannel>> = _availableChannels

    private val _selectedChannel = MutableLiveData<ChannelID>(ChannelID.Mesh)
    val selectedChannel: LiveData<ChannelID> = _selectedChannel

    private val _teleported = MutableLiveData(false)
    val teleported: LiveData<Boolean> = _teleported

    private val _locationNames = MutableLiveData<Map<GeohashChannelLevel, String>>(emptyMap())
    val locationNames: LiveData<Map<GeohashChannelLevel, String>> = _locationNames

    init {
        updatePermissionState()
        // Initialize DataManager and load persisted channel selection
        dataManager = com.bitchat.android.ui.DataManager(context)
        loadPersistedChannelSelection()
    }

    // MARK: - Public API (matching iOS interface)

    /**
     * Enable location channels (request permission if needed)
     */
    fun enableLocationChannels() {
        Log.d(TAG, "enableLocationChannels() called")
        
        when (getCurrentPermissionStatus()) {
            PermissionState.NOT_DETERMINED -> {
                Log.d(TAG, "Permission not determined - user needs to grant in app settings")
                _permissionState.postValue(PermissionState.NOT_DETERMINED)
            }
            PermissionState.DENIED, PermissionState.RESTRICTED -> {
                Log.d(TAG, "Permission denied or restricted")
                _permissionState.postValue(PermissionState.DENIED)
            }
            PermissionState.AUTHORIZED -> {
                Log.d(TAG, "Permission authorized - requesting location")
                _permissionState.postValue(PermissionState.AUTHORIZED)
                requestOneShotLocation()
            }
        }
    }

    /**
     * Refresh available channels from current location
     */
    fun refreshChannels() {
        if (_permissionState.value == PermissionState.AUTHORIZED) {
            requestOneShotLocation()
        }
    }

    /**
     * Begin periodic one-shot location refreshes while a selector UI is visible
     */
    fun beginLiveRefresh(interval: Long = 5000L) {
        Log.d(TAG, "Beginning live refresh with interval ${interval}ms")
        
        if (_permissionState.value != PermissionState.AUTHORIZED) {
            Log.w(TAG, "Cannot start live refresh - permission not authorized")
            return
        }

        // Cancel existing timer
        refreshTimer?.cancel()
        
        // Start new timer with coroutines
        refreshTimer = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                requestOneShotLocation()
                delay(interval)
            }
        }
        
        // Kick off immediately
        requestOneShotLocation()
    }

    /**
     * Stop periodic refreshes when selector UI is dismissed
     */
    fun endLiveRefresh() {
        Log.d(TAG, "Ending live refresh")
        refreshTimer?.cancel()
        refreshTimer = null
    }

    /**
     * Select a channel
     */
    fun select(channel: ChannelID) {
        Log.d(TAG, "Selected channel: ${channel.displayName}")
        _selectedChannel.postValue(channel)
        saveChannelSelection(channel)
    }
    
    /**
     * Set teleported status (for manual geohash teleportation)
     */
    fun setTeleported(teleported: Boolean) {
        Log.d(TAG, "Setting teleported status: $teleported")
        _teleported.postValue(teleported)
    }

    // MARK: - Location Operations

    private fun requestOneShotLocation() {
        if (!hasLocationPermission()) {
            Log.w(TAG, "No location permission for one-shot request")
            return
        }

        Log.d(TAG, "Requesting one-shot location")
        
        try {
            // Get last known location first for quick result
            val lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            
            if (lastKnownLocation != null) {
                Log.d(TAG, "Using last known location: ${lastKnownLocation.latitude}, ${lastKnownLocation.longitude}")
                lastLocation = lastKnownLocation
                computeChannels(lastKnownLocation)
                reverseGeocodeIfNeeded(lastKnownLocation)
            } else {
                Log.d(TAG, "No last known location available")
                // For demo purposes, use a default location (San Francisco)
                val demoLocation = Location("demo").apply {
                    latitude = 37.7749
                    longitude = -122.4194
                }
                Log.d(TAG, "Using demo location (San Francisco): ${demoLocation.latitude}, ${demoLocation.longitude}")
                lastLocation = demoLocation
                computeChannels(demoLocation)
                reverseGeocodeIfNeeded(demoLocation)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception requesting location: ${e.message}")
            updatePermissionState()
        }
    }

    // MARK: - Helpers

    private fun getCurrentPermissionStatus(): PermissionState {
        return when {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED -> {
                PermissionState.AUTHORIZED
            }
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED -> {
                PermissionState.AUTHORIZED
            }
            else -> {
                PermissionState.DENIED // In Android, we can't distinguish between denied and not determined after first ask
            }
        }
    }

    private fun updatePermissionState() {
        val newState = getCurrentPermissionStatus()
        Log.d(TAG, "Permission state updated to: $newState")
        _permissionState.postValue(newState)
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
               ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun computeChannels(location: Location) {
        Log.d(TAG, "Computing channels for location: ${location.latitude}, ${location.longitude}")
        
        val levels = GeohashChannelLevel.allCases()
        val result = mutableListOf<GeohashChannel>()
        
        for (level in levels) {
            val geohash = Geohash.encode(
                latitude = location.latitude,
                longitude = location.longitude,
                precision = level.precision
            )
            result.add(GeohashChannel(level = level, geohash = geohash))
            
            Log.v(TAG, "Generated ${level.displayName}: $geohash")
        }
        
        _availableChannels.postValue(result)
        
        // Recompute teleported status based on current location vs selected channel
        val selectedChannelValue = _selectedChannel.value
        when (selectedChannelValue) {
            is ChannelID.Mesh -> {
                _teleported.postValue(false)
            }
            is ChannelID.Location -> {
                val currentGeohash = Geohash.encode(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    precision = selectedChannelValue.channel.level.precision
                )
                val isTeleported = currentGeohash != selectedChannelValue.channel.geohash
                _teleported.postValue(isTeleported)
                Log.d(TAG, "Teleported status: $isTeleported (current: $currentGeohash, selected: ${selectedChannelValue.channel.geohash})")
            }
            null -> {
                _teleported.postValue(false)
            }
        }
    }

    private fun reverseGeocodeIfNeeded(location: Location) {
        if (!Geocoder.isPresent()) {
            Log.w(TAG, "Geocoder not present on this device")
            return
        }
        
        if (isGeocoding) {
            Log.d(TAG, "Already geocoding, skipping")
            return
        }

        isGeocoding = true
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Starting reverse geocoding")
                
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                
                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    val names = namesByLevel(address)
                    
                    Log.d(TAG, "Reverse geocoding result: $names")
                    _locationNames.postValue(names)
                } else {
                    Log.w(TAG, "No reverse geocoding results")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reverse geocoding failed: ${e.message}")
            } finally {
                isGeocoding = false
            }
        }
    }

    private fun namesByLevel(address: android.location.Address): Map<GeohashChannelLevel, String> {
        val dict = mutableMapOf<GeohashChannelLevel, String>()
        
        // Country
        address.countryName?.takeIf { it.isNotEmpty() }?.let {
            dict[GeohashChannelLevel.REGION] = it
        }
        
        // Province (state/province or county)
        address.adminArea?.takeIf { it.isNotEmpty() }?.let {
            dict[GeohashChannelLevel.PROVINCE] = it
        } ?: address.subAdminArea?.takeIf { it.isNotEmpty() }?.let {
            dict[GeohashChannelLevel.PROVINCE] = it
        }
        
        // City (locality)
        address.locality?.takeIf { it.isNotEmpty() }?.let {
            dict[GeohashChannelLevel.CITY] = it
        } ?: address.subAdminArea?.takeIf { it.isNotEmpty() }?.let {
            dict[GeohashChannelLevel.CITY] = it
        } ?: address.adminArea?.takeIf { it.isNotEmpty() }?.let {
            dict[GeohashChannelLevel.CITY] = it
        }
        
        // Neighborhood
        address.subLocality?.takeIf { it.isNotEmpty() }?.let {
            dict[GeohashChannelLevel.NEIGHBORHOOD] = it
        } ?: address.locality?.takeIf { it.isNotEmpty() }?.let {
            dict[GeohashChannelLevel.NEIGHBORHOOD] = it
        }
        
        // Block: reuse neighborhood/locality granularity without exposing street level
        address.subLocality?.takeIf { it.isNotEmpty() }?.let {
            dict[GeohashChannelLevel.BLOCK] = it
        } ?: address.locality?.takeIf { it.isNotEmpty() }?.let {
            dict[GeohashChannelLevel.BLOCK] = it
        }
        
        return dict
    }

    // MARK: - Channel Persistence
    
    /**
     * Save current channel selection to persistent storage
     */
    private fun saveChannelSelection(channel: ChannelID) {
        try {
            val channelData = when (channel) {
                is ChannelID.Mesh -> {
                    gson.toJson(mapOf("type" to "mesh"))
                }
                is ChannelID.Location -> {
                    gson.toJson(mapOf(
                        "type" to "location",
                        "level" to channel.channel.level.name,
                        "precision" to channel.channel.level.precision,
                        "geohash" to channel.channel.geohash,
                        "displayName" to channel.channel.level.displayName
                    ))
                }
            }
            dataManager?.saveLastGeohashChannel(channelData)
            Log.d(TAG, "Saved channel selection: ${channel.displayName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save channel selection: ${e.message}")
        }
    }
    
    /**
     * Load persisted channel selection from storage
     */
    private fun loadPersistedChannelSelection() {
        try {
            val channelData = dataManager?.loadLastGeohashChannel()
            if (channelData != null) {
                val channelMap = gson.fromJson(channelData, Map::class.java) as? Map<String, Any>
                if (channelMap != null) {
                    val channel = when (channelMap["type"] as? String) {
                        "mesh" -> ChannelID.Mesh
                        "location" -> {
                            val levelName = channelMap["level"] as? String
                            val precision = (channelMap["precision"] as? Double)?.toInt()
                            val geohash = channelMap["geohash"] as? String
                            val displayName = channelMap["displayName"] as? String
                            
                            if (levelName != null && precision != null && geohash != null && displayName != null) {
                                try {
                                    val level = GeohashChannelLevel.valueOf(levelName)
                                    val geohashChannel = GeohashChannel(level, geohash)
                                    ChannelID.Location(geohashChannel)
                                } catch (e: IllegalArgumentException) {
                                    Log.w(TAG, "Invalid geohash level in persisted data: $levelName")
                                    null
                                }
                            } else {
                                Log.w(TAG, "Incomplete location channel data in persistence")
                                null
                            }
                        }
                        else -> {
                            Log.w(TAG, "Unknown channel type in persisted data: ${channelMap["type"]}")
                            null
                        }
                    }
                    
                    if (channel != null) {
                        _selectedChannel.postValue(channel)
                        Log.d(TAG, "Restored persisted channel: ${channel.displayName}")
                    } else {
                        Log.d(TAG, "Could not restore persisted channel, defaulting to Mesh")
                        _selectedChannel.postValue(ChannelID.Mesh)
                    }
                } else {
                    Log.w(TAG, "Invalid channel data format in persistence")
                    _selectedChannel.postValue(ChannelID.Mesh)
                }
            } else {
                Log.d(TAG, "No persisted channel found, defaulting to Mesh")
                _selectedChannel.postValue(ChannelID.Mesh)
            }
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "Failed to parse persisted channel data: ${e.message}")
            _selectedChannel.postValue(ChannelID.Mesh)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted channel: ${e.message}")
            _selectedChannel.postValue(ChannelID.Mesh)
        }
    }
    
    /**
     * Clear persisted channel selection (useful for testing or reset)
     */
    fun clearPersistedChannel() {
        dataManager?.clearLastGeohashChannel()
        _selectedChannel.postValue(ChannelID.Mesh)
        Log.d(TAG, "Cleared persisted channel selection")
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up LocationChannelManager")
        endLiveRefresh()
    }
}
