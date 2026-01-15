package com.bitchat.android.geohash

import android.Manifest
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import java.util.*
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

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
        DENIED,
        AUTHORIZED
    }

    private val locationManager: LocationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val geocoderProvider: GeocoderProvider = GeocoderFactory.get(context)
    private var lastLocation: Location? = null
    private var geocodingJob: Job? = null
    private val gson = Gson()
    private var dataManager: com.bitchat.android.ui.DataManager? = null

    private fun checkSystemLocationEnabled(): Boolean {
        return try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            false
        }
    }

    private val locationStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                val isEnabled = checkSystemLocationEnabled()
                Log.d(TAG, "System location state changed: $isEnabled")
                _systemLocationEnabled.value = isEnabled
            }
        }
    }

    // Published state for UI bindings (matching iOS @Published properties)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _permissionState = MutableStateFlow(PermissionState.DENIED)
    val permissionState: StateFlow<PermissionState> = _permissionState

    private val _availableChannels = MutableStateFlow<List<GeohashChannel>>(emptyList())
    val availableChannels: StateFlow<List<GeohashChannel>> = _availableChannels

    private val _selectedChannel = MutableStateFlow<ChannelID>(ChannelID.Mesh)
    val selectedChannel: StateFlow<ChannelID> = _selectedChannel

    private val _teleported = MutableStateFlow(false)
    val teleported: StateFlow<Boolean> = _teleported

    private val _locationNames = MutableStateFlow<Map<GeohashChannelLevel, String>>(emptyMap())
    val locationNames: StateFlow<Map<GeohashChannelLevel, String>> = _locationNames
    
    private val _isLoadingLocation = MutableStateFlow(false)
    val isLoadingLocation: StateFlow<Boolean> = _isLoadingLocation
    
    private val _locationServicesEnabled = MutableStateFlow(false)
    val locationServicesEnabled: StateFlow<Boolean> = _locationServicesEnabled

    private val _systemLocationEnabled = MutableStateFlow(checkSystemLocationEnabled())
    val systemLocationEnabled: StateFlow<Boolean> = _systemLocationEnabled

    val effectiveLocationEnabled: StateFlow<Boolean> = combine(
        locationServicesEnabled,
        systemLocationEnabled
    ) { appToggle, systemToggle ->
        appToggle && systemToggle
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        false
    )


    init {
        updatePermissionState()
        // Initialize DataManager and load persisted settings
        dataManager = com.bitchat.android.ui.DataManager(context)
        loadPersistedChannelSelection()
        loadLocationServicesState()

        // Register for system location changes
        context.registerReceiver(locationStateReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
    }

    // MARK: - Public API (matching iOS interface)

    /**
     * Enable location channels (request permission if needed)
     * UNIFIED: Only requests location if location services are enabled by user
     */
    fun enableLocationChannels() {
        Log.d(TAG, "enableLocationChannels() called")
        
        if (!_locationServicesEnabled.value || !_systemLocationEnabled.value) {
            Log.w(TAG, "Location services disabled (app or system) - not requesting location")
            return
        }
        

        if (getCurrentPermissionStatus() == PermissionState.AUTHORIZED) {
            Log.d(TAG, "Permission authorized - requesting location")
            _permissionState.value = PermissionState.AUTHORIZED
            requestOneShotLocation()
        } else {
            Log.d(TAG, "Permission not granted")
            _permissionState.value = PermissionState.DENIED
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
     * Begin real-time location updates while a selector UI is visible
     * Uses requestLocationUpdates for continuous updates, plus a one-shot to prime state immediately
     */
    fun beginLiveRefresh(interval: Long = 5000L) {
        Log.d(TAG, "Beginning live refresh (continuous updates)")

        if (_permissionState.value != PermissionState.AUTHORIZED) {
            Log.w(TAG, "Cannot start live refresh - permission not authorized")
            return
        }

        if (!isLocationServicesEnabled()) {
            Log.w(TAG, "Cannot start live refresh - location services disabled")
            return
        }

        // Register for continuous updates from available providers
        try {
            if (hasLocationPermission()) {
                val providers = listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER
                )

                providers.forEach { provider ->
                    if (locationManager.isProviderEnabled(provider)) {
                        // 2s min time, 5m min distance for responsive yet battery-aware updates
                        locationManager.requestLocationUpdates(
                            provider,
                            interval,
                            5f,
                            continuousLocationListener
                        )
                        Log.d(TAG, "Registered continuous updates for $provider")
                    }
                }

                // Prime state immediately with last known / current location
                requestOneShotLocation()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing location permission for continuous updates: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register continuous updates: ${e.message}")
        }
    }

    /**
     * Stop periodic refreshes when selector UI is dismissed
     */
    fun endLiveRefresh() {
        Log.d(TAG, "Ending live refresh")
        // Unregister continuous updates listener
        try {
            locationManager.removeUpdates(continuousLocationListener)
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
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
                    _teleported.value = false
                }
                is ChannelID.Location -> {
                    val currentGeohash = Geohash.encode(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        precision = channel.channel.level.precision
                    )
                    val isTeleportedNow = currentGeohash != channel.channel.geohash
                    _teleported.value = isTeleportedNow
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
        _teleported.value = teleported
    }

    /**
     * Enable location services (user-controlled toggle)
     */
    fun enableLocationServices() {
        Log.d(TAG, "enableLocationServices() called by user")
        _locationServicesEnabled.value = true
        saveLocationServicesState(true)
        
        // If we have permission and system location is on, start location operations
        if (_permissionState.value == PermissionState.AUTHORIZED && systemLocationEnabled.value) {
            requestOneShotLocation()
        }
    }

    /**
     * Disable location services (user-controlled toggle)
     */
    fun disableLocationServices() {
        Log.d(TAG, "disableLocationServices() called by user")
        _locationServicesEnabled.value = false
        saveLocationServicesState(false)
        
        // Stop any ongoing location operations
        endLiveRefresh()
        
        // Clear available channels when location is disabled
        _availableChannels.value = emptyList()
        _locationNames.value = emptyMap()
        
        // If user had a location channel selected, switch back to mesh
        if (_selectedChannel.value is ChannelID.Location) {
            select(ChannelID.Mesh)
        }
    }

    /**
     * Check if location services are enabled by the user
     */
    /**
     * Check if both the app toggle and system location are enabled
     */
    fun isLocationServicesEnabled(): Boolean {
        return _locationServicesEnabled.value && _systemLocationEnabled.value
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
                _isLoadingLocation.value = false // Make sure loading state is off
                computeChannels(lastKnownLocation)
                reverseGeocodeIfNeeded(lastKnownLocation)
            } else {
                Log.d(TAG, "No last known location available")
                // Set loading state to true so UI can show a spinner
                _isLoadingLocation.value = true
                
                // Request a fresh location only when we don't have a last known location
                Log.d(TAG, "Requesting fresh location...")
                requestFreshLocation()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception requesting location: ${e.message}")
            _isLoadingLocation.value = false // Turn off loading state on error
            updatePermissionState()
        }
    }
    
    // Continuous location listener for real-time updates
    private val continuousLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            Log.d(TAG, "Real-time location: ${location.latitude}, ${location.longitude} acc=${location.accuracy}m")
            lastLocation = location
            _isLoadingLocation.value = false
            computeChannels(location)
            reverseGeocodeIfNeeded(location)
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            // Deprecated but can still be called on older devices
            Log.v(TAG, "Provider status changed: $provider -> $status")
        }

        override fun onProviderEnabled(provider: String) {
            Log.d(TAG, "Provider enabled: $provider")
        }

        override fun onProviderDisabled(provider: String) {
            Log.d(TAG, "Provider disabled: $provider")
        }
    }

    // One-time location listener to get a fresh location update
    private val oneShotLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            Log.d(TAG, "One-shot location: ${location.latitude}, ${location.longitude}")
            lastLocation = location
            computeChannels(location)
            reverseGeocodeIfNeeded(location)

            // Update loading state to indicate we have a location now
            _isLoadingLocation.value = false
            
            // Remove this listener after getting the update
            try {
                locationManager.removeUpdates(this)
            } catch (e: SecurityException) {
                Log.e(TAG, "Error removing location listener: ${e.message}")
            }
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            // Required for compatibility with older platform versions
        }

        override fun onProviderEnabled(provider: String) {
            // Required for compatibility with older platform versions
        }

        override fun onProviderDisabled(provider: String) {
            // Required for compatibility with older platform versions
        }
    }
    
    // Request a fresh location update using getCurrentLocation instead of continuous updates
    private fun requestFreshLocation() {
        if (!hasLocationPermission()) {
            _isLoadingLocation.value = false // Turn off loading state if no permission
            return
        }
        
        try {
            // Set loading state to true to indicate we're actively trying to get a location
            _isLoadingLocation.value = true
            
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
                                _isLoadingLocation.value = false
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
                _isLoadingLocation.value = false
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception requesting location: ${e.message}")
            _isLoadingLocation.value = false // Turn off loading state on error
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting location: ${e.message}")
            _isLoadingLocation.value = false // Turn off loading state on error
        }
    }

    // MARK: - Helpers

    private fun getCurrentPermissionStatus(): PermissionState {
        return if (hasLocationPermission()) {
            PermissionState.AUTHORIZED
        } else {
            PermissionState.DENIED
        }
    }

    private fun updatePermissionState() {
        val newState = getCurrentPermissionStatus()
        Log.d(TAG, "Permission state updated to: $newState")
        _permissionState.value = newState
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
        
        _availableChannels.value = result
        
        // Recompute teleported status based on current location vs selected channel
        val selectedChannelValue = _selectedChannel.value
        when (selectedChannelValue) {
            is ChannelID.Mesh -> {
                _teleported.value = false
            }
            is ChannelID.Location -> {
                val currentGeohash = Geohash.encode(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    precision = selectedChannelValue.channel.level.precision
                )
                val isTeleported = currentGeohash != selectedChannelValue.channel.geohash
                _teleported.value = isTeleported
                Log.d(TAG, "Teleported status: $isTeleported (current: $currentGeohash, selected: ${selectedChannelValue.channel.geohash})")
            }
        }
    }

    private fun reverseGeocodeIfNeeded(location: Location) {
        // Cancel any pending geocoding job to avoid race conditions
        geocodingJob?.cancel()

        geocodingJob = scope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting reverse geocoding")
                
                val addresses = geocoderProvider.getFromLocation(location.latitude, location.longitude, 1)

                if (!isActive) return@launch

                if (addresses.isNotEmpty()) {
                    val address = addresses[0]
                    val names = namesByLevel(address)
                    Log.d(TAG, "Reverse geocoding result: $names")
                    _locationNames.value = names
                } else {
                    Log.w(TAG, "No reverse geocoding results")
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Reverse geocoding failed: ${e.message}")
                }
            }
        }
    }

    private fun namesByLevel(address: android.location.Address): Map<GeohashChannelLevel, String> {
        val dict = mutableMapOf<GeohashChannelLevel, String>()
        
        // Country
        address.countryName?.takeIf { it.isNotEmpty() }?.let {
            dict[GeohashChannelLevel.REGION] = it
        }
        
        // Province (state/province or county or city)
        address.adminArea?.takeIf { it.isNotEmpty() }?.let {
            dict[GeohashChannelLevel.PROVINCE] = it
        } ?: address.subAdminArea?.takeIf { it.isNotEmpty() }?.let {
            dict[GeohashChannelLevel.PROVINCE] = it
        } ?: address.locality?.takeIf { it.isNotEmpty() }?.let {
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
                is ChannelID.Mesh -> gson.toJson(PersistedChannel(mesh = true))
                is ChannelID.Location -> gson.toJson(
                    PersistedChannel(
                        mesh = false,
                        level = channel.channel.level.name,
                        geohash = channel.channel.geohash
                    )
                )
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
            if (!channelData.isNullOrBlank()) {
                val persisted = gson.fromJson(channelData, PersistedChannel::class.java)
                val channel = persisted?.toChannel()
                if (channel != null) {
                    _selectedChannel.value = channel
                    Log.d(TAG, "Restored persisted channel: ${channel.displayName}")
                } else {
                    Log.d(TAG, "Could not restore persisted channel, defaulting to Mesh")
                    _selectedChannel.value = ChannelID.Mesh
                }
            } else {
                Log.d(TAG, "No persisted channel found, defaulting to Mesh")
                _selectedChannel.value = ChannelID.Mesh
            }
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "Failed to parse persisted channel data: ${e.message}")
            _selectedChannel.value = ChannelID.Mesh
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted channel: ${e.message}")
            _selectedChannel.value = ChannelID.Mesh
        }
    }

    data class PersistedChannel(
        val mesh: Boolean,
        val level: String? = null,
        val geohash: String? = null
    ) {
        fun toChannel(): ChannelID? {
            return if (mesh) {
                ChannelID.Mesh
            } else {
                val levelName = level ?: return null
                val gh = geohash ?: return null
                ChannelID.Location.fromPersisted(levelName, gh)
            }
        }
    }

    /**
     * Clear persisted channel selection (useful for testing or reset)
     */
    fun clearPersistedChannel() {
        dataManager?.clearLastGeohashChannel()
        _selectedChannel.value = ChannelID.Mesh
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
            _locationServicesEnabled.value = enabled
            Log.d(TAG, "Loaded location services state: $enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load location services state: ${e.message}")
            _locationServicesEnabled.value = false
        }
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up LocationChannelManager")
        endLiveRefresh()
        
        geocodingJob?.cancel()
        geocodingJob = null
        
        // Unregister receiver
        try { context.unregisterReceiver(locationStateReceiver) } catch (_: Exception) {}
        
        // Remove listeners to prevent memory leaks
        try { locationManager.removeUpdates(oneShotLocationListener) } catch (_: Exception) {}
        try { locationManager.removeUpdates(continuousLocationListener) } catch (_: Exception) {}
        // For Android 11+, getCurrentLocation doesn't need explicit cleanup as it's a one-time operation
    }
}
