package com.bitchat.android.geohash

import android.location.Address
import android.util.Log
import com.bitchat.android.net.OkHttpProvider
import com.google.gson.Gson
import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.util.Locale
import kotlin.coroutines.resume

class OpenStreetMapGeocoderProvider : GeocoderProvider {
    private val TAG = "OSMGeocoderProvider"
    private val gson = Gson()
    private val userAgent = "Bitchat-Android/1.0"

    override suspend fun getFromLocation(
        latitude: Double,
        longitude: Double,
        maxResults: Int,
        liveLocationToken: Long?
    ): List<Address> {
        return suspendCancellableCoroutine { continuation ->
            val lang = Locale.getDefault().toLanguageTag()
            val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$latitude&lon=$longitude&zoom=18&addressdetails=1&accept-language=$lang"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build()
            val call = OkHttpProvider.httpClient().newCall(request)

            continuation.invokeOnCancellation { call.cancel() }
            val enqueueRequest = {
                call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        Log.w(TAG, "OSM geocoding request failed")
                        continuation.resume(emptyList())
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val addresses = response.use {
                        if (!it.isSuccessful) {
                            Log.w(TAG, "OSM geocoding request returned ${it.code}")
                            return@use emptyList()
                        }

                        val body = it.body?.string()
                        if (body.isNullOrEmpty()) return@use emptyList()

                        runCatching {
                            val osmResponse = gson.fromJson(body, OsmResponse::class.java)
                            if (osmResponse?.address == null) emptyList()
                            else listOf(mapToAddress(osmResponse, latitude, longitude))
                        }.getOrElse {
                            Log.w(TAG, "OSM geocoding response could not be parsed")
                            emptyList()
                        }
                    }
                    if (continuation.isActive) continuation.resume(addresses)
                }
                })
            }
            val started = if (liveLocationToken == null) {
                enqueueRequest()
                true
            } else {
                LiveLocationPrivacyGate.runIfAllowed(
                    liveLocationToken,
                    enqueueRequest
                )
            }
            if (!started && continuation.isActive) {
                continuation.resume(emptyList())
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
