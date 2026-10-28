package com.bitchat.android.geohash

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource

internal class FusedLocationProvider(
    private val context: Context,
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context),
    private val systemFallback: SystemLocationProvider = SystemLocationProvider(context)
) : LocationProvider {

    companion object {
        private const val TAG = "FusedLocationProvider"
        private const val FRESH_LOCATION_TIMEOUT_MS = 30_000L
    }

    private val handler = Handler(Looper.getMainLooper())

    private val activeCallbacks = mutableMapOf<(Location) -> Unit, UpdateRegistration>()
    private val activeOneShotRequests = mutableSetOf<PendingOneShot>()
    private val activeLastKnownRequests = mutableSetOf<PendingLastKnown>()

    private class PendingOneShot(val deadlineElapsedRealtime: Long) {
        val cancellation = CancellationTokenSource()
        var fallbackStarted = false
        var timeoutRunnable: Runnable? = null
    }

    private class PendingLastKnown {
        var fallbackStarted = false
    }

    private class UpdateRegistration(
        val fusedCallback: LocationCallback,
        val systemCallback: (Location) -> Unit
    ) {
        var fallbackStarted = false
    }

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

        val pending = PendingLastKnown()
        synchronized(activeLastKnownRequests) {
            activeLastKnownRequests += pending
        }

        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    if (location != null && hasLocationPermission()) {
                        completeLastKnown(pending, callback, location)
                    } else {
                        startSystemLastKnownFallback(pending, callback)
                    }
                }
                .addOnFailureListener {
                    Log.e(TAG, "Error getting last-known fused location")
                    startSystemLastKnownFallback(pending, callback)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception getting last-known fused location", e)
            startSystemLastKnownFallback(pending, callback)
        }
    }

    private fun startSystemLastKnownFallback(
        pending: PendingLastKnown,
        callback: (Location?) -> Unit
    ) {
        var deliverNull = false
        var shouldStartFallback = false

        synchronized(activeLastKnownRequests) {
            when {
                !activeLastKnownRequests.contains(pending) -> return
                !hasLocationPermission() -> {
                    activeLastKnownRequests.remove(pending)
                    deliverNull = true
                }
                !pending.fallbackStarted -> {
                    pending.fallbackStarted = true
                    shouldStartFallback = true
                }
            }
        }

        when {
            deliverNull -> callback(null)
            shouldStartFallback -> {
                try {
                    systemFallback.getLastKnownLocation { location ->
                        completeLastKnown(pending, callback, location)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "System last-known location fallback failed", e)
                    completeLastKnown(pending, callback, null)
                }
            }
        }
    }

    private fun completeLastKnown(
        pending: PendingLastKnown,
        callback: (Location?) -> Unit,
        location: Location?
    ) {
        val shouldDeliver = synchronized(activeLastKnownRequests) {
            activeLastKnownRequests.remove(pending)
        }
        if (shouldDeliver) {
            callback(location.takeIf { hasLocationPermission() })
        }
    }

    @SuppressLint("MissingPermission")
    override fun requestFreshLocation(callback: (Location?) -> Unit) {
        if (!hasLocationPermission()) {
            callback(null)
            return
        }

        val pending = PendingOneShot(SystemClock.elapsedRealtime() + FRESH_LOCATION_TIMEOUT_MS)
        val timeout = Runnable { completeOneShot(pending, callback, null) }
        pending.timeoutRunnable = timeout
        synchronized(activeOneShotRequests) {
            activeOneShotRequests += pending
            handler.postDelayed(timeout, maxOf(0L, pending.deadlineElapsedRealtime - SystemClock.elapsedRealtime()))
        }

        try {
            val remainingMs = pending.deadlineElapsedRealtime - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) {
                completeOneShot(pending, callback, null)
                return
            }
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setDurationMillis(remainingMs)
                .build()

            fusedLocationClient.getCurrentLocation(request, pending.cancellation.token)
                .addOnSuccessListener { location ->
                    if (location != null && hasLocationPermission()) {
                        completeOneShot(pending, callback, location)
                    } else {
                        startSystemOneShotFallback(pending, callback)
                    }
                }
                .addOnFailureListener {
                    Log.w(TAG, "Fused fresh location failed; using system fallback")
                    startSystemOneShotFallback(pending, callback)
                }
                .addOnCanceledListener {
                    startSystemOneShotFallback(pending, callback)
                }
        } catch (e: Exception) {
            Log.w(TAG, "Fused fresh location could not start; using system fallback", e)
            startSystemOneShotFallback(pending, callback)
        }
    }

    private fun startSystemOneShotFallback(
        pending: PendingOneShot,
        callback: (Location?) -> Unit
    ) {
        var deliverNull = false
        var fallbackStartFailed = false

        synchronized(activeOneShotRequests) {
            if (!activeOneShotRequests.contains(pending)) {
                return
            }

            if (!hasLocationPermission() || SystemClock.elapsedRealtime() >= pending.deadlineElapsedRealtime) {
                deliverNull = true
            } else if (!pending.fallbackStarted) {
                pending.fallbackStarted = true
                try {
                    systemFallback.requestFreshLocation(pending.deadlineElapsedRealtime) { location ->
                        completeOneShot(pending, callback, location)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "System location fallback could not start", e)
                    fallbackStartFailed = true
                }
            }
        }

        when {
            deliverNull -> completeOneShot(pending, callback, null)
            fallbackStartFailed -> completeOneShot(pending, callback, null)
        }
    }

    private fun completeOneShot(
        pending: PendingOneShot,
        callback: (Location?) -> Unit,
        location: Location?
    ) {
        val shouldDeliver = synchronized(activeOneShotRequests) {
            activeOneShotRequests.remove(pending)
        }
        if (shouldDeliver) {
            pending.timeoutRunnable?.let(handler::removeCallbacks)
            pending.cancellation.cancel()
            callback(location.takeIf {
                SystemClock.elapsedRealtime() < pending.deadlineElapsedRealtime && hasLocationPermission()
            })
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

        lateinit var registration: UpdateRegistration
        val fusedCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (isCurrentRegistration(callback, registration) && hasLocationPermission()) {
                    result.lastLocation?.let(callback)
                }
            }
        }
        val systemCallback: (Location) -> Unit = { location ->
            if (isCurrentRegistration(callback, registration) && hasLocationPermission()) {
                callback(location)
            }
        }
        registration = UpdateRegistration(
            fusedCallback = fusedCallback,
            systemCallback = systemCallback
        )

        try {
            val request = LocationRequest.Builder(intervalMs)
                .setMinUpdateDistanceMeters(minDistanceMeters)
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .build()

            synchronized(activeCallbacks) {
                activeCallbacks[callback] = registration
                fusedLocationClient.requestLocationUpdates(
                    request,
                    fusedCallback,
                    Looper.getMainLooper()
                )
                    .addOnSuccessListener {
                        Log.d(TAG, "Registered fused updates")
                    }
                    .addOnFailureListener {
                        Log.w(TAG, "Fused updates unavailable; using system location provider")
                        startSystemUpdatesFallback(
                            callback = callback,
                            registration = registration,
                            intervalMs = intervalMs,
                            minDistanceMeters = minDistanceMeters
                        )
                    }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to register fused updates; using system location provider", e)
            startSystemUpdatesFallback(
                callback = callback,
                registration = registration,
                intervalMs = intervalMs,
                minDistanceMeters = minDistanceMeters
            )
        }
    }

    private fun startSystemUpdatesFallback(
        callback: (Location) -> Unit,
        registration: UpdateRegistration,
        intervalMs: Long,
        minDistanceMeters: Float
    ) {
        synchronized(activeCallbacks) {
            if (activeCallbacks[callback] !== registration || registration.fallbackStarted) {
                return
            }
            if (!hasLocationPermission()) {
                activeCallbacks.remove(callback)
                return
            }

            registration.fallbackStarted = true
            try {
                systemFallback.requestLocationUpdates(
                    intervalMs = intervalMs,
                    minDistanceMeters = minDistanceMeters,
                    callback = registration.systemCallback
                )
            } catch (e: Exception) {
                Log.w(TAG, "Unable to register system location fallback", e)
            }
        }
    }

    private fun isCurrentRegistration(
        callback: (Location) -> Unit,
        registration: UpdateRegistration
    ): Boolean = synchronized(activeCallbacks) {
        activeCallbacks[callback] === registration
    }

    override fun removeLocationUpdates(callback: (Location) -> Unit) {
        val registration = synchronized(activeCallbacks) {
            activeCallbacks.remove(callback)?.also {
                if (it.fallbackStarted) {
                    systemFallback.removeLocationUpdates(it.systemCallback)
                }
            }
        }

        if (registration != null) {
            runCatching { fusedLocationClient.removeLocationUpdates(registration.fusedCallback) }
                .onFailure { Log.w(TAG, "Unable to remove fused updates", it) }
        }
    }

    override fun cancel() {
        val registrations = synchronized(activeCallbacks) {
            activeCallbacks.values.toList().also { activeCallbacks.clear() }
        }
        registrations.forEach { registration ->
            runCatching { fusedLocationClient.removeLocationUpdates(registration.fusedCallback) }
                .onFailure { Log.w(TAG, "Unable to remove fused updates", it) }
        }

        val oneShotRequests = synchronized(activeOneShotRequests) {
            activeOneShotRequests.toList().also { activeOneShotRequests.clear() }
        }
        oneShotRequests.forEach { pending ->
            pending.timeoutRunnable?.let(handler::removeCallbacks)
            pending.cancellation.cancel()
        }
        synchronized(activeLastKnownRequests) {
            activeLastKnownRequests.clear()
        }
        // This instance owns the fallback provider, so it is safe to cancel all of its work here.
        systemFallback.cancel()
        Log.d(TAG, "Cancelled all fused location requests")
    }
}
