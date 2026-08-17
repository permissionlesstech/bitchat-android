package com.bitchat.android.service

/**
 * Wear shim for the phone's MeshServiceHolder.
 *
 * The shared `DebugSettingsManager` (compiled from app sources) references this holder only to
 * toggle BLE transport from the phone's debug UI, which does not exist on the watch. The real
 * holder is typed against `BluetoothMeshService`, which the watch deliberately does not include.
 */
object MeshServiceHolder {

    interface BleToggle {
        fun setBleTransportEnabled(enabled: Boolean)
    }

    val meshService: BleToggle? = null
}
