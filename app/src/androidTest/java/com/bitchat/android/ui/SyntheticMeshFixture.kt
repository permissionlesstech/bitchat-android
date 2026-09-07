package com.bitchat.android.ui

import com.bitchat.android.mesh.MeshService
import java.lang.reflect.Proxy

internal fun syntheticMesh(): MeshService = Proxy.newProxyInstance(
    MeshService::class.java.classLoader, arrayOf(MeshService::class.java),
) { _, method, _ ->
    when (method.name) {
        "getMyPeerID" -> "synthetic-self"
        "getPeerNicknames" -> emptyMap<String, String>()
        "shouldShowEncryptionIcon", "hasEstablishedSession" -> false
        "toString" -> "Synthetic mesh"
        "hashCode" -> 1
        "equals" -> false
        else -> null
    }
} as MeshService
