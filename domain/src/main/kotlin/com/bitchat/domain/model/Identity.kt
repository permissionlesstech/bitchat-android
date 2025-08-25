package com.bitchat.domain.model

data class IdentityAnnouncement(
    val nickname: String,
    val noisePublicKey: ByteArray,
    val signingPublicKey: ByteArray
)

