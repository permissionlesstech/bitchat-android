package com.bitchat.android.location

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

@Entity(tableName = "states")
data class StateEntity(
    @PrimaryKey val name: String
)

@Entity(tableName = "regions")
data class RegionEntity(
    @PrimaryKey val id: String, // state:name
    val stateName: String,
    val name: String
)

@Entity(tableName = "lgas")
data class LgaEntity(
    @PrimaryKey val id: String, // state:region:name
    val stateName: String,
    val regionName: String,
    val name: String
)

@Entity(tableName = "wards")
data class WardEntity(
    @PrimaryKey val id: String, // state:region:lga:name
    val stateName: String,
    val regionName: String,
    val lgaName: String,
    val name: String
)

@Entity(tableName = "constituencies")
data class ConstituencyEntity(
    @PrimaryKey val id: String, // state:region:lga:ward:name
    val stateName: String,
    val regionName: String,
    val lgaName: String,
    val wardName: String,
    val name: String
)

@Fts4(contentEntity = WardEntity::class)
@Entity(tableName = "wards_fts")
data class WardFtsEntity(
    val name: String,
    val lgaName: String,
    val stateName: String
)
