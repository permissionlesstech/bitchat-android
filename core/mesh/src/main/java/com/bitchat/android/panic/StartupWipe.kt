package com.bitchat.android.panic

import android.content.Context
import android.util.Log

/**
 * Finishes a panic wipe that was interrupted or could not confirm. A pending [PanicMarker] means
 * the sandbox may still hold what the panic meant to remove, so this runs from
 * `Application.onCreate()` before Hilt, and anything else, reads storage.
 */
object StartupWipe {
    private const val TAG = "StartupWipe"
    private const val ATTEMPTS = 2

    /**
     * With no marker, does nothing and returns true. With one, tries the wipe up to [ATTEMPTS]
     * times and clears the marker once one confirms. Returns whether the marker is now clear; when
     * it is not, `IdentityHolder` refuses to build, so nothing runs over unwiped storage.
     */
    fun finishPendingWipe(
        context: Context,
        wipe: SandboxWipeRunner = SandboxWipeRunner { SandboxWipe(context).run() },
        marker: PanicMarker = PanicMarker(context),
    ): Boolean {
        if (!marker.pending) return true
        repeat(ATTEMPTS) { attempt ->
            val result = try {
                wipe.run()
            } catch (e: Exception) {
                Log.w(TAG, "Startup wipe attempt ${attempt + 1} failed: ${e.javaClass.simpleName}")
                null
            }
            if (result != null) {
                if (result.confirmed) {
                    marker.clear()
                    return !marker.pending
                }
                Log.w(
                    TAG,
                    "Startup wipe attempt ${attempt + 1} left ${result.leftovers.size} paths and " +
                        "${result.aliasesLeft} aliases",
                )
            }
        }
        return false
    }
}
