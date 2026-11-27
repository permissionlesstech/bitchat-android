package com.bitchat.android.settings

/**
 * About's "background mode" switch: whether the mesh keeps running in a
 * foreground service once the app is closed. The app binds it, since the
 * preference and the service are both the app's.
 */
interface BackgroundMeshSetting {

    fun isEnabled(): Boolean

    /** Stores [enabled] and starts or stops the mesh foreground service to match. */
    fun setEnabled(enabled: Boolean)
}
