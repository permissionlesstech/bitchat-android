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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val scope: CoroutineScope,
    private val mediaWorkDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val getMeshService: () -> MeshService
) {
    // Helper to get current mesh service (may change after panic clear)
    private val meshService: MeshService
        get() = getMeshService()
    companion object {
        private const val TAG = "MediaSendingManager"
        private const val MAX_FILE_SIZE = com.bitchat.android.util.AppConstants.Media.MAX_FILE_SIZE_BYTES
        private const val PENDING_PRIVATE_MEDIA_TIMEOUT_MS = 15_000L
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
        val conversationID: String,
        val recipientMeshPeerID: String,
        val filePacket: BitchatFilePacket,
        val filePath: String,
        val messageType: BitchatMessageType,
        val transferId: String
    )

    private var pendingPrivateMedia: PendingPrivateMedia? = null

    private data class PendingAutomaticPrivateMedia(
        val requestId: String,
        val conversationID: String,
        val recipientMeshPeerID: String,
        val filePacket: BitchatFilePacket,
        val filePath: String,
        val messageType: BitchatMessageType,
        val transferId: String,
        val allowLegacyFallback: Boolean
    )

    private var pendingAutomaticPrivateMedia: PendingAutomaticPrivateMedia? = null
    private var evaluatingAutomaticRequestId: String? = null
    private var automaticRetryRequestedFor: String? = null
    private var pendingAutomaticTimeoutRequestId: String? = null

    /**
     * Enforce the send-size cap with a user-visible failure posted to the
     * conversation the user is sending from. Returns true if the file is
     * oversized and the send was aborted.
     */
    private fun rejectIfOversized(
        file: java.io.File,
        toPeerIDOrNull: String?,
        channelOrNull: String?
    ): Boolean {
        val size = file.length()
        if (size <= MAX_FILE_SIZE) return false
        Log.e(TAG, "❌ File too large: $size bytes (max: $MAX_FILE_SIZE)")
        val sizeMb = size / (1024 * 1024)
        val maxMb = MAX_FILE_SIZE / (1024 * 1024)
        val text = "cannot send ${file.name}: file is too large (${sizeMb} MB, max $maxMb MB)"
        when {
            toPeerIDOrNull != null -> {
                val sys = BitchatMessage(
                    sender = "system",
                    content = text,
                    timestamp = Date(),
                    isRelay = false
                )
                messageManager.addPrivateMessageNoUnread(toPeerIDOrNull, sys)
            }
            channelOrNull != null -> {
                val sys = BitchatMessage(
                    sender = "system",
                    content = text,
                    timestamp = Date(),
                    isRelay = false
                )
                messageManager.addChannelMessage(channelOrNull, sys)
            }
            else -> messageManager.addSystemMessage(text)
        }
        return true
    }

    /**
     * Send a voice note (audio file)
     */
    fun sendVoiceNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        scope.launch {
            sendVoiceNoteAsync(toPeerIDOrNull, channelOrNull, filePath)
        }
    }

    private suspend fun sendVoiceNoteAsync(
        toPeerIDOrNull: String?,
        channelOrNull: String?,
        filePath: String
    ) {
        try {
            val filePacket = withContext(mediaWorkDispatcher) {
                val file = java.io.File(filePath)
                if (!file.exists()) {
                    Log.e(TAG, "Voice note file does not exist")
                    return@withContext null
                }

                if (rejectIfOversized(file, toPeerIDOrNull, channelOrNull)) {
                    return@withContext null
                }

                BitchatFilePacket(
                    fileName = file.name,
                    fileSize = file.length(),
                    mimeType = "audio/mp4",
                    content = file.readBytes()
                )
            } ?: return

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
        scope.launch {
            sendImageNoteAsync(toPeerIDOrNull, channelOrNull, filePath)
        }
    }

    private suspend fun sendImageNoteAsync(
        toPeerIDOrNull: String?,
        channelOrNull: String?,
        filePath: String
    ) {
        try {
            val filePacket = withContext(mediaWorkDispatcher) {
                val file = java.io.File(filePath)
                if (!file.exists()) {
                    Log.e(TAG, "Image file does not exist")
                    return@withContext null
                }

                if (rejectIfOversized(file, toPeerIDOrNull, channelOrNull)) {
                    return@withContext null
                }

                BitchatFilePacket(
                    fileName = file.name,
                    fileSize = file.length(),
                    mimeType = "image/jpeg",
                    content = file.readBytes()
                )
            } ?: return

            if (toPeerIDOrNull != null) {
                sendPrivateFile(toPeerIDOrNull, filePacket, filePath, BitchatMessageType.Image)
            } else {
                sendPublicFile(channelOrNull, filePacket, filePath, BitchatMessageType.Image)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image send failed: ${e.message}", e)
        }
    }

    /**
     * Send a generic file
     */
    fun sendFileNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        scope.launch {
            sendFileNoteAsync(toPeerIDOrNull, channelOrNull, filePath)
        }
    }

    private suspend fun sendFileNoteAsync(
        toPeerIDOrNull: String?,
        channelOrNull: String?,
        filePath: String
    ) {
        try {
            val filePacket = withContext(mediaWorkDispatcher) {
                val file = java.io.File(filePath)
                if (!file.exists()) {
                    Log.e(TAG, "File does not exist")
                    return@withContext null
                }

                if (rejectIfOversized(file, toPeerIDOrNull, channelOrNull)) {
                    return@withContext null
                }

                // Use the real MIME type based on extension; fallback to octet-stream
                val mimeType = try {
                    com.bitchat.android.features.file.FileUtils.getMimeTypeFromExtension(file.name)
                } catch (_: Exception) {
                    "application/octet-stream"
                }

                // Try to preserve the original file name if our copier prefixed it earlier
                val originalName = run {
                    val name = file.name
                    val base = name.substringBeforeLast('.')
                    val ext = name.substringAfterLast('.', "").let {
                        if (it.isNotBlank()) ".${it}" else ""
                    }
                    val stripped = Regex("^send_\\d+_(.+)$")
                        .matchEntire(base)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?: base
                    stripped + ext
                }

                BitchatFilePacket(
                    fileName = originalName,
                    fileSize = file.length(),
                    mimeType = mimeType,
                    content = file.readBytes()
                )
            } ?: return

            val messageType = when {
                filePacket.mimeType.lowercase().startsWith("image/") -> BitchatMessageType.Image
                filePacket.mimeType.lowercase().startsWith("audio/") -> BitchatMessageType.Audio
                else -> BitchatMessageType.File
            }

            if (toPeerIDOrNull != null) {
                sendPrivateFile(toPeerIDOrNull, filePacket, filePath, messageType)
            } else {
                sendPublicFile(channelOrNull, filePacket, filePath, messageType)
            }
        } catch (e: Exception) {
            Log.e(TAG, "File send failed: ${e.message}", e)
        }
    }

    /**
     * Send a file privately (encrypted)
     */
    private suspend fun sendPrivateFile(
        toPeerID: String,
        filePacket: BitchatFilePacket,
        filePath: String,
        messageType: BitchatMessageType
    ) {
        val payload = withContext(mediaWorkDispatcher) { filePacket.encode() }
            ?: run {
                Log.e(TAG, "Failed to encode file packet for private send")
                return
            }

        val transferId = withContext(mediaWorkDispatcher) {
            sha256Hex(payload)
        }
        val recipient = PrivateMediaRecipientResolver.resolve(toPeerID, meshService)
            ?: run {
                addPrivateMediaSystemMessage(
                    toPeerID,
                    "Private media was not sent because this conversation has no active mesh route."
                )
                return
            }

        val pending = PendingAutomaticPrivateMedia(
            requestId = UUID.randomUUID().toString(),
            conversationID = recipient.conversationID,
            recipientMeshPeerID = recipient.meshPeerID,
            filePacket = filePacket,
            filePath = filePath,
            messageType = messageType,
            transferId = transferId,
            allowLegacyFallback = false
        )
        if (!reserveAutomaticPending(pending)) {
            addPrivateMediaSystemMessage(
                recipient.conversationID,
                "Private media was not sent because another secure media send is still pending."
            )
            return
        }
        evaluateAutomaticPending(pending)
    }

    /**
     * Consume consent exactly once, then re-run policy and final-packet
     * admission. A capability pin appearing while the dialog was open upgrades
     * this send to encrypted rather than forcing the legacy path.
     */
    fun approveLegacyPrivateMedia(requestId: String) {
        scope.launch {
            approveLegacyPrivateMediaAsync(requestId)
        }
    }

    private suspend fun approveLegacyPrivateMediaAsync(requestId: String) {
        val pending = consumePendingConsent(requestId) ?: return
        val automatic = PendingAutomaticPrivateMedia(
            requestId = UUID.randomUUID().toString(),
            conversationID = pending.conversationID,
            recipientMeshPeerID = pending.recipientMeshPeerID,
            filePacket = pending.filePacket,
            filePath = pending.filePath,
            messageType = pending.messageType,
            transferId = pending.transferId,
            allowLegacyFallback = true
        )
        if (!reserveAutomaticPending(automatic)) {
            addPrivateMediaSystemMessage(
                pending.conversationID,
                "Private media was not sent because another secure media send is still pending."
            )
            return
        }
        evaluateAutomaticPending(automatic)
    }

    fun cancelLegacyPrivateMedia(requestId: String) {
        consumePendingConsent(requestId)
    }

    fun clearPendingPrivateMediaConsent() {
        synchronized(pendingConsentLock) {
            pendingPrivateMedia = null
            pendingAutomaticPrivateMedia = null
            evaluatingAutomaticRequestId = null
            automaticRetryRequestedFor = null
            pendingAutomaticTimeoutRequestId = null
            _legacyPrivateMediaConsent.value = null
        }
    }

    /** Retry the exact first-send intent after peer-state proof or watchdog resolution. */
    fun retryPendingPrivateMedia(peerID: String) {
        scope.launch { retryPendingPrivateMediaOnScope(peerID) }
    }

    private suspend fun retryPendingPrivateMediaOnScope(peerID: String) {
        val pending = synchronized(pendingConsentLock) {
            pendingAutomaticPrivateMedia
                ?.takeIf { it.recipientMeshPeerID == peerID }
        } ?: return
        evaluateAutomaticPending(pending)
    }

    private suspend fun evaluateAutomaticPending(pending: PendingAutomaticPrivateMedia) {
        val acquired = synchronized(pendingConsentLock) {
            if (pendingAutomaticPrivateMedia?.requestId != pending.requestId) {
                return@synchronized false
            }
            if (evaluatingAutomaticRequestId == pending.requestId) {
                automaticRetryRequestedFor = pending.requestId
                return@synchronized false
            }
            evaluatingAutomaticRequestId = pending.requestId
            true
        }
        if (!acquired) return

        while (true) {
            val preparation = try {
                withContext(mediaWorkDispatcher) {
                    meshService.prepareFilePrivate(
                        recipientPeerID = pending.recipientMeshPeerID,
                        file = pending.filePacket,
                        transferId = pending.transferId,
                        allowLegacyFallback = pending.allowLegacyFallback
                    )
                }
            } catch (error: Exception) {
                PrivateMediaPreparation.Rejected(
                    error.message ?: "Secure private-media preparation failed"
                )
            }
            val stillCurrent = synchronized(pendingConsentLock) {
                pendingAutomaticPrivateMedia?.requestId == pending.requestId
            }
            if (stillCurrent) handlePrivatePreparation(preparation, pending)

            val rerun = synchronized(pendingConsentLock) {
                if (evaluatingAutomaticRequestId == pending.requestId) {
                    evaluatingAutomaticRequestId = null
                }
                val requested = automaticRetryRequestedFor == pending.requestId &&
                    pendingAutomaticPrivateMedia?.requestId == pending.requestId
                if (automaticRetryRequestedFor == pending.requestId) {
                    automaticRetryRequestedFor = null
                }
                if (requested) evaluatingAutomaticRequestId = pending.requestId
                requested
            }
            if (!rerun) return
        }
    }

    private suspend fun handlePrivatePreparation(
        preparation: PrivateMediaPreparation,
        pending: PendingAutomaticPrivateMedia
    ) {
        when (preparation) {
            is PrivateMediaPreparation.Ready -> {
                clearAutomaticPending(pending.requestId)
                commitPreparedPrivateFile(
                    preparation,
                    pending.conversationID,
                    pending.recipientMeshPeerID,
                    pending.filePath,
                    pending.messageType,
                    pending.transferId
                )
            }

            is PrivateMediaPreparation.RequiresLegacyConsent -> {
                clearAutomaticPending(pending.requestId)
                if (pending.allowLegacyFallback) {
                    Log.w(TAG, "Legacy consent was consumed but policy still requested consent; send aborted")
                    addPrivateMediaSystemMessage(
                        pending.conversationID,
                        "Private media was not sent because its security policy changed."
                    )
                    return
                }
                val nickname = try {
                    meshService.getPeerNicknames()[pending.recipientMeshPeerID]
                } catch (_: Exception) {
                    null
                } ?: pending.recipientMeshPeerID.take(8)
                val request = LegacyPrivateMediaConsentRequest(
                    requestId = UUID.randomUUID().toString(),
                    recipientNickname = nickname,
                    fileName = pending.filePacket.fileName,
                    warning = preparation.warning
                )
                synchronized(pendingConsentLock) {
                    if (pendingPrivateMedia != null) {
                        Log.w(TAG, "A legacy private-media consent prompt is already pending")
                        return
                    }
                    pendingPrivateMedia = PendingPrivateMedia(
                        request,
                        pending.conversationID,
                        pending.recipientMeshPeerID,
                        pending.filePacket,
                        pending.filePath,
                        pending.messageType,
                        pending.transferId
                    )
                    _legacyPrivateMediaConsent.value = request
                }
            }

            PrivateMediaPreparation.NeedsHandshake -> {
                ensureAutomaticPendingTimeout(pending)
                Log.d(TAG, "Private media needs a Noise handshake; retaining first-send intent")
                try {
                    meshService.initiateNoiseHandshake(pending.recipientMeshPeerID)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not initiate private-media Noise handshake: ${e.message}")
                }
            }

            PrivateMediaPreparation.AwaitingPeerState -> {
                ensureAutomaticPendingTimeout(pending)
                Log.d(TAG, "Private media is waiting for authenticated peer state; first-send intent retained")
            }

            is PrivateMediaPreparation.Rejected -> {
                clearAutomaticPending(pending.requestId)
                Log.w(TAG, "Private media not sent: ${preparation.reason}")
                addPrivateMediaSystemMessage(
                    pending.conversationID,
                    "Private media was not sent: ${preparation.reason}"
                )
            }
        }
    }

    private fun reserveAutomaticPending(pending: PendingAutomaticPrivateMedia): Boolean =
        synchronized(pendingConsentLock) {
            if (pendingAutomaticPrivateMedia != null) return@synchronized false
            pendingAutomaticPrivateMedia = pending
            true
        }

    private fun ensureAutomaticPendingTimeout(pending: PendingAutomaticPrivateMedia) {
        val shouldStart = synchronized(pendingConsentLock) {
            if (pendingAutomaticPrivateMedia?.requestId != pending.requestId ||
                pendingAutomaticTimeoutRequestId == pending.requestId
            ) return@synchronized false
            pendingAutomaticTimeoutRequestId = pending.requestId
            true
        }
        if (!shouldStart) return
        scope.launch {
            delay(PENDING_PRIVATE_MEDIA_TIMEOUT_MS)
            val expired = synchronized(pendingConsentLock) {
                if (pendingAutomaticPrivateMedia?.requestId != pending.requestId) false
                else {
                    pendingAutomaticPrivateMedia = null
                    if (automaticRetryRequestedFor == pending.requestId) {
                        automaticRetryRequestedFor = null
                    }
                    if (pendingAutomaticTimeoutRequestId == pending.requestId) {
                        pendingAutomaticTimeoutRequestId = null
                    }
                    true
                }
            }
            if (expired) {
                addPrivateMediaSystemMessage(
                    pending.conversationID,
                    "Private media was not sent because secure session setup timed out."
                )
            }
        }
    }

    private fun clearAutomaticPending(requestId: String) {
        synchronized(pendingConsentLock) {
            if (pendingAutomaticPrivateMedia?.requestId == requestId) {
                pendingAutomaticPrivateMedia = null
            }
            if (automaticRetryRequestedFor == requestId) automaticRetryRequestedFor = null
            if (pendingAutomaticTimeoutRequestId == requestId) {
                pendingAutomaticTimeoutRequestId = null
            }
        }
    }

    private fun addPrivateMediaSystemMessage(peerID: String, text: String) {
        messageManager.addPrivateMessageNoUnread(
            peerID,
            BitchatMessage(
                sender = "system",
                content = text,
                timestamp = Date(),
                isRelay = false,
                isPrivate = true,
                senderPeerID = peerID
            )
        )
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

    private suspend fun commitPreparedPrivateFile(
        preparation: PrivateMediaPreparation.Ready,
        conversationID: String,
        recipientMeshPeerID: String,
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
            recipientNickname = try {
                meshService.getPeerNicknames()[recipientMeshPeerID]
            } catch (_: Exception) {
                null
            },
            senderPeerID = meshService.myPeerID
        )

        // Preparation already built and admitted the exact final packet. Map
        // progress before commit so the first asynchronous event cannot race us.
        if (!messageManager.addPrivateMessageDurably(conversationID, msg, forceRead = true)) {
            Log.e(TAG, "Prepared private-media message could not be persisted; send aborted")
            addPrivateMediaSystemMessage(
                conversationID,
                "Private media was not sent because the conversation could not be saved."
            )
            return
        }
        synchronized(transferMessageMap) {
            transferMessageMap[transferId] = msg.id
            messageTransferMap[msg.id] = transferId
        }
        messageManager.updateMessageDeliveryStatus(
            msg.id,
            com.bitchat.android.model.DeliveryStatus.PartiallyDelivered(0, 100)
        )

        if (!preparation.transfer.commit()) {
            synchronized(transferMessageMap) {
                transferMessageMap.remove(transferId)
                messageTransferMap.remove(msg.id)
            }
            messageManager.updateMessageDeliveryStatus(
                msg.id,
                com.bitchat.android.model.DeliveryStatus.Failed(
                    "Prepared transfer could not be committed"
                )
            )
            Log.w(TAG, "Prepared private-media commit failed; local echo marked failed")
            addPrivateMediaSystemMessage(
                conversationID,
                "Private media was not sent because the prepared transfer could not be committed."
            )
            return
        }
    }

    /**
     * Send a file publicly (broadcast or channel)
     */
    private suspend fun sendPublicFile(
        channelOrNull: String?,
        filePacket: BitchatFilePacket,
        filePath: String,
        messageType: BitchatMessageType
    ) {
        val payload = withContext(mediaWorkDispatcher) { filePacket.encode() }
            ?: run {
                Log.e(TAG, "Failed to encode file packet for broadcast send")
                return
            }

        val transferId = withContext(mediaWorkDispatcher) {
            sha256Hex(payload)
        }

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
        
        withContext(mediaWorkDispatcher) {
            meshService.sendFileBroadcast(filePacket)
        }
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
            if (evt.failed) {
                messageManager.updateMessageDeliveryStatus(
                    msgId,
                    com.bitchat.android.model.DeliveryStatus.Failed("transfer could not be sent")
                )
                synchronized(transferMessageMap) {
                    val msgIdRemoved = transferMessageMap.remove(evt.transferId)
                    if (msgIdRemoved != null) messageTransferMap.remove(msgIdRemoved)
                }
            } else if (evt.completed) {
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
