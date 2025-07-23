package com.bitchat.android

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bitchat.android.onboarding.BluetoothStatus

class BluetoothStateReceiver(private val onConnectionChange: (BluetoothStatus) -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent != null && context != null) {
            val action = intent.action
            if (BluetoothAdapter.ACTION_STATE_CHANGED == action) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        onConnectionChange.invoke(BluetoothStatus.ENABLED)
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        onConnectionChange.invoke(BluetoothStatus.DISABLED)
                    }
                    else -> {
                        onConnectionChange.invoke(BluetoothStatus.NOT_SUPPORTED)
                    }
                }
            }
        }
    }
}