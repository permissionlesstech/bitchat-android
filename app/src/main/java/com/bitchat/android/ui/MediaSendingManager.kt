package com.bitchat.android.ui

import android.util.Log
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.mesh.PrivateMediaPreparation
import java.util.Date
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LegacyPrivateMediaConsentRequest(
    val requestId: String,
    val recipientNickname: String,
    val fileName: String,
    val warning: String
)

/**
 * Handles media file sending operations (voice notes, images, generic files)
 * Separated from ChatViewModel for better separation of concerns
 */
class MediaSendingManager(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val channelManager: ChannelManager,
    private val getMeshService: () -> MeshService
) {
    // Helper to get current mesh service (may change after panic clear)
    private val meshService: MeshService
        get() = getMeshService()
    companion object {
        private const val TAG = "MediaSendingManager"
        private const val MAX_FILE_SIZE = com.bitchat.android.util.AppConstants.Media.MAX_FILE_SIZE_BYTES // 50MB limit
    }

    // Track in-flight transfer progress: transferId -> messageId and reverse
    private val transferMessageMap = mutableMapOf<String, String>()
    private val messageTransferMap = mutableMapOf<String, String>()
    private val pendingConsentLock = Any()
    private val _legacyPrivateMediaConsent = MutableStateFlow<LegacyPrivateMediaConsentRequest?>(null)
    val legacyPrivateMediaConsent: StateFlow<LegacyPrivateMediaConsentRequest?> =
        _legacyPrivateMediaConsent.asStateFlow()

    private data class PendingPrivateMedia(
        val request: LegacyPrivateMediaConsentRequest,
        val peerID: String,
        val filePacket: BitchatFilePacket,
        val filePath: String,
        val messageType: BitchatMessageType,
        val transferId: String
    )

    private var pendingPrivateMedia: PendingPrivateMedia? = null

    /**
     * Send a voice note (audio file)
     */
    fun sendVoiceNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        try {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "❌ File does not exist: $filePath")
                return
            }
            Log.d(TAG, "📁 File exists: size=${file.length()} bytes, name=${file.name}")
            
            if (file.length() > MAX_FILE_SIZE) {
                Log.e(TAG, "❌ File too large: ${file.length()} bytes (max: $MAX_FILE_SIZE)")
                return
            }

            val filePacket = BitchatFilePacket(
                fileName = file.name,
                fileSize = file.length(),
                mimeType = "audio/mp4",
                content = file.readBytes()
            )

            if (toPeerIDOrNull != null) {
                sendPrivateFile(toPeerIDOrNull, filePacket, filePath, BitchatMessageType.Audio)
            } else {
                sendPublicFile(channelOrNull, filePacket, filePath, BitchatMessageType.Audio)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send voice note: ${e.message}")
        }
    }

    /**
     * Send an image file
     */
    fun sendImageNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        try {
            Log.d(TAG, "🔄 Starting image send: $filePath")
            val file = java.io.File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "❌ File does not exist: $filePath")
                return
            }
            Log.d(TAG, "📁 File exists: size=${file.length()} bytes, name=${file.name}")
            
            if (file.length() > MAX_FILE_SIZE) {
                Log.e(TAG, "❌ File too large: ${file.length()} bytes (max: $MAX_FILE_SIZE)")
                return
            }

            val filePacket = BitchatFilePacket(
                fileName = file.name,
                fileSize = file.length(),
                mimeType = "image/jpeg",
                content = file.readBytes()
            )

            if (toPeerIDOrNull != null) {
                sendPrivateFile(toPeerIDOrNull, filePacket, filePath, BitchatMessageType.Image)
            } else {
                sendPublicFile(channelOrNull, filePacket, filePath, BitchatMessageType.Image)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL: Image send failed completely", e)
            Log.e(TAG, "❌ Image path: $filePath")
            Log.e(TAG, "❌ Error details: ${e.message}")
            Log.e(TAG, "❌ Error type: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Send a generic file
     */
    fun sendFileNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        try {
            Log.d(TAG, "🔄 Starting file send: $filePath")
            val file = java.io.File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "❌ File does not exist: $filePath")
                return
            }
            Log.d(TAG, "📁 File exists: size=${file.length()} bytes, name=${file.name}")
            
            if (file.length() > MAX_FILE_SIZE) {
                Log.e(TAG, "❌ File too large: ${file.length()} bytes (max: $MAX_FILE_SIZE)")
                return
            }

            // Use the real MIME type based on extension; fallback to octet-stream
            val mimeType = try { 
                com.bitchat.android.features.file.FileUtils.getMimeTypeFromExtension(file.name) 
            } catch (_: Exception) { 
                "application/octet-stream" 
            }
            Log.d(TAG, "🏷️ MIME type: $mimeType")

            // Try to preserve the original file name if our copier prefixed it earlier
            val originalName = run {
                val name = file.name
                val base = name.substringBeforeLast('.')
                val ext = name.substringAfterLast('.', "").let { if (it.isNotBlank()) ".${it}" else "" }
                val stripped = Regex("^send_\\d+_(.+)$").matchEntire(base)?.groupValues?.getOrNull(1) ?: base
                stripped + ext
            }
            Log.d(TAG, "📝 Original filename: $originalName")

            val filePacket = BitchatFilePacket(
                fileName = originalName,
                fileSize = file.length(),
                mimeType = mimeType,
                content = file.readBytes()
            )
            Log.d(TAG, "📦 Created file packet successfully")

            val messageType = when {
                mimeType.lowercase().startsWith("image/") -> BitchatMessageType.Image
                mimeType.lowercase().startsWith("audio/") -> BitchatMessageType.Audio
                else -> BitchatMessageType.File
            }

            if (toPeerIDOrNull != null) {
                sendPrivateFile(toPeerIDOrNull, filePacket, filePath, messageType)
            } else {
                sendPublicFile(channelOrNull, filePacket, filePath, messageType)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL: File send failed completely", e)
            Log.e(TAG, "❌ File path: $filePath")
            Log.e(TAG, "❌ Error details: ${e.message}")
            Log.e(TAG, "❌ Error type: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Send a file privately (encrypted)
     */
    private fun sendPrivateFile(
        toPeerID: String,
        filePacket: BitchatFilePacket,
        filePath: String,
        messageType: BitchatMessageType
    ) {
        val payload = filePacket.encode()
        if (payload == null) {
            Log.e(TAG, "❌ Failed to encode file packet for private send")
            return
        }
        Log.d(TAG, "🔒 Encoded private packet: ${payload.size} bytes")

        val transferId = sha256Hex(payload)
        val contentHash = sha256Hex(filePacket.content)

        Log.d(TAG, "📤 FILE_TRANSFER send (private): name='${filePacket.fileName}', size=${filePacket.fileSize}, mime='${filePacket.mimeType}', sha256=$contentHash, to=${toPeerID.take(8)} transferId=${transferId.take(16)}…")

        when (val preparation = meshService.prepareFilePrivate(
            recipientPeerID = toPeerID,
            file = filePacket,
            transferId = transferId,
            allowLegacyFallback = false
        )) {
            is PrivateMediaPreparation.Ready -> commitPreparedPrivateFile(
                preparation = preparation,
                toPeerID = toPeerID,
                filePath = filePath,
                messageType = messageType,
                transferId = transferId
            )

            is PrivateMediaPreparation.RequiresLegacyConsent -> {
                val nickname = try {
                    meshService.getPeerNicknames()[toPeerID]
                } catch (_: Exception) {
                    null
                } ?: toPeerID.take(8)
                val request = LegacyPrivateMediaConsentRequest(
                    requestId = UUID.randomUUID().toString(),
                    recipientNickname = nickname,
                    fileName = filePacket.fileName,
                    warning = preparation.warning
                )
                synchronized(pendingConsentLock) {
                    if (pendingPrivateMedia != null) {
                        Log.w(TAG, "A legacy private-media consent prompt is already pending")
                        return
                    }
                    pendingPrivateMedia = PendingPrivateMedia(
                        request,
                        toPeerID,
                        filePacket,
                        filePath,
                        messageType,
                        transferId
                    )
                    _legacyPrivateMediaConsent.value = request
                }
            }

            PrivateMediaPreparation.NeedsHandshake -> {
                Log.i(TAG, "Private media needs a Noise handshake; initiating now, with no local echo")
                try {
                    meshService.initiateNoiseHandshake(toPeerID)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not initiate private-media Noise handshake: ${e.message}")
                }
            }

            is PrivateMediaPreparation.Rejected ->
                Log.w(TAG, "Private media not sent: ${preparation.reason}")
        }
    }

    /**
     * Consume consent exactly once, then re-run policy and final-packet
     * admission. A capability pin appearing while the dialog was open upgrades
     * this send to encrypted rather than forcing the legacy path.
     */
    fun approveLegacyPrivateMedia(requestId: String) {
        val pending = consumePendingConsent(requestId) ?: return
        when (val preparation = meshService.prepareFilePrivate(
            recipientPeerID = pending.peerID,
            file = pending.filePacket,
            transferId = pending.transferId,
            allowLegacyFallback = true
        )) {
            is PrivateMediaPreparation.Ready -> commitPreparedPrivateFile(
                preparation,
                pending.peerID,
                pending.filePath,
                pending.messageType,
                pending.transferId
            )
            is PrivateMediaPreparation.RequiresLegacyConsent ->
                Log.w(TAG, "Legacy consent was consumed but policy still requested consent; send aborted")
            PrivateMediaPreparation.NeedsHandshake -> {
                Log.i(TAG, "Noise session disappeared before consent approval; initiating a new handshake")
                try {
                    meshService.initiateNoiseHandshake(pending.peerID)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not re-initiate private-media Noise handshake: ${e.message}")
                }
            }
            is PrivateMediaPreparation.Rejected ->
                Log.w(TAG, "Private media policy changed before approval: ${preparation.reason}")
        }
    }

    fun cancelLegacyPrivateMedia(requestId: String) {
        consumePendingConsent(requestId)
    }

    fun clearPendingPrivateMediaConsent() {
        synchronized(pendingConsentLock) {
            pendingPrivateMedia = null
            _legacyPrivateMediaConsent.value = null
        }
    }

    private fun consumePendingConsent(requestId: String): PendingPrivateMedia? {
        return synchronized(pendingConsentLock) {
            val pending = pendingPrivateMedia
            if (pending?.request?.requestId != requestId) return@synchronized null
            pendingPrivateMedia = null
            _legacyPrivateMediaConsent.value = null
            pending
        }
    }

    private fun commitPreparedPrivateFile(
        preparation: PrivateMediaPreparation.Ready,
        toPeerID: String,
        filePath: String,
        messageType: BitchatMessageType,
        transferId: String
    ) {
        if (preparation.transfer.transferId != transferId) {
            Log.e(TAG, "Prepared private-media transfer ID changed; send aborted")
            return
        }

        val msg = BitchatMessage(
            id = UUID.randomUUID().toString().uppercase(),
            sender = state.getNicknameValue() ?: "me",
            content = filePath,
            type = messageType,
            timestamp = Date(),
            isRelay = false,
            isPrivate = true,
            recipientNickname = try { meshService.getPeerNicknames()[toPeerID] } catch (_: Exception) { null },
            senderPeerID = meshService.myPeerID
        )

        // Preparation already built and admitted the exact final packet. Map
        // progress before commit so the first asynchronous event cannot race us.
        messageManager.addPrivateMessage(toPeerID, msg)
        synchronized(transferMessageMap) {
            transferMessageMap[transferId] = msg.id
            messageTransferMap[msg.id] = transferId
        }
        messageManager.updateMessageDeliveryStatus(
            msg.id,
            com.bitchat.android.model.DeliveryStatus.PartiallyDelivered(0, 100)
        )

        if (!preparation.transfer.commit()) {
            messageManager.removeMessageById(msg.id)
            synchronized(transferMessageMap) {
                transferMessageMap.remove(transferId)
                messageTransferMap.remove(msg.id)
            }
            Log.w(TAG, "Prepared private-media commit failed; local echo rolled back")
            return
        }
        Log.d(TAG, "✅ Private media committed using ${preparation.transfer.wireMode}")
    }

    /**
     * Send a file publicly (broadcast or channel)
     */
    private fun sendPublicFile(
        channelOrNull: String?,
        filePacket: BitchatFilePacket,
        filePath: String,
        messageType: BitchatMessageType
    ) {
        val payload = filePacket.encode()
        if (payload == null) {
            Log.e(TAG, "❌ Failed to encode file packet for broadcast send")
            return
        }
        Log.d(TAG, "🔓 Encoded broadcast packet: ${payload.size} bytes")
        
        val transferId = sha256Hex(payload)
        val contentHash = sha256Hex(filePacket.content)
        
        Log.d(TAG, "📤 FILE_TRANSFER send (broadcast): name='${filePacket.fileName}', size=${filePacket.fileSize}, mime='${filePacket.mimeType}', sha256=$contentHash, transferId=${transferId.take(16)}…")

        val message = BitchatMessage(
            id = java.util.UUID.randomUUID().toString().uppercase(), // Generate unique ID for each message
            sender = state.getNicknameValue() ?: meshService.myPeerID,
            content = filePath,
            type = messageType,
            timestamp = Date(),
            isRelay = false,
            senderPeerID = meshService.myPeerID,
            channel = channelOrNull
        )
        
        if (!channelOrNull.isNullOrBlank()) {
            channelManager.addChannelMessage(channelOrNull, message, meshService.myPeerID)
        } else {
            messageManager.addMessage(message)
        }
        
        synchronized(transferMessageMap) {
            transferMessageMap[transferId] = message.id
            messageTransferMap[message.id] = transferId
        }
        
        // Seed progress so animations start immediately
        messageManager.updateMessageDeliveryStatus(
            message.id,
            com.bitchat.android.model.DeliveryStatus.PartiallyDelivered(0, 100)
        )
        
        Log.d(TAG, "📤 Calling meshService.sendFileBroadcast")
        meshService.sendFileBroadcast(filePacket)
        Log.d(TAG, "✅ File broadcast completed successfully")
    }

    /**
     * Cancel a media transfer by message ID
     */
    fun cancelMediaSend(messageId: String) {
        val transferId = synchronized(transferMessageMap) { messageTransferMap[messageId] }
        if (transferId != null) {
            val cancelled = meshService.cancelFileTransfer(transferId)
            if (cancelled) {
                // Try to remove cached local file for this message (if any)
                runCatching { findMessagePathById(messageId)?.let { java.io.File(it).delete() } }

                // Remove the message from chat upon explicit cancel
                messageManager.removeMessageById(messageId)
                synchronized(transferMessageMap) {
                    transferMessageMap.remove(transferId)
                    messageTransferMap.remove(messageId)
                }
            }
        }
    }

    private fun findMessagePathById(messageId: String): String? {
        // Search main timeline
        state.getMessagesValue().firstOrNull { it.id == messageId }?.content?.let { return it }
        // Search private chats
        state.getPrivateChatsValue().values.forEach { list ->
            list.firstOrNull { it.id == messageId }?.content?.let { return it }
        }
        // Search channel messages
        state.getChannelMessagesValue().values.forEach { list ->
            list.firstOrNull { it.id == messageId }?.content?.let { return it }
        }
        return null
    }

    /**
     * Update progress for a transfer
     */
    fun updateTransferProgress(transferId: String, messageId: String) {
        synchronized(transferMessageMap) {
            transferMessageMap[transferId] = messageId
            messageTransferMap[messageId] = transferId
        }
    }

    /**
     * Handle transfer progress events
     */
    fun handleTransferProgressEvent(evt: com.bitchat.android.mesh.TransferProgressEvent) {
        val msgId = synchronized(transferMessageMap) { transferMessageMap[evt.transferId] }
        if (msgId != null) {
            if (evt.completed) {
                messageManager.updateMessageDeliveryStatus(
                    msgId,
                    com.bitchat.android.model.DeliveryStatus.Delivered(to = "mesh", at = java.util.Date())
                )
                synchronized(transferMessageMap) {
                    val msgIdRemoved = transferMessageMap.remove(evt.transferId)
                    if (msgIdRemoved != null) messageTransferMap.remove(msgIdRemoved)
                }
            } else {
                messageManager.updateMessageDeliveryStatus(
                    msgId,
                    com.bitchat.android.model.DeliveryStatus.PartiallyDelivered(evt.sent, evt.total)
                )
            }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        bytes.size.toString(16)
    }
}
