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

    override suspend fun getFromLocation(latitude: Double, longitude: Double, maxResults: Int): List<Address> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                try {
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
                                    Log.e(TAG, "Geocode error: $errorMessage")
                                    cont.resume(emptyList())
                                }
                            }
                        }
                    )
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            try {
                geocoder.getFromLocation(latitude, longitude, maxResults) ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Geocode failed", e)
                emptyList()
            }
        }
    }
}
