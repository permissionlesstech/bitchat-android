package com.bitchat.android.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.*

/**
 * Read receipt structure - exact same as iOS version
 */
@Parcelize
data class ReadReceipt(
    val originalMessageID: String,
    val receiptID: String = UUID.randomUUID().toString(),
    val readerID: String,
    val readerNickname: String,
    val timestamp: Date = Date()
) : Parcelable {
    
    fun encode(): ByteArray? {
        return try {
            com.google.gson.Gson().toJson(this).toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
    
    companion object {
        fun decode(data: ByteArray): ReadReceipt? {
            return try {
                val json = String(data, Charsets.UTF_8)
                com.google.gson.Gson().fromJson(json, ReadReceipt::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }
}
