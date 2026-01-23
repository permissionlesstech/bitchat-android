package com.bitchat.android.features.festival

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * FestivalScheduleManager - ViewModel for festival schedule data
 * 
 * Android equivalent of iOS FestivalScheduleManager.
 * Manages festival data loading, filtering, and real-time schedule updates.
 * 
 * Usage:
 * ```kotlin
 * val viewModel: FestivalScheduleManager = viewModel()
 * val festivalData by viewModel.festivalData.collectAsState()
 * val nowPlaying by viewModel.nowPlaying.collectAsState()
 * ```
 */
class FestivalScheduleManager(application: Application) : AndroidViewModel(application) {
    
    // ========================================================================
    // MARK: - State
    // ========================================================================
    
    private val _festivalData = MutableStateFlow<FestivalData?>(null)
    val festivalData: StateFlow<FestivalData?> = _festivalData.asStateFlow()
    
    private val _selectedDay = MutableStateFlow<String?>(null)
    val selectedDay: StateFlow<String?> = _selectedDay.asStateFlow()
    
    private val _selectedStage = MutableStateFlow<String?>(null)
    val selectedStage: StateFlow<String?> = _selectedStage.asStateFlow()
    
    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()
    
    private val _nowPlaying = MutableStateFlow<List<ScheduledSet>>(emptyList())
    val nowPlaying: StateFlow<List<ScheduledSet>> = _nowPlaying.asStateFlow()
    
    private val _upcomingSoon = MutableStateFlow<List<ScheduledSet>>(emptyList())
    val upcomingSoon: StateFlow<List<ScheduledSet>> = _upcomingSoon.asStateFlow()
    
    // ========================================================================
    // MARK: - Initialization
    // ========================================================================
    
    init {
        loadSchedule()
    }
    
    /**
     * Load festival schedule from bundled JSON asset
     */
    fun loadSchedule() {
        viewModelScope.launch {
            val data = loadFestivalData(getApplication())
            if (data != null) {
                _festivalData.value = data
                _selectedDay.value = data.festival.dates.start
                _isLoaded.value = true
                updateNowPlaying()
            }
        }
    }
    
    // ========================================================================
    // MARK: - Computed Properties
    // ========================================================================
    
    /**
     * Festival timezone identifier
     */
    val timezone: String
        get() = _festivalData.value?.festival?.timezone ?: "America/Los_Angeles"
    
    /**
     * All unique days from the schedule, sorted
     */
    val days: List<String>
        get() = _festivalData.value?.sets
            ?.map { it.day }
            ?.distinct()
            ?.sorted()
            ?: emptyList()
    
    /**
     * All stages
     */
    val stages: List<Stage>
        get() = _festivalData.value?.stages ?: emptyList()
    
    /**
     * Custom festival channels
     */
    val customChannels: List<CustomChannel>
        get() = _festivalData.value?.customChannels ?: emptyList()
    
    /**
     * Points of interest
     */
    val pointsOfInterest: List<PointOfInterest>
        get() = _festivalData.value?.pointsOfInterest ?: emptyList()
    
    /**
     * Map bounds for the festival
     */
    val mapBounds: MapBounds?
        get() = _festivalData.value?.mapBounds
    
    // ========================================================================
    // MARK: - Selection
    // ========================================================================
    
    /**
     * Set the selected day
     */
    fun selectDay(day: String) {
        _selectedDay.value = day
    }
    
    /**
     * Set the selected stage filter (null for all stages)
     */
    fun selectStage(stageId: String?) {
        _selectedStage.value = stageId
    }
    
    // ========================================================================
    // MARK: - Filtering
    // ========================================================================
    
    /**
     * Get sets for a specific day, sorted by start time
     * 
     * @param day Day string in "yyyy-MM-dd" format
     * @return List of sets sorted by start time
     */
    fun setsForDay(day: String): List<ScheduledSet> {
        return _festivalData.value?.sets
            ?.filter { it.day == day }
            ?.sortedBy { it.startDateTime(timezone) }
            ?: emptyList()
    }
    
    
    /** Convenience alias for setsForDay */
    fun sets(day: String): List<ScheduledSet> = setsForDay(day)
    /**
     * Get sets for a specific day and stage
     * 
     * @param day Day string in "yyyy-MM-dd" format
     * @param stageId Stage ID to filter by
     * @return List of sets for the specified day and stage
     */
    fun setsForDayAndStage(day: String, stageId: String): List<ScheduledSet> {
        return setsForDay(day).filter { it.stage == stageId }
    }
    
    /**
     * Get sets for the currently selected day and optional stage filter
     */
    fun filteredSets(): List<ScheduledSet> {
        val day = _selectedDay.value ?: return emptyList()
        val stageId = _selectedStage.value
        
        return if (stageId != null) {
            setsForDayAndStage(day, stageId)
        } else {
            setsForDay(day)
        }
    }
    
    // ========================================================================
    // MARK: - Now Playing / Upcoming
    // ========================================================================
    
    /**
     * Update the now playing and upcoming lists
     * Call this periodically (e.g., every minute) to keep UI current
     */
    fun updateNowPlaying() {
        val data = _festivalData.value ?: return
        val now = LocalDateTime.now()
        
        _nowPlaying.value = data.sets
            .filter { it.isNowPlaying(now, timezone) }
        
        _upcomingSoon.value = data.sets
            .filter { it.isUpcoming(30, now, timezone) }
            .sortedBy { it.startDateTime(timezone) }
    }
    
    // ========================================================================
    // MARK: - Stage Lookup
    // ========================================================================
    
    /**
     * Get stage by ID
     * 
     * @param stageId Stage ID to look up
     * @return Stage or null if not found
     */
    fun stageById(stageId: String): Stage? {
        return _festivalData.value?.stages?.find { it.id == stageId }
    }
    
    
    /** Convenience alias for stageById */
    fun stage(stageId: String): Stage? = stageById(stageId)
    /**
     * Get the nearest stage to a location
     * 
     * Useful for auto-joining stage channels when user is near a stage.
     * 
     * @param location User's current location
     * @return Nearest stage or null if no stages have coordinates
     */
    fun nearestStage(location: Location): Stage? {
        return _festivalData.value?.stages
            ?.filter { it.hasCoordinates }
            ?.minByOrNull { it.distanceTo(location) ?: Float.MAX_VALUE }
    }
    
    /**
     * Get the nearest stage within a threshold distance
     * 
     * @param location User's current location
     * @param maxDistanceMeters Maximum distance in meters
     * @return Nearest stage if within threshold, null otherwise
     */
    fun nearestStageWithinDistance(location: Location, maxDistanceMeters: Float = 100f): Stage? {
        val nearest = nearestStage(location) ?: return null
        val distance = nearest.distanceTo(location) ?: return null
        return if (distance <= maxDistanceMeters) nearest else null
    }
    
    // ========================================================================
    // MARK: - Formatting Helpers
    // ========================================================================
    
    /**
     * Format day string for display (e.g., "Friday, Aug 7")
     * 
     * @param day Day string in "yyyy-MM-dd" format
     * @return Formatted display string
     */
    fun formatDayForDisplay(day: String): String {
        return try {
            val date = LocalDate.parse(day)
            val formatter = java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d")
            date.format(formatter)
        } catch (e: Exception) {
            day
        }
    }
    
    /**
     * Get short day name (e.g., "Fri")
     * 
     * @param day Day string in "yyyy-MM-dd" format
     * @return Short day name
     */
    fun shortDayName(day: String): String {
        return try {
            val date = LocalDate.parse(day)
            val formatter = java.time.format.DateTimeFormatter.ofPattern("EEE")
            date.format(formatter)
        } catch (e: Exception) {
            day
        }
    }
}


