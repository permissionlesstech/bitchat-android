package com.bitchat.domain.model

data class FragmentPayload(
    val fragmentId: ByteArray,
    val index: Int,
    val total: Int,
    val originalType: UByte,
    val data: ByteArray
)

