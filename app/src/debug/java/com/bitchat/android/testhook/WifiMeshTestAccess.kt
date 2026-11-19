package com.bitchat.android.testhook

import com.bitchat.android.identity.scope.IdentityComponent
import com.bitchat.android.wifiaware.WifiAwareMeshService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn

/** Builds a Wi-Fi Aware mesh the way its controller does, for device tests. It is not started. Debug builds only. */
@EntryPoint
@InstallIn(IdentityComponent::class)
interface WifiMeshTestAccess {
    fun wifiAwareMeshService(): WifiAwareMeshService
}
