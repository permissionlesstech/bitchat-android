package com.bitchat.android.onboarding

/** A change to the device's radios that onboarding reacts to in any state. */
sealed interface SystemEvent {
    /** The Bluetooth adapter reached STATE_ON. */
    data object BluetoothOn : SystemEvent

    /** The Bluetooth adapter reached STATE_OFF. */
    data object BluetoothOff : SystemEvent

    /** The location mode or providers changed; [enabled] is the state read right then. */
    data class LocationChanged(val enabled: Boolean) : SystemEvent
}
