package com.bitchat.android.hotspot

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper

/**
 * Small boundary around the asynchronous Wi-Fi P2P API.
 *
 * [WifiP2pManager.ActionListener] only acknowledges that a command was accepted by the
 * framework. It does not report that group creation or removal has completed. Keeping
 * that distinction in this interface makes the manager's lifecycle testable without
 * teaching tests about framework callbacks or binder channels.
 */
internal interface HotspotP2p {
    val available: Boolean
    val supportsP2pStateQuery: Boolean
    val supportsCustomCredentials: Boolean
    val supportsChannelClose: Boolean

    interface Channel {
        fun close()
    }

    data class Group(
        val networkName: String?,
        val passphrase: String?,
        val clientCount: Int,
        val isGroupOwner: Boolean
    )

    data class Credentials(
        val networkName: String,
        val passphrase: String
    )

    interface ActionCallback {
        fun onAccepted()
        fun onRejected(reason: Int)
    }

    fun initialize(onDisconnected: () -> Unit): Channel?
    fun requestP2pState(channel: Channel, callback: (Int) -> Unit)
    fun requestGroup(channel: Channel, callback: (Group?) -> Unit)
    fun createGroup(
        channel: Channel,
        credentials: Credentials?,
        callback: ActionCallback
    )

    fun removeGroup(channel: Channel, callback: ActionCallback)
}

@SuppressLint("MissingPermission")
internal class AndroidHotspotP2p(
    private val context: Context
) : HotspotP2p {
    private val manager =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    override val available: Boolean
        get() = manager != null

    override val supportsP2pStateQuery: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override val supportsCustomCredentials: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override val supportsChannelClose: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1

    override fun initialize(onDisconnected: () -> Unit): HotspotP2p.Channel? {
        val raw = manager?.initialize(
            context,
            Looper.getMainLooper()
        ) {
            onDisconnected()
        } ?: return null
        return AndroidChannel(raw)
    }

    override fun requestP2pState(
        channel: HotspotP2p.Channel,
        callback: (Int) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            manager?.requestP2pState(channel.raw(), callback)
        } else {
            callback(WifiP2pManager.WIFI_P2P_STATE_DISABLED)
        }
    }

    override fun requestGroup(
        channel: HotspotP2p.Channel,
        callback: (HotspotP2p.Group?) -> Unit
    ) {
        manager?.requestGroupInfo(channel.raw()) { group ->
            callback(
                group?.let {
                    HotspotP2p.Group(
                        networkName = it.networkName,
                        passphrase = it.passphrase,
                        clientCount = it.clientList?.size ?: 0,
                        isGroupOwner = it.isGroupOwner
                    )
                }
            )
        }
    }

    override fun createGroup(
        channel: HotspotP2p.Channel,
        credentials: HotspotP2p.Credentials?,
        callback: HotspotP2p.ActionCallback
    ) {
        val listener = callback.asFrameworkListener()
        if (credentials == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            manager?.createGroup(channel.raw(), listener)
            return
        }

        val config = WifiP2pConfig.Builder()
            .setNetworkName(credentials.networkName)
            .setPassphrase(credentials.passphrase)
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
            .build()
        manager?.createGroup(channel.raw(), config, listener)
    }

    override fun removeGroup(
        channel: HotspotP2p.Channel,
        callback: HotspotP2p.ActionCallback
    ) {
        manager?.removeGroup(channel.raw(), callback.asFrameworkListener())
    }

    private fun HotspotP2p.ActionCallback.asFrameworkListener() =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onAccepted()
            override fun onFailure(reason: Int) = onRejected(reason)
        }

    private fun HotspotP2p.Channel.raw(): WifiP2pManager.Channel =
        (this as AndroidChannel).raw

    private class AndroidChannel(
        val raw: WifiP2pManager.Channel
    ) : HotspotP2p.Channel {
        override fun close() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                raw.close()
            }
        }
    }
}
