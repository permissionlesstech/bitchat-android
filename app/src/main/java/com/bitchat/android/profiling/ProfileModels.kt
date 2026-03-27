package com.bitchat.android.profiling

import androidx.room.Entity
import androidx.room.PrimaryKey
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import com.bitchat.android.location.NigeriaLocation

@Parcelize
data class UserExtendedProfile(
    val name: String = "",
    val age: Int = 0,
    val bio: String = "",
    val skills: List<String> = emptyList(),
    val interests: List<String> = emptyList(),
    val traits: Map<String, String> = emptyMap() // e.g. "personality" -> "introvert"
) : Parcelable

@Entity(tableName = "scouted_profiles")
@Parcelize
data class ScoutedProfile(
    @PrimaryKey val id: String, // UUID or derived from hash
    val name: String,
    val age: Int?,
    val gender: String?,
    val location: NigeriaLocation,
    val skills: List<String> = emptyList(),
    val contact: String? = null,
    val traits: Map<String, String> = emptyMap(),
    val scoutPubkey: String, // Who created this profile
    val createdAt: Long = System.currentTimeMillis(),
    val version: Int = 1
) : Parcelable

@Entity(tableName = "merged_databases")
data class MergedDatabase(
    @PrimaryKey val id: String,
    val name: String,
    val scope: String, // Admin level scope
    val adminPubkey: String,
    val mergedAt: Long = System.currentTimeMillis()
)
