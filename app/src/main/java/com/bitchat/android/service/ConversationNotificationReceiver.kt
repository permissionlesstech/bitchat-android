package com.bitchat.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.nostr.GeohashAliasRegistry
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrTransport
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.MessageRouter
import com.bitchat.android.ui.NotificationManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Date
import java.util.UUID

/** Handles privacy-scoped direct reply and mark-read actions from DM notifications. */
class ConversationNotificationReceiver : BroadcastReceiver() {

    /** The receiver is built by the system, so it reaches what it uses through an entry point. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun nostrTransport(): NostrTransport
        fun nostrIdentity(): NostrIdentityBridge
        fun favorites(): FavoritesPersistenceService
        fun geohashAliases(): GeohashAliasRegistry
        fun contactDirectory(): ContactDirectory
        fun appStateStore(): AppStateStore
        fun meshServiceHolder(): MeshServiceHolder
    }

    override fun onReceive(context: Context, intent: Intent) {
        val dependencies = EntryPointAccessors
            .fromApplication(context.applicationContext, Dependencies::class.java)
        val contactDirectory = dependencies.contactDirectory()
        val appStateStore = dependencies.appStateStore()
        val conversationID = intent.getStringExtra(NotificationManager.EXTRA_PEER_ID)
            ?.let(contactDirectory::canonicalConversationId)
            ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                var acknowledged = false
                when (intent.action) {
                    NotificationManager.ACTION_MARK_CONVERSATION_READ -> {
                        acknowledged =
                            appStateStore.setPrivateConversationRead(conversationID, true)
                    }

                    NotificationManager.ACTION_REPLY_TO_CONVERSATION -> {
                        val reply = RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(NotificationManager.KEY_TEXT_REPLY)
                            ?.toString()
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                            ?: return@launch
                        // A notification can outlive the process/service that posted it. Promote
                        // the mesh runtime before dispatch so Android keeps the transport alive
                        // after this short-lived receiver finishes.
                        MeshForegroundService.start(context.applicationContext)
                        val mesh = dependencies.meshServiceHolder().getUnifiedOrCreate(
                            context.applicationContext
                        )
                        val message = BitchatMessage(
                            id = UUID.randomUUID().toString().uppercase(),
                            sender = mesh.myPeerID,
                            content = reply,
                            timestamp = Date(),
                            isPrivate = true,
                            recipientNickname = intent.getStringExtra(
                                NotificationManager.EXTRA_SENDER_NICKNAME
                            ),
                            senderPeerID = mesh.myPeerID,
                            deliveryStatus = DeliveryStatus.Sending
                        )
                        val persisted = appStateStore.addPrivateMessageDurably(
                            peerID = conversationID,
                            msg = message,
                            forceRead = true
                        )
                        if (persisted) {
                            MessageRouter.getInstance(
                                context.applicationContext,
                                mesh,
                                dependencies.nostrIdentity(),
                                dependencies.favorites(),
                                contactDirectory,
                                dependencies.geohashAliases(),
                            ) {
                                dependencies.nostrTransport()
                            }
                                .sendPrivate(
                                    content = reply,
                                    toPeerID = conversationID,
                                    recipientNickname = message.recipientNickname.orEmpty(),
                                    messageID = message.id
                                )
                            acknowledged =
                                appStateStore.setPrivateConversationRead(conversationID, true)
                        }
                    }
                }
                if (acknowledged) {
                    NotificationManager.acknowledgeConversation(context, conversationID, contactDirectory)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
