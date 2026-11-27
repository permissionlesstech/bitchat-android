package com.bitchat.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.bitchat.android.identity.scope.CurrentIdentity
import com.bitchat.android.identity.scope.IdentityHolder
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.MessageRouterHolder
import com.bitchat.android.ui.NotificationManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Handles privacy-scoped direct reply and mark-read actions from DM notifications. */
class ConversationNotificationReceiver : BroadcastReceiver() {

    /** The receiver is built by the system, so it reaches what it uses through an entry point. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        @CurrentIdentity fun contactDirectory(): ContactDirectory
        @CurrentIdentity fun appStateStore(): AppStateStore
        @CurrentIdentity fun meshServiceHolder(): MeshServiceHolder
        @CurrentIdentity fun messageRouterHolder(): MessageRouterHolder
        fun identityHolder(): IdentityHolder
    }

    override fun onReceive(context: Context, intent: Intent) {
        val dependencies = EntryPointAccessors
            .fromApplication(context.applicationContext, Dependencies::class.java)
        // Everything is read here, on the main thread, where a panic cannot be halfway through
        // swapping identities. From here on this reply belongs to this identity.
        val generation = dependencies.identityHolder().generation
        val receivedIn = generation.value
        val contactDirectory = dependencies.contactDirectory()
        val appStateStore = dependencies.appStateStore()
        val meshServiceHolder = dependencies.meshServiceHolder()
        val messageRouterHolder = dependencies.messageRouterHolder()
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
                        acknowledged = ConversationReply(
                            identityIsCurrent = { generation.value == receivedIn },
                            appStateStore = appStateStore,
                            mesh = { meshServiceHolder.getUnifiedOrCreate(context.applicationContext) },
                            router = messageRouterHolder::getOrAttach,
                        ).send(
                            conversationID = conversationID,
                            reply = reply,
                            recipientNickname = intent.getStringExtra(NotificationManager.EXTRA_SENDER_NICKNAME),
                        )
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
