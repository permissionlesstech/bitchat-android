package com.bitchat.android.model

import android.os.Parcelable
import com.google.gson.GsonBuilder
import kotlinx.parcelize.Parcelize
import java.util.*

/**
 * Delivery acknowledgment structure - exact same as iOS version
 */
@Parcelize
data class DeliveryAck(
    val originalMessageID: String,
    val ackID: String = UUID.randomUUID().toString(),
    val recipientID: String,
    val recipientNickname: String,
    val timestamp: Date = Date(),
    val hopCount: UInt
) : Parcelable {

    private val gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .create()
    
    fun encode(): ByteArray? {
        return try {
            gson.toJson(this).toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
    
    companion object {
        fun decode(data: ByteArray): DeliveryAck? {
            return try {
                val json = String(data, Charsets.UTF_8)
                com.google.gson.Gson().fromJson(json, DeliveryAck::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }
}
