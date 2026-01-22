package com.bitchat.android.features.festival

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Root festival data container
 * Parses the same FestivalSchedule.json format as iOS
 */
@Serializable
data class FestivalData(
    val festival: FestivalInfo,
    val tabs: List<FestivalTab>? = null,
    val stages: List<Stage>,
    val sets: List<ScheduledSet>,
    val customChannels: List<CustomChannel>? = null,
    val mapBounds: MapBounds? = null,
    val pointsOfInterest: List<PointOfInterest>? = null
) {
    /** Get configured tabs, or default tabs if none specified */
    val configuredTabs: List<FestivalTab>
        get() = tabs ?: FestivalTab.defaultTabs
}

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
    val timezoneId: ZoneId
        get() = ZoneId.of(timezone ?: "America/Los_Angeles")
}

@Serializable
data class FestivalDates(
    val start: String,
    val end: String
)

/**
 * Tab configuration from JSON - allows festivals to customize which tabs appear
 */
@Serializable
data class FestivalTab(
    val id: String,
    val name: String,
    val icon: String,
    val type: TabType
) {
    @Serializable
    enum class TabType {
        @SerialName("schedule") SCHEDULE,
        @SerialName("channels") CHANNELS,
        @SerialName("chat") CHAT,
        @SerialName("map") MAP,
        @SerialName("info") INFO,
        @SerialName("friends") FRIENDS,
        @SerialName("custom") CUSTOM
    }
    
    companion object {
        /** Default tabs if none specified in JSON */
        val defaultTabs = listOf(
            FestivalTab("schedule", "Schedule", "calendar", TabType.SCHEDULE),
            FestivalTab("channels", "Channels", "antenna.radiowaves.left.and.right", TabType.CHANNELS),
            FestivalTab("chat", "Mesh Chat", "bubble.left.and.bubble.right", TabType.CHAT),
            FestivalTab("info", "Info", "info.circle", TabType.INFO)
        )
    }
}

@Serializable
data class Stage(
    val id: String,
    val name: String,
    val description: String,
    val color: String,
    val geohash: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
) {
    /** Parse hex color to Compose Color */
    val composeColor: Color
        get() = parseHexColor(color)
    
    /** Channel name for this stage (e.g., "#lands-end") */
    val channelName: String
        get() = "#$id"
}

@Serializable
data class CustomChannel(
    val id: String,
    val name: String,
    val description: String,
    val icon: String,
    val color: String
) {
    val composeColor: Color
        get() = parseHexColor(color)
}

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
}

@Serializable
data class PointOfInterest(
    val id: String,
    val name: String,
    val icon: String,
    val latitude: Double,
    val longitude: Double
)

@Serializable
data class ScheduledSet(
    val id: String,
    val artist: String,
    val stage: String,
    val day: String,
    val start: String,
    val end: String,
    val genre: String? = null
) {
    /** Parse start time as LocalDateTime */
    fun startDateTime(timezone: ZoneId = ZoneId.of("America/Los_Angeles")): LocalDateTime? {
        return parseDateTime(day, start)
    }
    
    /** Parse end time as LocalDateTime */
    fun endDateTime(timezone: ZoneId = ZoneId.of("America/Los_Angeles")): LocalDateTime? {
        return parseDateTime(day, end)
    }
    
    /** Check if this set is currently playing */
    fun isNowPlaying(
        currentTime: LocalDateTime = LocalDateTime.now(),
        timezone: ZoneId = ZoneId.of("America/Los_Angeles")
    ): Boolean {
        val start = startDateTime(timezone) ?: return false
        val end = endDateTime(timezone) ?: return false
        return currentTime >= start && currentTime < end
    }
    
    /** Check if this set is coming up within the next N minutes */
    fun isUpcoming(
        withinMinutes: Int = 30,
        currentTime: LocalDateTime = LocalDateTime.now(),
        timezone: ZoneId = ZoneId.of("America/Los_Angeles")
    ): Boolean {
        val start = startDateTime(timezone) ?: return false
        val threshold = currentTime.plusMinutes(withinMinutes.toLong())
        return start > currentTime && start <= threshold
    }
    
    /** Formatted time range string (e.g., "8:30 PM - 10:00 PM") */
    val timeRangeString: String
        get() {
            val startDt = startDateTime() ?: return "$start - $end"
            val endDt = endDateTime() ?: return "$start - $end"
            val formatter = DateTimeFormatter.ofPattern("h:mm a")
            return "${startDt.format(formatter)} - ${endDt.format(formatter)}"
        }
    
    private fun parseDateTime(day: String, time: String): LocalDateTime? {
        return try {
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            LocalDateTime.parse("$day $time", formatter)
        } catch (e: Exception) {
            null
        }
    }
}

// MARK: - Utilities

/** Parse hex color string to Compose Color */
private fun parseHexColor(hex: String): Color {
    val cleaned = hex.removePrefix("#")
    return try {
        val rgb = cleaned.toLong(16)
        Color(
            red = ((rgb shr 16) and 0xFF) / 255f,
            green = ((rgb shr 8) and 0xFF) / 255f,
            blue = (rgb and 0xFF) / 255f
        )
    } catch (e: Exception) {
        Color.Gray
    }
}

/** Load festival data from assets */
fun loadFestivalData(context: Context): FestivalData? {
    return try {
        val json = Json { ignoreUnknownKeys = true }
        val inputStream = context.assets.open("festival/FestivalSchedule.json")
        val jsonString = inputStream.bufferedReader().use { it.readText() }
        json.decodeFromString<FestivalData>(jsonString)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
