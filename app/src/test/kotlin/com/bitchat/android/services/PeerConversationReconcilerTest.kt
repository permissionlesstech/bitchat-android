package com.bitchat.android.services

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerConversationReconcilerTest {
    @Test
    fun `unchanged membership avoids conversation work while alias promotions still merge`() {
        val batches = mutableListOf<List<PeerConversationReconciler.PeerAliases>>()
        val reconciler = PeerConversationReconciler { batches.add(it) }
        val peers = List(1_000) { index ->
            PeerConversationReconciler.PeerAliases("fixture-$index", "fixture-$index", setOf("fixture-$index"))
        }
        reconciler.reconcile(peers)
        repeat(10) { reconciler.reconcile(peers.reversed()) }
        assertEquals(1, batches.size)
        assertEquals(1_000, batches.single().size)
        val promoted = peers[0].copy(conversationID = "fixture-contact", aliases = setOf("fixture-0", "fixture-contact"))
        reconciler.reconcile(listOf(promoted) + peers.drop(1))
        assertEquals(listOf(promoted), batches.last())
        reconciler.reconcile(emptyList())
        reconciler.reconcile(peers)
        assertEquals(3, batches.size)
    }
}
