package com.bitchat.android.geohash

import android.location.Address
import android.util.Log
import com.bitchat.android.net.OkHttpProvider
import com.google.gson.Gson
import okhttp3.Request
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OpenStreetMapGeocoderProvider : GeocoderProvider {
    private val TAG = "OSMGeocoderProvider"
    private val gson = Gson()
    private val userAgent = "Bitchat-Android/1.0"

    override suspend fun getFromLocation(latitude: Double, longitude: Double, maxResults: Int): List<Address> {
        return withContext(Dispatchers.IO) {
            val lang = Locale.getDefault().toLanguageTag()
            // Using format=jsonv2 for structured address breakdown
            val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$latitude&lon=$longitude&zoom=18&addressdetails=1&accept-language=$lang"

            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .build()

                val response = OkHttpProvider.httpClient().newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "OSM Request failed: ${response.code}")
                    response.close()
                    return@withContext emptyList<Address>()
                }

                val body = response.body?.string()
                response.close()

                if (body.isNullOrEmpty()) return@withContext emptyList<Address>()

                try {
                    val osmResponse = gson.fromJson(body, OsmResponse::class.java)
                    if (osmResponse?.address == null) return@withContext emptyList<Address>()
                    
                    val address = mapToAddress(osmResponse, latitude, longitude)
                    listOf(address)
                } catch (e: Exception) {
                     Log.e(TAG, "OSM Parse failed: ${e.message}")
                     emptyList<Address>()
                }
            } catch (e: Exception) {
                Log.e(TAG, "OSM Geocoding failed", e)
                emptyList<Address>()
            }
        }
    }

    private fun mapToAddress(res: OsmResponse, lat: Double, lon: Double): Address {
        val address = Address(Locale.getDefault())
        address.latitude = lat
        address.longitude = lon
        
        val a = res.address ?: return address

        address.countryName = a.country
        address.adminArea = a.state
        address.subAdminArea = a.county
        
        // City logic similar to Google's mapping
        address.locality = a.city ?: a.town ?: a.village ?: a.hamlet
        
        // Neighborhood logic
        address.subLocality = a.suburb ?: a.neighbourhood ?: a.residential ?: a.quarter
        
        address.postalCode = a.postcode
        address.thoroughfare = a.road
        
        // Feature name
        address.featureName = res.name

        return address
    }

    // Data classes for JSON parsing
    private data class OsmResponse(
        val name: String?,
        val display_name: String?,
        val address: OsmAddress?
    )

    private data class OsmAddress(
        val country: String?,
        val state: String?,
        val county: String?,
        val city: String?,
        val town: String?,
        val village: String?,
        val hamlet: String?,
        val suburb: String?,
        val neighbourhood: String?,
        val residential: String?,
        val quarter: String?,
        val postcode: String?,
        val road: String?
    )
}
