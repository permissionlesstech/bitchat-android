package com.bitchat.android.location

import android.content.Context
import com.google.gson.Gson
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.profiling.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

class LocationHierarchyManager(private val context: Context) {
    private val identityManager = SecureIdentityStateManager(context)
    private val db = AppDatabase.getDatabase(context)
    private val dao = db.locationDao()
    private val gson = Gson()

    fun getCurrentAdminLocation(): NigeriaLocation? {
        val json = identityManager.getSecureValue("user_location") ?: return null
        return try {
            gson.fromJson(json, NigeriaLocation::class.java)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun recordMovement(latitude: Double, longitude: Double) = withContext(Dispatchers.IO) {
        val lastLocations = dao.getRecentLocations(2)

        // Simple spatial compression: Don't record if too close to last point
        if (lastLocations.isNotEmpty()) {
            val last = lastLocations[0]
            val dist = abs(last.latitude - latitude) + abs(last.longitude - longitude)
            if (dist < 0.0001) return@withContext // Approx 10 meters
        }

        val entry = LocationHistoryEntity(
            timestamp = System.currentTimeMillis(),
            latitude = latitude,
            longitude = longitude,
            adminScope = getCurrentAdminLocation()?.toScopeString()
        )
        dao.insertLocation(entry)

        // Prune old history (older than 30 days)
        dao.pruneLocations(System.currentTimeMillis() - 30 * 24 * 60 * 60 * 1000L)
    }
}
