package com.bitchat.android.ui

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import androidx.appcompat.app.AppCompatActivity
import com.bitchat.android.utils.DeviceUtils

/**
 * Base activity for the app's own screens: device-appropriate orientation, and the display's
 * highest refresh rate.
 *
 * Tablets can rotate to landscape, phones are locked to portrait.
 */
abstract class OrientationAwareActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setOrientationBasedOnDeviceType()
        requestHighestRefreshRate()
    }

    /**
     * Ask the compositor for the fastest mode this panel offers.
     *
     * Only modes at the resolution already in use are considered - a panel typically lists its
     * high refresh rates at more than one resolution, and picking purely by refresh rate would
     * silently drop the screen to a lower one.
     *
     * This is a request, not a guarantee: the system still overrides it for battery saver, for a
     * user-set refresh-rate preference, and on panels that gate high rates by content.
     */
    private fun requestHighestRefreshRate() {
        val display: Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }
        val current = display?.mode ?: return

        val sameResolution = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth &&
                it.physicalHeight == current.physicalHeight
        }
        val fastest = sameResolution.maxByOrNull { it.refreshRate } ?: return

        // No early return when the reported mode already looks fastest. Display.mode can name the
        // panel's default mode while the compositor is actually driving it slower, and skipping
        // the request in that case is exactly the situation this method exists to fix.
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = fastest.modeId
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Second, weaker signal. Some vendors honour the float hint when they ignore a
                // specific mode id.
                preferredRefreshRate = fastest.refreshRate
            }
        }

        // Logged in full because the request is frequently refused and the mode list is the only
        // way to tell why: a panel whose high rates live at another resolution, a vendor
        // refresh-rate setting, or a device that simply has one mode.
        Log.i(
            TAG,
            "Requested ${fastest.refreshRate} Hz (mode ${fastest.modeId}); " +
                "current ${current.refreshRate} Hz at " +
                "${current.physicalWidth}x${current.physicalHeight}; " +
                "modes=" + display.supportedModes.joinToString {
                    "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}"
                }
        )
    }

    private fun setOrientationBasedOnDeviceType() {
        requestedOrientation = if (DeviceUtils.isTablet(this)) {
            // Allow all orientations on tablets
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            // Lock to portrait on phones
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private companion object {
        const val TAG = "OrientationAwareActivity"
    }
}
