package com.bitchat.domain.model

import java.util.Date

sealed class DeliveryStatus {
    data object Sending : DeliveryStatus()
    data object Sent : DeliveryStatus()
    data class Delivered(val to: String, val at: Date) : DeliveryStatus()
    data class Read(val by: String, val at: Date) : DeliveryStatus()
    data class Failed(val reason: String) : DeliveryStatus()
    data class PartiallyDelivered(val reached: Int, val total: Int) : DeliveryStatus()
}

data class Message(
    val id: String,
    val sender: String,
    val content: String,
    val timestamp: Date,
    val isRelay: Boolean = false,
    val originalSender: String? = null,
    val isPrivate: Boolean = false,
    val recipientNickname: String? = null,
    val senderPeerId: String? = null,
    val mentions: List<String>? = null,
    val channel: String? = null,
    val encryptedContent: ByteArray? = null,
    val isEncrypted: Boolean = false,
    val deliveryStatus: DeliveryStatus? = null
)

