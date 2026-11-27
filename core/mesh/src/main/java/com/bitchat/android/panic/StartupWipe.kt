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
     * @property foundPending a marker was pending at launch: a panic did not finish, so what it
     * left outside the sandbox may be there too, and [replayCleanups] should run.
     * @property markerClear the marker is now clear; when it is not, `IdentityHolder` refuses to
     * build, so nothing runs over unwiped storage.
     */
    data class Outcome(val foundPending: Boolean, val markerClear: Boolean)

    /**
     * With no marker, does nothing. With one, tries the wipe up to [ATTEMPTS] times and clears the
     * marker once one confirms.
     */
    fun finishPendingWipe(
        context: Context,
        wipe: SandboxWipeRunner = SandboxWipeRunner { SandboxWipe(context).run() },
        marker: PanicMarker = PanicMarker(context),
        savedScreensStale: PanicMarker = PanicMarker.savedScreensStale(context),
    ): Outcome {
        if (!marker.pending) return Outcome(foundPending = false, markerClear = true)
        // Before the wipe: the system kept the Activity's state outside the sandbox, and whichever
        // process opens the next Activity must not restore it, even if this one dies first.
        savedScreensStale.set()
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
                    return Outcome(foundPending = true, markerClear = !marker.pending)
                }
                Log.w(
                    TAG,
                    "Startup wipe attempt ${attempt + 1} left ${result.leftovers.size} paths and " +
                        "${result.aliasesLeft} aliases",
                )
            }
        }
        return Outcome(foundPending = true, markerClear = false)
    }

    /**
     * Runs the panic's system-side cleanups again after a launch that found a pending marker: the
     * process may have died before the panic reached them, leaving notifications, conversation
     * shortcuts or a copied clip. Every cleanup runs; a failure is logged by name and never stops
     * the launch.
     */
    /**
     * True once after a panic, for the next Activity created, which then starts with no saved
     * state: state saved before the panic names the ended identity's screens. The panic and the
     * startup wipe set it.
     */
    fun takeStaleSavedScreens(context: Context): Boolean {
        val stale = PanicMarker.savedScreensStale(context)
        if (!stale.pending) return false
        stale.clear()
        return true
    }

    fun replayCleanups(cleanups: Iterable<PanicCleanup>) {
        for (cleanup in cleanups) {
            val undone = try {
                cleanup.run()
            } catch (e: Exception) {
                Log.e(TAG, "System-side cleanup threw: ${cleanup.name}: ${e.javaClass.simpleName}")
                continue
            }
            if (!undone) Log.e(TAG, "System-side cleanup failed: ${cleanup.name}")
        }
    }
}
