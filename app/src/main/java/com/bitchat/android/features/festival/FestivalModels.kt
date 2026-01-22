package com.bitchat.android.features.festival

import android.content.Context
import android.location.Location
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Festival data models for Festivus Mestivus Android port.
 * 
 * These models mirror the iOS FestivalModels.swift exactly to ensure
 * the same FestivalSchedule.json can be used on both platforms.
 */

// ============================================================================
// MARK: - Root Data Model
// ============================================================================

@Serializable
data class FestivalData(
    val festival: FestivalInfo,
    val stages: List<Stage>,
    val sets: List<ScheduledSet>,
    val customChannels: List<CustomChannel>? = null,
    val mapBounds: MapBounds? = null,
    val pointsOfInterest: List<PointOfInterest>? = null
)

// ============================================================================
// MARK: - Festival Info
// ============================================================================

@Serializable
data class FestivalInfo(
    val name: String,
    val location: String,
    val dates: FestivalDates,
    val gatesOpen: String,
    val musicStart: String,
    val musicEnd: String,
    val timezone: String? = null
) {
    val timezoneIdentifier: String
        get() = timezone ?: "America/Los_Angeles"
    
    val zoneId: ZoneId
        get() = ZoneId.of(timezoneIdentifier)
}

@Serializable
data class FestivalDates(
    val start: String,  // "2026-08-07"
    val end: String     // "2026-08-09"
) {
    val startDate: LocalDate
        get() = LocalDate.parse(start)
    
    val endDate: LocalDate
        get() = LocalDate.parse(end)
}

// ============================================================================
// MARK: - Stage
// ============================================================================

@Serializable
data class Stage(
    val id: String,
    val name: String,
    val description: String,
    val color: String,          // Hex color like "#FF6B6B"
    val geohash: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
) {
    /**
     * Convert hex color to Compose Color
     */
    val composeColor: Color
        get() = parseHexColor(color)
    
    /**
     * Check if stage has valid coordinates
     */
    val hasCoordinates: Boolean
        get() = latitude != null && longitude != null
    
    /**
     * Channel name for this stage (e.g., "#lands-end")
     */
    val channelName: String
        get() = "#$id"
    
    /**
     * Calculate distance from a location to this stage
     */
    fun distanceTo(location: Location): Float? {
        if (!hasCoordinates) return null
        val stageLocation = Location("").apply {
            this.latitude = this@Stage.latitude!!
            this.longitude = this@Stage.longitude!!
        }
        return location.distanceTo(stageLocation)
    }
}

// ============================================================================
// MARK: - Scheduled Set
// ============================================================================

@Serializable
data class ScheduledSet(
    val id: String,
    val artist: String,
    val stage: String,      // Stage ID reference
    val day: String,        // "2026-08-07"
    val start: String,      // "20:30"
    val end: String,        // "22:00"
    val genre: String? = null
) {
    /**
     * Parse start time as LocalDateTime
     */
    fun startDateTime(timezone: String = "America/Los_Angeles"): LocalDateTime {
        val date = LocalDate.parse(day)
        val time = LocalTime.parse(start, DateTimeFormatter.ofPattern("HH:mm"))
        return LocalDateTime.of(date, time)
    }
    
    /**
     * Parse end time as LocalDateTime
     */
    fun endDateTime(timezone: String = "America/Los_Angeles"): LocalDateTime {
        val date = LocalDate.parse(day)
        val time = LocalTime.parse(end, DateTimeFormatter.ofPattern("HH:mm"))
        return LocalDateTime.of(date, time)
    }
    
    /**
     * Check if this set is currently playing
     * 
     * @param currentTime Current time to check against
     * @param timezone Festival timezone
     * @return true if set is currently in progress
     */
    fun isNowPlaying(
        currentTime: LocalDateTime = LocalDateTime.now(),
        timezone: String = "America/Los_Angeles"
    ): Boolean {
        val startTime = startDateTime(timezone)
        val endTime = endDateTime(timezone)
        return currentTime >= startTime && currentTime < endTime
    }
    
    /**
     * Check if this set is upcoming within the specified minutes
     * 
     * @param minutes Number of minutes to look ahead
     * @param currentTime Current time to check against
     * @param timezone Festival timezone
     * @return true if set starts within the specified window
     */
    fun isUpcoming(
        minutes: Int = 30,
        currentTime: LocalDateTime = LocalDateTime.now(),
        timezone: String = "America/Los_Angeles"
    ): Boolean {
        val startTime = startDateTime(timezone)
        val threshold = currentTime.plusMinutes(minutes.toLong())
        return startTime > currentTime && startTime <= threshold
    }
    
    /**
     * Formatted time range string (e.g., "8:30 PM - 10:00 PM")
     */
    val timeRangeString: String
        get() {
            val startTime = startDateTime()
            val endTime = endDateTime()
            val formatter = DateTimeFormatter.ofPattern("h:mm a")
            return "${startTime.format(formatter)} - ${endTime.format(formatter)}"
        }
}

// ============================================================================
// MARK: - Custom Channel
// ============================================================================

@Serializable
data class CustomChannel(
    val id: String,
    val name: String,
    val description: String,
    val icon: String,       // SF Symbol name (map to Material icon in UI)
    val color: String       // Hex color
) {
    val composeColor: Color
        get() = parseHexColor(color)
    
    /**
     * Map iOS SF Symbol names to Material Icons
     * 
     * Call this from the UI layer where you have access to Icons
     */
    val materialIconName: String
        get() = when (icon) {
            "person.2.fill" -> "Groups"
            "magnifyingglass" -> "Search"
            "car.fill" -> "DirectionsCar"
            "fork.knife" -> "Restaurant"
            "music.note.list" -> "QueueMusic"
            else -> "Tag"
        }
}

// ============================================================================
// MARK: - Map Bounds
// ============================================================================

@Serializable
data class MapBounds(
    val northEast: Coordinate,
    val southWest: Coordinate
) {
    @Serializable
    data class Coordinate(
        val latitude: Double,
        val longitude: Double
    )
    
    val center: Coordinate
        get() = Coordinate(
            latitude = (northEast.latitude + southWest.latitude) / 2,
            longitude = (northEast.longitude + southWest.longitude) / 2
        )
}

// ============================================================================
// MARK: - Point of Interest
// ============================================================================

@Serializable
data class PointOfInterest(
    val id: String,
    val name: String,
    val icon: String,       // SF Symbol name
    val latitude: Double,
    val longitude: Double
) {
    /**
     * Map iOS SF Symbol names to Material Icons
     */
    val materialIconName: String
        get() = when (icon) {
            "door.left.hand.open" -> "MeetingRoom"
            "cross.case.fill" -> "LocalHospital"
            "info.circle.fill" -> "Info"
            "drop.fill" -> "WaterDrop"
            else -> "Place"
        }
}

// ============================================================================
// MARK: - Utility Functions
// ============================================================================

/**
 * Parse hex color string to Compose Color
 * 
 * @param hex Color string like "#FF6B6B" or "FF6B6B"
 * @return Compose Color, or Gray if parsing fails
 */
private fun parseHexColor(hex: String): Color {
    return try {
        val sanitized = hex.removePrefix("#")
        val colorLong = sanitized.toLong(16)
        Color(
            red = ((colorLong shr 16) and 0xFF) / 255f,
            green = ((colorLong shr 8) and 0xFF) / 255f,
            blue = (colorLong and 0xFF) / 255f
        )
    } catch (e: Exception) {
        Color.Gray
    }
}

// ============================================================================
// MARK: - JSON Loading
// ============================================================================

/**
 * Load festival data from bundled JSON asset
 * 
 * @param context Android context for asset access
 * @param filename JSON filename in assets/festival/ directory
 * @return Parsed FestivalData or null if loading fails
 */
fun loadFestivalData(context: Context, filename: String = "FestivalSchedule.json"): FestivalData? {
    return try {
        val json = Json { 
            ignoreUnknownKeys = true 
            isLenient = true
        }
        val jsonString = context.assets
            .open("festival/$filename")
            .bufferedReader()
            .use { it.readText() }
        json.decodeFromString<FestivalData>(jsonString)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
