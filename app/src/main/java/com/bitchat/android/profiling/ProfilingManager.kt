package com.bitchat.android.profiling

import android.content.Context
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.location.NigeriaLocation
import com.google.gson.Gson
import com.bitchat.android.nostr.NostrEvent
import java.util.UUID

class ProfilingManager(private val context: Context, private val dao: ProfileDao? = null) {
    private val profileDao: ProfileDao by lazy {
        dao ?: AppDatabase.getDatabase(context).profileDao()
    }

    suspend fun scoutPerson(
        name: String,
        age: Int?,
        gender: String?,
        location: NigeriaLocation,
        skills: List<String>,
        contact: String?,
        traits: Map<String, String>,
        scoutPubkey: String
    ): ScoutedProfile {
        val profile = ScoutedProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            age = age,
            gender = gender,
            location = location,
            skills = skills,
            contact = contact,
            traits = traits,
            scoutPubkey = scoutPubkey
        )
        profileDao.insertScoutedProfile(profile)
        return profile
    }

    suspend fun receiveMergeRequest(event: NostrEvent) {
        val profiles = try {
            Gson().fromJson(event.content, Array<ScoutedProfile>::class.java)
        } catch (e: Exception) {
            emptyArray<ScoutedProfile>()
        }

        profiles.forEach { profile ->
            val existing = profileDao.getProfileById(profile.id)
            if (existing == null || existing.version < profile.version) {
                profileDao.insertScoutedProfile(profile)
            }
        }
    }

    suspend fun getAllProfiles(): List<ScoutedProfile> = profileDao.getAllScoutedProfiles()

    fun matchProfiles(
        profiles: List<ScoutedProfile>,
        criteriaSkills: List<String> = emptyList(),
        criteriaTraits: Map<String, String> = emptyMap()
    ): List<ScoutedProfile> {
        return profiles.filter { profile ->
            val matchesSkills = criteriaSkills.isEmpty() || criteriaSkills.any { it in profile.skills }
            val matchesTraits = criteriaTraits.isEmpty() || criteriaTraits.all { (k, v) -> profile.traits[k] == v }
            matchesSkills && matchesTraits
        }
    }
}
