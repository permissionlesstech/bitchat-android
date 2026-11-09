package com.bitchat.android.testhook

import com.bitchat.android.identity.scope.IdentityComponent
import com.bitchat.android.identity.scope.IdentityHolder
import com.bitchat.android.identity.scope.entryPoint
import com.bitchat.android.services.ConversationRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn

/** Debug-only: reaches the current identity's conversation repository, which no bridge hands out. */
@EntryPoint
@InstallIn(IdentityComponent::class)
interface ConversationTestAccess {
    fun conversationRepository(): ConversationRepository

    companion object {
        fun of(holder: IdentityHolder): ConversationRepository =
            holder.entryPoint<ConversationTestAccess>().conversationRepository()
    }
}
