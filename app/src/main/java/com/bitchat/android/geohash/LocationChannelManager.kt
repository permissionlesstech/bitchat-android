package com.bitchat.android.geohash

import android.Manifest
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.bitchat.android.nostr.NostrIdentityBridge
import kotlinx.coroutines.*
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

    enum class LocationSelectionSource {
        NEARBY,
        MANUAL
    }

    private val locationManager: LocationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val locationProvider: LocationProvider
    private val geocoderProvider: GeocoderProvider = GeocoderFactory.get(context)
    private var geocodingJob: Job? = null
    private val gson = Gson()
    private var dataManager: com.bitchat.android.ui.DataManager? = null
    private var selectedLocationSource: LocationSelectionSource? = null
    private var activeLocationUpdateCallback: ((Location) -> Unit)? = null

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
                if (!isEnabled) {
                    clearLiveLocationState()
                }
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
    
    val locationServicesEnabled: StateFlow<Boolean> = LiveLocationPrivacyGate.enabled

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
        // Choose the best location provider
        val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        locationProvider = if (availability == ConnectionResult.SUCCESS) {
            Log.i(TAG, "Using FusedLocationProvider (Google Play Services)")
            FusedLocationProvider(context)
        } else {
            Log.i(TAG, "Using SystemLocationProvider (Native LocationManager)")
            SystemLocationProvider(context)
        }
        LiveLocationPrivacyGate.addRevocationListener(::cancelLiveLocationWork)

        // Initialize DataManager and load persisted settings
        dataManager = com.bitchat.android.ui.DataManager(context)
        loadLocationServicesState()
        syncPermissionState()
        if (!_systemLocationEnabled.value) clearLiveLocationState()
        loadPersistedChannelSelection()

        // Register for system location changes
        context.registerReceiver(locationStateReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
    }

    // MARK: - Public API (matching iOS interface)

    /**
     * Enable location channels (request permission if needed)
     * UNIFIED: Only requests location if location services are enabled by user
     */
    fun enableLocationChannels() {
        if (!LiveLocationPrivacyGate.isEnabled || !_systemLocationEnabled.value) {
            Log.w(TAG, "Location services disabled (app or system) - not requesting location")
            return
        }

        if (syncPermissionState() == PermissionState.AUTHORIZED) {
            requestOneShotLocation()
        }
    }

    /**
     * Refresh available channels from current location
     */
    fun refreshChannels() {
        if (syncPermissionState() == PermissionState.AUTHORIZED && isLocationServicesEnabled()) {
            requestOneShotLocation()
        }
    }

    /**
     * Begin real-time location updates while a selector UI is visible
     * Uses requestLocationUpdates for continuous updates, plus a one-shot to prime state immediately
     */
    fun beginLiveRefresh(interval: Long = 5000L) {
        if (syncPermissionState() != PermissionState.AUTHORIZED) {
            Log.w(TAG, "Cannot start live refresh - permission not authorized")
            return
        }

        if (!isLocationServicesEnabled()) {
            Log.w(TAG, "Cannot start live refresh - location services disabled")
            return
        }

        endLiveRefresh()
        LiveLocationPrivacyGate.resumeAccess()
        val token = LiveLocationPrivacyGate.captureToken() ?: return
        val callback: (Location) -> Unit = { location ->
            if (canUseLiveLocation(token)) {
                onLocationUpdated(location, token)
            }
        }
        activeLocationUpdateCallback = callback

        // Register for continuous updates from available provider
        val started = LiveLocationPrivacyGate.runIfAllowed(token) {
            locationProvider.requestLocationUpdates(
                intervalMs = interval,
                minDistanceMeters = 5f,
                callback = callback
            )
        }
        if (!started) {
            activeLocationUpdateCallback = null
            return
        }
        
        // Prime state immediately with last known / current location
        requestOneShotLocation()
    }

    /**
     * Stop periodic refreshes when selector UI is dismissed
     */
    fun endLiveRefresh() {
        activeLocationUpdateCallback?.let(locationProvider::removeLocationUpdates)
        activeLocationUpdateCallback = null
    }

    /**
     * Generic selection is intentionally treated as manual. GPS-derived selections must use
     * [selectNearby] so their provenance can be revoked when live location is disabled.
     */
    fun select(channel: ChannelID) {
        when (channel) {
            ChannelID.Mesh -> selectInternal(ChannelID.Mesh, source = null, teleported = false)
            is ChannelID.Location -> selectManual(channel.channel)
        }
    }

    fun selectNearby(channel: GeohashChannel): Boolean {
        val isCurrentNearbyChannel = _availableChannels.value.contains(channel)
        if (!isCurrentNearbyChannel || !isLocationServicesEnabled() ||
            syncPermissionState() != PermissionState.AUTHORIZED
        ) {
            Log.w(TAG, "Blocked nearby channel selection without live-location access")
            return false
        }
        selectInternal(
            ChannelID.Location(channel),
            source = LocationSelectionSource.NEARBY,
            teleported = false
        )
        return true
    }

    fun selectManual(channel: GeohashChannel, teleported: Boolean = true) {
        selectInternal(
            ChannelID.Location(channel),
            source = LocationSelectionSource.MANUAL,
            teleported = teleported || !LiveLocationPrivacyGate.isEnabled
        )
    }

    /**
     * Enable location services (user-controlled toggle)
     */
    fun enableLocationServices() {
        if (!LiveLocationPrivacyGate.isEnabled) {
            LiveLocationPrivacyGate.update(true)
            saveLocationServicesState(true)
        }
        
        // If we have permission and system location is on, start location operations
        if (syncPermissionState() == PermissionState.AUTHORIZED && systemLocationEnabled.value) {
            requestOneShotLocation()
        }
    }

    /**
     * Disable location services (user-controlled toggle)
     */
    fun disableLocationServices() {
        LiveLocationPrivacyGate.update(false)
        saveLocationServicesState(false)
        clearLiveLocationState(invalidateAccess = false)
    }

    /**
     * Check if location services are enabled by the user
     */
    /**
     * Check if both the app toggle and system location are enabled
     */
    fun isLocationServicesEnabled(): Boolean {
        return LiveLocationPrivacyGate.isEnabled && _systemLocationEnabled.value
    }

    fun canUseSelectedLocationChannel(channel: GeohashChannel): Boolean {
        if (_selectedChannel.value != ChannelID.Location(channel)) return false
        return selectedLocationSource == LocationSelectionSource.MANUAL ||
            LiveLocationPrivacyGate.captureToken() != null
    }

    fun isSelectedChannelLiveDerived(channel: GeohashChannel): Boolean =
        _selectedChannel.value == ChannelID.Location(channel) &&
            selectedLocationSource == LocationSelectionSource.NEARBY

    fun liveLocationTokenForSelectedChannel(channel: GeohashChannel): Long? {
        if (!isSelectedChannelLiveDerived(channel)) return null
        return LiveLocationPrivacyGate.captureToken()
    }

    private fun selectInternal(
        channel: ChannelID,
        source: LocationSelectionSource?,
        teleported: Boolean
    ) {
        selectedLocationSource = source
        _teleported.value = when (channel) {
            ChannelID.Mesh -> false
            is ChannelID.Location -> teleported
        }
        _selectedChannel.value = channel
        saveChannelSelection(channel, source)
    }

    /**
     * Revokes all GPS-derived in-memory state and pending work. This deliberately does not
     * change the persisted soft setting when Android's hard permission or system provider is
     * temporarily unavailable.
     */
    private fun clearLiveLocationState(invalidateAccess: Boolean = true) {
        if (invalidateAccess) LiveLocationPrivacyGate.invalidate()
        cancelLiveLocationWork()
        _isLoadingLocation.value = false
        NostrIdentityBridge.clearGeohashIdentityCache(
            _availableChannels.value.map { it.geohash }
        )
        _availableChannels.value = emptyList()
        _locationNames.value = emptyMap()

        when {
            _selectedChannel.value is ChannelID.Location &&
                selectedLocationSource == LocationSelectionSource.NEARBY -> {
                selectInternal(ChannelID.Mesh, source = null, teleported = false)
            }
            _selectedChannel.value is ChannelID.Location -> {
                // Manual channels remain usable for teleports, bookmarks, and DMs. Never use
                // a previously retained GPS fix to classify them while live access is off.
                selectedLocationSource = LocationSelectionSource.MANUAL
                _teleported.value = true
                saveChannelSelection(_selectedChannel.value, selectedLocationSource)
            }
        }
    }

    private fun cancelLiveLocationWork() {
        locationProvider.cancel()
        activeLocationUpdateCallback = null
        geocodingJob?.cancel()
        geocodingJob = null
    }

    // MARK: - Location Operations

    private fun requestOneShotLocation() {
        if (!isLocationServicesEnabled() ||
            syncPermissionState() != PermissionState.AUTHORIZED
        ) {
            Log.w(TAG, "No location permission for one-shot request")
            return
        }

        LiveLocationPrivacyGate.resumeAccess()
        val token = LiveLocationPrivacyGate.captureToken() ?: return
        _isLoadingLocation.value = true

        val started = LiveLocationPrivacyGate.runIfAllowed(token) {
            locationProvider.getLastKnownLocation { cached ->
                if (!canUseLiveLocation(token)) return@getLastKnownLocation

                if (cached != null) {
                    onLocationUpdated(cached, token)
                } else {
                    LiveLocationPrivacyGate.runIfAllowed(token) {
                        locationProvider.requestFreshLocation { fresh ->
                            if (!canUseLiveLocation(token)) return@requestFreshLocation

                            if (fresh != null) {
                                onLocationUpdated(fresh, token)
                            } else {
                                Log.w(TAG, "Failed to get fresh location")
                                _isLoadingLocation.value = false
                            }
                        }
                    }
                }
            }
        }
        if (!started) _isLoadingLocation.value = false
    }

    private fun onLocationUpdated(location: Location, token: Long) {
        LiveLocationPrivacyGate.runIfAllowed(token) {
            if (!_systemLocationEnabled.value || !hasRuntimeLocationPermission()) return@runIfAllowed
            _isLoadingLocation.value = false
            computeChannels(location, token)
            reverseGeocodeIfNeeded(location, token)
        }
    }


    // MARK: - Helpers

    private fun hasRuntimeLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun syncPermissionState(): PermissionState {
        val newState = if (hasRuntimeLocationPermission()) {
            PermissionState.AUTHORIZED
        } else {
            PermissionState.DENIED
        }

        if (_permissionState.value != newState) {
            _permissionState.value = newState
        }

        if (newState == PermissionState.DENIED) {
            clearLiveLocationState()
        }
        return newState
    }

    private fun canUseLiveLocation(token: Long): Boolean {
        return LiveLocationPrivacyGate.accepts(token) &&
            _systemLocationEnabled.value &&
            hasRuntimeLocationPermission()
    }

    private fun computeChannels(location: Location, token: Long) {
        if (!canUseLiveLocation(token)) return

        val levels = GeohashChannelLevel.allCases()
        val result = mutableListOf<GeohashChannel>()

        for (level in levels) {
            val geohash = Geohash.encode(
                latitude = location.latitude,
                longitude = location.longitude,
                precision = level.precision
            )
            result.add(GeohashChannel(level = level, geohash = geohash))
        }

        if (!canUseLiveLocation(token)) return
        _availableChannels.value = result
        
        val selectedChannelValue = _selectedChannel.value
        if (selectedChannelValue is ChannelID.Location &&
            selectedLocationSource == LocationSelectionSource.NEARBY
        ) {
            val currentGeohash = Geohash.encode(
                latitude = location.latitude,
                longitude = location.longitude,
                precision = selectedChannelValue.channel.level.precision
            )
            _teleported.value = currentGeohash != selectedChannelValue.channel.geohash
        } else if (selectedChannelValue is ChannelID.Mesh) {
            _teleported.value = false
        }
    }

    private fun reverseGeocodeIfNeeded(location: Location, token: Long) {
        if (!canUseLiveLocation(token)) return
        geocodingJob?.cancel()

        geocodingJob = scope.launch(Dispatchers.IO) {
            try {
                if (!canUseLiveLocation(token)) return@launch
                val addresses = geocoderProvider.getFromLocation(
                    location.latitude,
                    location.longitude,
                    1,
                    liveLocationToken = token
                )

                if (!isActive || !canUseLiveLocation(token)) return@launch

                if (addresses.isNotEmpty()) {
                    val address = addresses[0]
                    val names = namesByLevel(address)
                    LiveLocationPrivacyGate.runIfAllowed(token) {
                        if (_systemLocationEnabled.value && hasRuntimeLocationPermission()) {
                            _locationNames.value = names
                        }
                    }
                } else {
                    Log.w(TAG, "No reverse geocoding results")
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Reverse geocoding failed")
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
    private fun saveChannelSelection(
        channel: ChannelID,
        source: LocationSelectionSource?
    ) {
        try {
            val channelData = when (channel) {
                is ChannelID.Mesh -> gson.toJson(PersistedChannel(mesh = true))
                is ChannelID.Location -> gson.toJson(
                    PersistedChannel(
                        mesh = false,
                        level = channel.channel.level.name,
                        geohash = channel.channel.geohash,
                        source = source?.name
                    )
                )
            }
            dataManager?.saveLastGeohashChannel(channelData)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save channel selection")
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
                val source = persisted?.selectionSource()
                val canRestore = channel !is ChannelID.Location ||
                    source == LocationSelectionSource.MANUAL ||
                    (LiveLocationPrivacyGate.isEnabled &&
                        _systemLocationEnabled.value &&
                        _permissionState.value == PermissionState.AUTHORIZED)

                if (channel != null && canRestore) {
                    _selectedChannel.value = channel
                    selectedLocationSource = if (channel is ChannelID.Location) source else null
                    _teleported.value = channel is ChannelID.Location &&
                        source == LocationSelectionSource.MANUAL
                } else {
                    _selectedChannel.value = ChannelID.Mesh
                    selectedLocationSource = null
                    _teleported.value = false
                    saveChannelSelection(ChannelID.Mesh, source = null)
                }
            } else {
                _selectedChannel.value = ChannelID.Mesh
                selectedLocationSource = null
            }
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "Failed to parse persisted channel data")
            _selectedChannel.value = ChannelID.Mesh
            selectedLocationSource = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted channel")
            _selectedChannel.value = ChannelID.Mesh
            selectedLocationSource = null
        }
    }

    data class PersistedChannel(
        val mesh: Boolean,
        val level: String? = null,
        val geohash: String? = null,
        val source: String? = null
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

        fun selectionSource(): LocationSelectionSource? {
            if (mesh) return null
            return source?.let {
                runCatching { LocationSelectionSource.valueOf(it) }.getOrNull()
            } ?: LocationSelectionSource.NEARBY
        }
    }

    /**
     * Clear persisted channel selection (useful for testing or reset)
     */
    fun clearPersistedChannel() {
        dataManager?.clearLastGeohashChannel()
        _selectedChannel.value = ChannelID.Mesh
        selectedLocationSource = null
        _teleported.value = false
    }

    // MARK: - Location Services State Persistence

    /**
     * Save location services enabled state to persistent storage
     */
    private fun saveLocationServicesState(enabled: Boolean) {
        try {
            dataManager?.saveLocationServicesEnabled(enabled)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save location services state")
        }
    }

    /**
     * Load persisted location services state from storage
     */
    private fun loadLocationServicesState() {
        try {
            val enabled = dataManager?.isLocationServicesEnabled() ?: false
            LiveLocationPrivacyGate.update(enabled)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load location services state")
            LiveLocationPrivacyGate.update(false)
        }
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        endLiveRefresh()
        locationProvider.cancel()
        
        geocodingJob?.cancel()
        geocodingJob = null
        
        // Unregister receiver
        try { context.unregisterReceiver(locationStateReceiver) } catch (_: Exception) {}
    }
}
