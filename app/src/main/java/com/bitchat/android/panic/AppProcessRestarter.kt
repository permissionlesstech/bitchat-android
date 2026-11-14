package com.bitchat.android.panic

import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Opens the launcher Activity in a fresh task and ends the process, so the system starts the app
 * again in a new one. The panic marker is still set then, and the new process finishes the wipe
 * before anything reads storage.
 */
class AppProcessRestarter @Inject constructor(
    @ApplicationContext private val context: Context,
) : ProcessRestarter {
    override fun restart() {
        try {
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                context.startActivity(launch)
            }
        } catch (e: Exception) {
            // The process ends anyway; the person opens the app again and the wipe finishes then.
            Log.e(TAG, "Could not schedule the relaunch: ${e.javaClass.simpleName}")
        } finally {
            Runtime.getRuntime().exit(0)
        }
    }

    private companion object {
        const val TAG = "AppProcessRestarter"
    }
}
