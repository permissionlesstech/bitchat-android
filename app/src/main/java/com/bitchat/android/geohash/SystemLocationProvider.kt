package com.bitchat.android.geohash

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat

internal class SystemLocationProvider(private val context: Context) : LocationProvider {

    companion object {
        private const val TAG = "SystemLocationProvider"
        private const val FRESH_LOCATION_TIMEOUT_MS = 30_000L
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // Map to keep track of listeners to unregister them later.
    private val activeListeners = mutableMapOf<(Location) -> Unit, LocationListener>()
    private val activeOneShotRequests = mutableSetOf<PendingOneShot>()

    private class PendingOneShot(
        val callback: (Location?) -> Unit,
        val providers: List<String>,
        val deadlineElapsedRealtime: Long
    ) {
        var nextProviderIndex = 0
        var cancellationSignal: CancellationSignal? = null
        var listener: LocationListener? = null
        var timeoutRunnable: Runnable? = null
        var completed = false
    }

    private data class ProviderAttempt(
        val provider: String,
        val timeoutMs: Long
    )

    private data class OneShotResources(
        val cancellationSignal: CancellationSignal?,
        val listener: LocationListener?,
        val timeoutRunnable: Runnable?
    )

    private fun hasLocationPermission(): Boolean {
        return LiveLocationPrivacyGate.captureToken() != null &&
            (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            )
    }

    @SuppressLint("MissingPermission")
    override fun getLastKnownLocation(callback: (Location?) -> Unit) {
        if (!hasLocationPermission()) {
            callback(null)
            return
        }

        try {
            var bestLocation: Location? = null
            val providers = locationManager.getProviders(true)
            for (provider in providers) {
                val location = locationManager.getLastKnownLocation(provider)
                if (location != null) {
                    if (bestLocation == null || location.time > bestLocation.time) {
                        bestLocation = location
                    }
                }
            }
            callback(bestLocation.takeIf { hasLocationPermission() })
        } catch (e: Exception) {
            Log.e(TAG, "Error getting last-known location")
            callback(null)
        }
    }

    @SuppressLint("MissingPermission")
    override fun requestFreshLocation(callback: (Location?) -> Unit) {
        if (!hasLocationPermission()) {
            callback(null)
            return
        }

        val request = PendingOneShot(
            callback = callback,
            providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER
            ),
            deadlineElapsedRealtime = SystemClock.elapsedRealtime() + FRESH_LOCATION_TIMEOUT_MS
        )

        synchronized(activeOneShotRequests) {
            activeOneShotRequests += request
        }
        startNextOneShotProvider(request)
    }

    private fun startNextOneShotProvider(request: PendingOneShot) {
        var shouldFinish = false
        val attempt = synchronized(activeOneShotRequests) {
            if (!isOneShotActiveLocked(request)) {
                null
            } else if (!hasLocationPermission()) {
                shouldFinish = true
                null
            } else {
                val remainingMs = request.deadlineElapsedRealtime - SystemClock.elapsedRealtime()
                if (remainingMs <= 0L) {
                    shouldFinish = true
                    null
                } else {
                    var nextAttempt: ProviderAttempt? = null
                    while (request.nextProviderIndex < request.providers.size && nextAttempt == null) {
                        val provider = request.providers[request.nextProviderIndex++]
                        if (isProviderEnabled(provider)) {
                            val remainingEnabledProviders = 1 + request.providers
                                .drop(request.nextProviderIndex)
                                .count(::isProviderEnabled)
                            nextAttempt = ProviderAttempt(
                                provider = provider,
                                timeoutMs = maxOf(1L, remainingMs / remainingEnabledProviders)
                            )
                        }
                    }
                    if (nextAttempt == null) {
                        shouldFinish = true
                    }
                    nextAttempt
                }
            }
        }

        when {
            attempt != null -> requestOneShotFromProvider(request, attempt)
            shouldFinish -> finishOneShot(request, null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestOneShotFromProvider(
        request: PendingOneShot,
        attempt: ProviderAttempt
    ) {
        Log.d(TAG, "Requesting fresh location from ${attempt.provider}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestCurrentLocation(request, attempt)
        } else {
            requestSingleLocationUpdate(request, attempt)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestCurrentLocation(
        request: PendingOneShot,
        attempt: ProviderAttempt
    ) {
        val cancellationSignal = CancellationSignal()
        val timeout = Runnable {
            val resources = detachCurrentProvider(
                request = request,
                expectedCancellationSignal = cancellationSignal
            ) ?: return@Runnable
            Log.w(TAG, "Location request timed out for ${attempt.provider}")
            releaseOneShotResources(resources)
            startNextOneShotProvider(request)
        }

        if (!attachCurrentProvider(request, cancellationSignal, null, timeout)) return
        handler.postDelayed(timeout, attempt.timeoutMs)

        try {
            locationManager.getCurrentLocation(
                attempt.provider,
                cancellationSignal,
                context.mainExecutor
            ) { location ->
                val resources = detachCurrentProvider(
                    request = request,
                    expectedCancellationSignal = cancellationSignal
                ) ?: return@getCurrentLocation
                releaseOneShotResources(resources)

                if (location != null && hasLocationPermission()) {
                    finishOneShot(request, location)
                } else {
                    startNextOneShotProvider(request)
                }
            }
        } catch (e: Exception) {
            val resources = detachCurrentProvider(
                request = request,
                expectedCancellationSignal = cancellationSignal
            )
            if (resources != null) {
                Log.w(TAG, "Unable to request ${attempt.provider} location", e)
                releaseOneShotResources(resources)
                startNextOneShotProvider(request)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestSingleLocationUpdate(
        request: PendingOneShot,
        attempt: ProviderAttempt
    ) {
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val resources = detachCurrentProvider(
                    request = request,
                    expectedListener = this
                ) ?: return
                releaseOneShotResources(resources)

                if (hasLocationPermission()) {
                    finishOneShot(request, location)
                } else {
                    startNextOneShotProvider(request)
                }
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        val timeout = Runnable {
            val resources = detachCurrentProvider(
                request = request,
                expectedListener = listener
            ) ?: return@Runnable
            Log.w(TAG, "Location request timed out for ${attempt.provider}")
            releaseOneShotResources(resources)
            startNextOneShotProvider(request)
        }

        if (!attachCurrentProvider(request, null, listener, timeout)) return
        handler.postDelayed(timeout, attempt.timeoutMs)

        try {
            locationManager.requestSingleUpdate(attempt.provider, listener, null)
            if (!isCurrentProviderRequest(request, expectedListener = listener)) {
                runCatching { locationManager.removeUpdates(listener) }
            }
        } catch (e: Exception) {
            val resources = detachCurrentProvider(
                request = request,
                expectedListener = listener
            )
            if (resources != null) {
                Log.w(TAG, "Unable to request ${attempt.provider} location", e)
                releaseOneShotResources(resources)
                startNextOneShotProvider(request)
            }
        }
    }

    private fun isProviderEnabled(provider: String): Boolean {
        return try {
            locationManager.isProviderEnabled(provider)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to inspect $provider provider", e)
            false
        }
    }

    private fun attachCurrentProvider(
        request: PendingOneShot,
        cancellationSignal: CancellationSignal?,
        listener: LocationListener?,
        timeoutRunnable: Runnable
    ): Boolean = synchronized(activeOneShotRequests) {
        if (!isOneShotActiveLocked(request) || !hasLocationPermission()) {
            false
        } else {
            request.cancellationSignal = cancellationSignal
            request.listener = listener
            request.timeoutRunnable = timeoutRunnable
            true
        }
    }

    private fun isCurrentProviderRequest(
        request: PendingOneShot,
        expectedCancellationSignal: CancellationSignal? = null,
        expectedListener: LocationListener? = null
    ): Boolean = synchronized(activeOneShotRequests) {
        if (!isOneShotActiveLocked(request)) {
            false
        } else {
            (expectedCancellationSignal == null || request.cancellationSignal === expectedCancellationSignal) &&
                (expectedListener == null || request.listener === expectedListener)
        }
    }

    private fun detachCurrentProvider(
        request: PendingOneShot,
        expectedCancellationSignal: CancellationSignal? = null,
        expectedListener: LocationListener? = null
    ): OneShotResources? = synchronized(activeOneShotRequests) {
        if (!isCurrentProviderRequestLocked(request, expectedCancellationSignal, expectedListener)) {
            null
        } else {
            OneShotResources(
                cancellationSignal = request.cancellationSignal,
                listener = request.listener,
                timeoutRunnable = request.timeoutRunnable
            ).also {
                request.cancellationSignal = null
                request.listener = null
                request.timeoutRunnable = null
            }
        }
    }

    private fun isOneShotActiveLocked(request: PendingOneShot): Boolean =
        !request.completed && activeOneShotRequests.contains(request)

    private fun isCurrentProviderRequestLocked(
        request: PendingOneShot,
        expectedCancellationSignal: CancellationSignal?,
        expectedListener: LocationListener?
    ): Boolean =
        isOneShotActiveLocked(request) &&
            (expectedCancellationSignal == null || request.cancellationSignal === expectedCancellationSignal) &&
            (expectedListener == null || request.listener === expectedListener)

    private fun finishOneShot(request: PendingOneShot, location: Location?) {
        val resources = synchronized(activeOneShotRequests) {
            if (!isOneShotActiveLocked(request)) {
                null
            } else {
                request.completed = true
                activeOneShotRequests.remove(request)
                OneShotResources(
                    cancellationSignal = request.cancellationSignal,
                    listener = request.listener,
                    timeoutRunnable = request.timeoutRunnable
                ).also {
                    request.cancellationSignal = null
                    request.listener = null
                    request.timeoutRunnable = null
                }
            }
        } ?: return

        releaseOneShotResources(resources)
        request.callback(location.takeIf { hasLocationPermission() })
    }

    private fun releaseOneShotResources(resources: OneShotResources) {
        resources.timeoutRunnable?.let(handler::removeCallbacks)
        resources.cancellationSignal?.cancel()
        resources.listener?.let { listener ->
            runCatching { locationManager.removeUpdates(listener) }
                .onFailure { Log.w(TAG, "Unable to remove one-shot location listener", it) }
        }
    }

    @SuppressLint("MissingPermission")
    override fun requestLocationUpdates(
        intervalMs: Long,
        minDistanceMeters: Float,
        callback: (Location) -> Unit
    ) {
        if (!hasLocationPermission()) return

        removeLocationUpdates(callback)

        lateinit var listener: LocationListener
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val isCurrentListener = synchronized(activeListeners) {
                    activeListeners[callback] === listener
                }
                if (isCurrentListener && hasLocationPermission()) callback(location)
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }

        var registered = false
        var shouldCleanUp = false
        synchronized(activeListeners) {
            activeListeners[callback] = listener
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (!isProviderEnabled(provider)) continue
                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        intervalMs,
                        minDistanceMeters,
                        listener
                    )
                    registered = true
                    Log.d(TAG, "Registered updates for $provider")
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to register updates for $provider", e)
                }
            }

            shouldCleanUp = !registered || activeListeners[callback] !== listener
            if (shouldCleanUp && activeListeners[callback] === listener) {
                activeListeners.remove(callback)
            }
        }

        if (shouldCleanUp) {
            runCatching { locationManager.removeUpdates(listener) }
            Log.w(TAG, "No system providers accepted continuous location updates")
        }
    }

    override fun removeLocationUpdates(callback: (Location) -> Unit) {
        val listener = synchronized(activeListeners) {
            activeListeners.remove(callback)
        }

        if (listener != null) {
            runCatching { locationManager.removeUpdates(listener) }
                .onFailure { Log.w(TAG, "Unable to remove location updates", it) }
        }
    }

    override fun cancel() {
        val listeners = synchronized(activeListeners) {
            activeListeners.values.toList().also { activeListeners.clear() }
        }
        listeners.forEach { listener ->
            runCatching { locationManager.removeUpdates(listener) }
                .onFailure { Log.w(TAG, "Unable to remove location updates", it) }
        }

        val oneShotResources = synchronized(activeOneShotRequests) {
            activeOneShotRequests.map { request ->
                request.completed = true
                OneShotResources(
                    cancellationSignal = request.cancellationSignal,
                    listener = request.listener,
                    timeoutRunnable = request.timeoutRunnable
                )
            }.also {
                activeOneShotRequests.clear()
            }
        }
        oneShotResources.forEach(::releaseOneShotResources)
        Log.d(TAG, "Cancelled all system location requests")
    }
}

