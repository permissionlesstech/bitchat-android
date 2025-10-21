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
    
    // Add a new LiveData property to indicate when location is being fetched
    private val _isLoadingLocation = MutableLiveData(false)
    val isLoadingLocation: LiveData<Boolean> = _isLoadingLocation
    
    // Add a new LiveData property to track if location services are enabled by user
    private val _locationServicesEnabled = MutableLiveData(false)
    val locationServicesEnabled: LiveData<Boolean> = _locationServicesEnabled

    init {
        updatePermissionState()
        // Initialize DataManager and load persisted settings
        dataManager = com.bitchat.android.ui.DataManager(context)
        loadPersistedChannelSelection()
        loadLocationServicesState()
    }

    // MARK: - Public API (matching iOS interface)

    /**
     * Enable location channels (request permission if needed)
     * UNIFIED: Only requests location if location services are enabled by user
     */
    fun enableLocationChannels() {
        Log.d(TAG, "enableLocationChannels() called")
        
        // UNIFIED FIX: Check if location services are enabled by user
        if (!isLocationServicesEnabled()) {
            Log.w(TAG, "Location services disabled by user - not requesting location")
            return
        }
        
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
        if (_permissionState.value == PermissionState.AUTHORIZED && isLocationServicesEnabled()) {
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
        
        if (!isLocationServicesEnabled()) {
            Log.w(TAG, "Cannot start live refresh - location services disabled by user")
            return
        }

        // Cancel existing timer
        refreshTimer?.cancel()
        
        // Start new timer with coroutines
        refreshTimer = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                if (isLocationServicesEnabled()) {
                    requestOneShotLocation()
                }
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
        // Use synchronous set to avoid race with background recomputation
        _selectedChannel.value = channel
        saveChannelSelection(channel)

        // Immediately recompute teleported status against the latest known location
        lastLocation?.let { location ->
            when (channel) {
                is ChannelID.Mesh -> {
                    _teleported.postValue(false)
                }
                is ChannelID.Location -> {
                    val currentGeohash = Geohash.encode(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        precision = channel.channel.level.precision
                    )
                    val isTeleportedNow = currentGeohash != channel.channel.geohash
                    _teleported.postValue(isTeleportedNow)
                    Log.d(TAG, "Teleported (immediate recompute): $isTeleportedNow (current: $currentGeohash, selected: ${channel.channel.geohash})")
                }
            }
        }
    }
    
    /**
     * Set teleported status (for manual geohash teleportation)
     */
    fun setTeleported(teleported: Boolean) {
        Log.d(TAG, "Setting teleported status: $teleported")
        _teleported.postValue(teleported)
    }

    /**
     * Enable location services (user-controlled toggle)
     */
    fun enableLocationServices() {
        Log.d(TAG, "enableLocationServices() called by user")
        _locationServicesEnabled.postValue(true)
        saveLocationServicesState(true)
        
        // If we have permission, start location operations
        if (_permissionState.value == PermissionState.AUTHORIZED) {
            requestOneShotLocation()
        }
    }

    /**
     * Disable location services (user-controlled toggle)
     */
    fun disableLocationServices() {
        Log.d(TAG, "disableLocationServices() called by user")
        _locationServicesEnabled.postValue(false)
        saveLocationServicesState(false)
        
        // Stop any ongoing location operations
        endLiveRefresh()
        
        // Clear available channels when location is disabled
        _availableChannels.postValue(emptyList())
        _locationNames.postValue(emptyMap())
        
        // If user had a location channel selected, switch back to mesh
        if (_selectedChannel.value is ChannelID.Location) {
            select(ChannelID.Mesh)
        }
    }

    /**
     * Check if location services are enabled by the user
     */
    fun isLocationServicesEnabled(): Boolean {
        return _locationServicesEnabled.value ?: false
    }

    // MARK: - Location Operations

    private fun requestOneShotLocation() {
        if (!hasLocationPermission()) {
            Log.w(TAG, "No location permission for one-shot request")
            return
        }

        Log.d(TAG, "Requesting one-shot location")
        
        try {
            // Try to get last known location from all available providers
            var lastKnownLocation: Location? = null
            
            // Get all available providers and try each one
            val providers = locationManager.getProviders(true)
            for (provider in providers) {
                val location = locationManager.getLastKnownLocation(provider)
                if (location != null) {
                    // If we find a location, check if it's more recent than what we have
                    if (lastKnownLocation == null || location.time > lastKnownLocation.time) {
                        lastKnownLocation = location
                    }
                }
            }

            if (lastKnownLocation == null) {
                lastKnownLocation = lastLocation;
            }
            
            // Use last known location if we have one
            if (lastKnownLocation != null) {
                Log.d(TAG, "Using last known location: ${lastKnownLocation.latitude}, ${lastKnownLocation.longitude}")
                lastLocation = lastKnownLocation
                _isLoadingLocation.postValue(false) // Make sure loading state is off
                computeChannels(lastKnownLocation)
                reverseGeocodeIfNeeded(lastKnownLocation)
            } else {
                Log.d(TAG, "No last known location available")
                // Set loading state to true so UI can show a spinner
                _isLoadingLocation.postValue(true)
                
                // Request a fresh location only when we don't have a last known location
                Log.d(TAG, "Requesting fresh location...")
                requestFreshLocation()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception requesting location: ${e.message}")
            _isLoadingLocation.postValue(false) // Turn off loading state on error
            updatePermissionState()
        }
    }
    
    // One-time location listener to get a fresh location update
    private val oneShotLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            Log.d(TAG, "Fresh location received: ${location.latitude}, ${location.longitude}")
            lastLocation = location
            computeChannels(location)
            reverseGeocodeIfNeeded(location)
            
            // Update loading state to indicate we have a location now
            _isLoadingLocation.postValue(false)
            
            // Remove this listener after getting the update
            try {
                locationManager.removeUpdates(this)
            } catch (e: SecurityException) {
                Log.e(TAG, "Error removing location listener: ${e.message}")
            }
        }
    }
    
    // Request a fresh location update using getCurrentLocation instead of continuous updates
    private fun requestFreshLocation() {
        if (!hasLocationPermission()) {
            _isLoadingLocation.postValue(false) // Turn off loading state if no permission
            return
        }
        
        try {
            // Set loading state to true to indicate we're actively trying to get a location
            _isLoadingLocation.postValue(true)
            
            // Try common providers in order of preference
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            
            var providerFound = false
            for (provider in providers) {
                if (locationManager.isProviderEnabled(provider)) {
                    Log.d(TAG, "Getting current location from $provider")
                    
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        // For Android 11+ (API 30+), use getCurrentLocation
                        locationManager.getCurrentLocation(
                            provider,
                            null, // No cancellation signal
                            context.mainExecutor,
                            { location ->
                                if (location != null) {
                                    Log.d(TAG, "Fresh location received: ${location.latitude}, ${location.longitude}")
                                    lastLocation = location
                                    computeChannels(location)
                                    reverseGeocodeIfNeeded(location)
                                } else {
                                    Log.w(TAG, "Received null location from getCurrentLocation")
                                }
                                // Update loading state to indicate we have a location now
                                _isLoadingLocation.postValue(false)
                            }
                        )
                    } else {
                        // For older versions, fall back to one-shot requestSingleUpdate
                        locationManager.requestSingleUpdate(
                            provider,
                            oneShotLocationListener,
                            null // Looper - null uses the main thread
                        )
                    }
                    
                    providerFound = true
                    break
                }
            }
            
            // If no provider was available, turn off loading state
            if (!providerFound) {
                Log.w(TAG, "No location providers available")
                _isLoadingLocation.postValue(false)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception requesting location: ${e.message}")
            _isLoadingLocation.postValue(false) // Turn off loading state on error
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting location: ${e.message}")
            _isLoadingLocation.postValue(false) // Turn off loading state on error
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

    // MARK: - Location Services State Persistence

    /**
     * Save location services enabled state to persistent storage
     */
    private fun saveLocationServicesState(enabled: Boolean) {
        try {
            dataManager?.saveLocationServicesEnabled(enabled)
            Log.d(TAG, "Saved location services state: $enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save location services state: ${e.message}")
        }
    }

    /**
     * Load persisted location services state from storage
     */
    private fun loadLocationServicesState() {
        try {
            val enabled = dataManager?.isLocationServicesEnabled() ?: false
            _locationServicesEnabled.postValue(enabled)
            Log.d(TAG, "Loaded location services state: $enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load location services state: ${e.message}")
            _locationServicesEnabled.postValue(false)
        }
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up LocationChannelManager")
        endLiveRefresh()
        
        // For older Android versions, remove any remaining location listener to prevent memory leaks
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            try {
                locationManager.removeUpdates(oneShotLocationListener)
            } catch (e: SecurityException) {
                Log.e(TAG, "Error removing location listener during cleanup: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup: ${e.message}")
            }
        }
        // For Android 11+, getCurrentLocation doesn't need explicit cleanup as it's a one-time operation
    }
}
