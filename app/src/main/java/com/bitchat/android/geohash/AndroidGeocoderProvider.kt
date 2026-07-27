package com.bitchat.android.geohash

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AndroidGeocoderProvider(context: Context) : GeocoderProvider {
    private val geocoder = Geocoder(context, Locale.getDefault())
    private val TAG = "AndroidGeocoderProvider"

    override suspend fun getFromLocation(
        latitude: Double,
        longitude: Double,
        maxResults: Int,
        liveLocationToken: Long?
    ): List<Address> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                try {
                    val startRequest = {
                        geocoder.getFromLocation(
                            latitude,
                            longitude,
                            maxResults,
                            object : Geocoder.GeocodeListener {
                                override fun onGeocode(addresses: MutableList<Address>) {
                                    if (cont.isActive) cont.resume(addresses)
                                }

                                override fun onError(errorMessage: String?) {
                                    if (cont.isActive) {
                                        Log.e(TAG, "Geocode error")
                                        cont.resume(emptyList())
                                    }
                                }
                            }
                        )
                    }
                    val started = if (liveLocationToken == null) {
                        startRequest()
                        true
                    } else {
                        LiveLocationPrivacyGate.runIfAllowed(
                            liveLocationToken,
                            startRequest
                        )
                    }
                    if (!started && cont.isActive) cont.resume(emptyList())
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            try {
                var addresses: List<Address> = emptyList()
                val request = {
                    addresses = geocoder.getFromLocation(
                        latitude,
                        longitude,
                        maxResults
                    ) ?: emptyList()
                }
                if (liveLocationToken == null) {
                    request()
                } else {
                    LiveLocationPrivacyGate.runIfAllowed(liveLocationToken, request)
                }
                addresses
            } catch (e: Exception) {
                Log.e(TAG, "Geocode failed")
                emptyList()
            }
        }
    }
}
