package com.bitchat.android.navigation

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateChatNavigationTest {

    private val navigator = AppNavigator()

    private fun stack(): List<Any> = navigator.backStack.toList()

    @Test
    fun `a chat opened from the peer list is pushed over it`() {
        navigator.resetTo(ChatRoute)
        navigator.goTo(MeshPeerListRoute)

        navigator.openPrivateChat("peer-a", over = MeshPeerListRoute)

        assertEquals(listOf(ChatRoute, MeshPeerListRoute, PrivateChatRoute("peer-a")), stack())
    }

    @Test
    fun `a second chat from the list replaces the first and keeps the list`() {
        navigator.resetTo(ChatRoute)
        navigator.goTo(MeshPeerListRoute)
        navigator.openPrivateChat("peer-a", over = MeshPeerListRoute)
        navigator.goTo(SecurityVerificationRoute("peer-a"))

        navigator.openPrivateChat("peer-b", over = MeshPeerListRoute)

        assertEquals(listOf(ChatRoute, MeshPeerListRoute, PrivateChatRoute("peer-b")), stack())
    }

    @Test
    fun `a chat opened over a base that is not on the stack falls back to chat`() {
        navigator.resetTo(ChatRoute)
        navigator.goTo(ChatUserRoute("alice", messageId = null))

        navigator.openPrivateChat("peer-a", over = MeshPeerListRoute)

        assertEquals(listOf(ChatRoute, PrivateChatRoute("peer-a")), stack())
    }

    @Test
    fun `a chat opened from a sheet over chat takes the sheet's place`() {
        navigator.resetTo(ChatRoute)
        navigator.goTo(ChatUserRoute("alice", messageId = null))

        navigator.openPrivateChat("peer-a")

        assertEquals(listOf(ChatRoute, PrivateChatRoute("peer-a")), stack())
    }

    @Test
    fun `a chat opened from another chat replaces it rather than stacking`() {
        navigator.resetTo(ChatRoute)
        navigator.openPrivateChat("peer-a")
        navigator.goTo(SecurityVerificationRoute("peer-a"))

        navigator.openPrivateChat("peer-b")

        assertEquals(listOf(ChatRoute, PrivateChatRoute("peer-b")), stack())
    }

    @Test
    fun `nothing opens while chat is not on the stack`() {
        navigator.resetTo(Onboarding)

        navigator.openPrivateChat("peer-a")

        assertEquals(listOf(Onboarding), stack())
    }

    /** Stands in for the host's onboarding route, which lives in :app. */
    private data object Onboarding : NavKey
}
