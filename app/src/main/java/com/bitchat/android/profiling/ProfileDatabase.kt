package com.bitchat.android.profiling

import android.content.Context
import androidx.room.*
import androidx.annotation.VisibleForTesting
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.bitchat.android.location.*
import com.bitchat.android.location.NigeriaLocation

class Converters {
    @TypeConverter
    fun fromStringList(value: List<String>): String = Gson().toJson(value)
    @TypeConverter
    fun toStringList(value: String): List<String> = Gson().fromJson(value, object : TypeToken<List<String>>() {}.type)

    @TypeConverter
    fun fromStringMap(value: Map<String, String>): String = Gson().toJson(value)
    @TypeConverter
    fun toStringMap(value: String): Map<String, String> = Gson().fromJson(value, object : TypeToken<Map<String, String>>() {}.type)

    @TypeConverter
    fun fromLocation(value: NigeriaLocation): String = Gson().toJson(value)
    @TypeConverter
    fun toLocation(value: String): NigeriaLocation = Gson().fromJson(value, NigeriaLocation::class.java)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM scouted_profiles")
    suspend fun getAllScoutedProfiles(): List<ScoutedProfile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScoutedProfile(profile: ScoutedProfile)

    @Query("SELECT * FROM scouted_profiles WHERE id = :id")
    suspend fun getProfileById(id: String): ScoutedProfile?

    @Query("DELETE FROM scouted_profiles WHERE id = :id")
    suspend fun deleteProfile(id: String)
}

@Database(entities = [ScoutedProfile::class, MergedDatabase::class, StateEntity::class, RegionEntity::class, LgaEntity::class, WardEntity::class, ConstituencyEntity::class, WardFtsEntity::class, LocationHistoryEntity::class], version = 3)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun adminDao(): AdminDao
    abstract fun locationDao(): LocationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bitchat_nigeria_db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }

        @VisibleForTesting
        fun getInMemoryDatabase(context: Context): AppDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                AppDatabase::class.java
            ).allowMainThreadQueries().fallbackToDestructiveMigration().build()
        }
    }
}
