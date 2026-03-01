package com.bitchat.android.location

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface LocationDao {
    @Insert
    suspend fun insertLocation(location: LocationHistoryEntity)

    @Query("SELECT * FROM location_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentLocations(limit: Int): List<LocationHistoryEntity>

    @Query("DELETE FROM location_history WHERE timestamp < :timestamp")
    suspend fun pruneLocations(timestamp: Long)
}
