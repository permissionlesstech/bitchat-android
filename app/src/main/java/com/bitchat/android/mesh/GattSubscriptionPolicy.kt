package com.bitchat.android.mesh

import android.bluetooth.BluetoothGattDescriptor

/**
 * Decides what a Client Characteristic Configuration Descriptor write means for
 * our broadcast-notification subscription list.
 *
 * A CCCD write is the one GATT operation a peer can repeat freely on an open
 * connection: it needs no authentication, no Noise session and no announcement,
 * and the Bluetooth spec places no limit on how often a client may rewrite the
 * descriptor. Every effect the server attaches to that write therefore has to be
 * idempotent, or a peer gets to drive it at whatever rate it likes.
 */
internal object GattSubscriptionPolicy {

    enum class Request {
        /** Client asked to receive broadcast notifications. */
        ENABLE,

        /** Client asked to stop receiving them. Honouring this is spec-required. */
        DISABLE,

        /** Any other descriptor value, including indications we do not use. */
        UNRECOGNIZED
    }

    fun classify(value: ByteArray?): Request = when {
        value == null -> Request.UNRECOGNIZED
        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.contentEquals(value) -> Request.ENABLE
        BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE.contentEquals(value) -> Request.DISABLE
        else -> Request.UNRECOGNIZED
    }
}
