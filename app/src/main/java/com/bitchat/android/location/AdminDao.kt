package com.bitchat.android.location

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AdminDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStates(states: List<StateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRegions(regions: List<RegionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLgas(lgas: List<LgaEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWards(wards: List<WardEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConstituencies(constituencies: List<ConstituencyEntity>)

    @Query("SELECT * FROM states ORDER BY name ASC")
    suspend fun getAllStates(): List<StateEntity>

    @Query("SELECT * FROM regions WHERE stateName = :stateName ORDER BY name ASC")
    suspend fun getRegionsForState(stateName: String): List<RegionEntity>

    @Query("SELECT * FROM lgas WHERE stateName = :stateName AND regionName = :regionName ORDER BY name ASC")
    suspend fun getLgasForRegion(stateName: String, regionName: String): List<LgaEntity>

    @Query("SELECT * FROM wards WHERE stateName = :stateName AND lgaName = :lgaName ORDER BY name ASC")
    suspend fun getWardsForLga(stateName: String, lgaName: String): List<WardEntity>

    @Query("SELECT * FROM constituencies WHERE stateName = :stateName AND wardName = :wardName ORDER BY name ASC")
    suspend fun getConstituenciesForWard(stateName: String, wardName: String): List<ConstituencyEntity>

    @Query("SELECT * FROM wards JOIN wards_fts ON wards.name = wards_fts.name WHERE wards_fts.name MATCH :query")
    suspend fun searchWards(query: String): List<WardEntity>

    @Query("SELECT COUNT(*) FROM states")
    suspend fun getStateCount(): Int
}
