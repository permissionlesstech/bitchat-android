package com.bitchat.watch.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Tactile accents for the watch's important moments. Uses predefined vibration effects so
 * the feel stays consistent with the rest of Wear OS.
 */
object WearHaptics {
    /** Firm knock: recording started, message received. */
    fun knock(context: Context) = vibrate(context, VibrationEffect.EFFECT_HEAVY_CLICK)

    /** Crisp click: recording stopped, message sent. */
    fun click(context: Context) = vibrate(context, VibrationEffect.EFFECT_CLICK)

    /** Double tap: destructive/cancel confirmation. */
    fun reject(context: Context) = vibrate(context, VibrationEffect.EFFECT_DOUBLE_CLICK)

    /** Light tick: small confirmations. */
    fun tick(context: Context) = vibrate(context, VibrationEffect.EFFECT_TICK)

    private fun vibrate(context: Context, effect: Int) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return
        vibrator.vibrate(VibrationEffect.createPredefined(effect))
    }
}
