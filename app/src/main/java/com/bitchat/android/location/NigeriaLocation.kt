package com.bitchat.android.location

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class NigeriaLocation(
    val state: String,
    val region: String,
    val lga: String,
    val ward: String,
    val constituency: String
) : Parcelable {
    fun toScopeString(): String {
        return "ng:$state:$region:$lga:$ward:$constituency"
    }

    companion object {
        fun fromScopeString(scope: String): NigeriaLocation? {
            val parts = scope.split(":")
            if (parts.size != 6 || parts[0] != "ng") return null
            return NigeriaLocation(parts[1], parts[2], parts[3], parts[4], parts[5])
        }
    }
}

@Parcelize
data class LocationHistoryEntry(
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val adminLocation: NigeriaLocation? = null
) : Parcelable
