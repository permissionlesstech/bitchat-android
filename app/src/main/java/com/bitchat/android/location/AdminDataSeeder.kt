package com.bitchat.android.location

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.bitchat.android.onboarding.NigeriaData
import com.bitchat.android.profiling.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AdminDataSeeder {
    private const val TAG = "AdminDataSeeder"

    suspend fun seedIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        val dao = db.adminDao()

        if (dao.getStateCount() > 0) {
            Log.d(TAG, "Database already seeded")
            return@withContext
        }

        Log.d(TAG, "Seeding administrative database from ng.json...")
        val json = context.assets.open("ng.json").bufferedReader().use { it.readText() }
        val data = Gson().fromJson(json, NigeriaData::class.java)

        val states = mutableListOf<StateEntity>()
        val regions = mutableListOf<RegionEntity>()
        val lgas = mutableListOf<LgaEntity>()
        val wards = mutableListOf<WardEntity>()
        val constituencies = mutableListOf<ConstituencyEntity>()

        data.states.forEach { state ->
            states.add(StateEntity(state.name))
            state.regions.forEach { region ->
                val regionId = "${state.name}:${region.name}"
                regions.add(RegionEntity(regionId, state.name, region.name))
                region.lgas.forEach { lga ->
                    val lgaId = "${regionId}:${lga.name}"
                    lgas.add(LgaEntity(lgaId, state.name, region.name, lga.name))
                    lga.wards.forEach { ward ->
                        val wardId = "${lgaId}:${ward.name}"
                        wards.add(WardEntity(wardId, state.name, region.name, lga.name, ward.name))
                        ward.constituencies.forEach { constituency ->
                            val constituencyId = "${wardId}:${constituency}"
                            constituencies.add(ConstituencyEntity(constituencyId, state.name, region.name, lga.name, ward.name, constituency))
                        }
                    }
                }
            }
        }

        dao.insertStates(states)
        dao.insertRegions(regions)
        dao.insertLgas(lgas)
        dao.insertWards(wards)
        dao.insertConstituencies(constituencies)
        Log.d(TAG, "Administrative database seeding complete.")
    }
}
