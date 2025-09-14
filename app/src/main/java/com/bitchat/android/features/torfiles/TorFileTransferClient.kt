package com.bitchat.android.features.torfiles

import android.util.Log
import com.bitchat.android.net.OkHttpProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/**
 * Client for sending voice notes via Tor to a peer's onion service.
 */
object TorFileTransferClient {
    private const val TAG = "TorFileClient"

    fun sendVoiceNote(onionAddress: String, fromPeerID: String, file: File) {
        try {
            val url = "http://$onionAddress/upload"
            val client = OkHttpProvider.httpClient()
            val body: RequestBody = file.asRequestBody("audio/mp4".toMediaType())
            val req = Request.Builder()
                .url(url)
                .post(body)
                .addHeader("X-Bitchat-Type", "voice")
                .addHeader("X-Bitchat-PeerID", fromPeerID)
                .addHeader("X-Bitchat-Filename", file.name)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "Upload failed: ${resp.code}")
                } else {
                    Log.i(TAG, "Voice note sent to $onionAddress")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending voice note: ${e.message}")
        }
    }
}

