package com.bitchat.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearNavigationStateTest {

    @Test
    fun `notification dm goes back to main chat`() {
        val navigation = WearNavigationState()
        navigation.navigate(WearScreen.People)
        navigation.navigate(WearScreen.Nickname)

        navigation.openDmFromNotification("peer-a")

        assertEquals(WearScreen.Dm("peer-a"), navigation.screen)
        assertTrue(navigation.goBack())
        assertEquals(WearScreen.Chat, navigation.screen)
        assertFalse(navigation.canGoBack)
    }

    @Test
    fun `dm opened from people also goes back to main chat`() {
        val navigation = WearNavigationState()
        navigation.navigate(WearScreen.People)
        navigation.navigate(WearScreen.Dm("peer-a"))

        assertTrue(navigation.goBack())

        assertEquals(WearScreen.Chat, navigation.screen)
        assertFalse(navigation.canGoBack)
    }

    @Test
    fun `dm text input returns to dm before main chat`() {
        val navigation = WearNavigationState()
        navigation.navigate(WearScreen.People)
        navigation.navigate(WearScreen.Dm("peer-a"))
        navigation.navigate(WearScreen.TextInput("peer-a"))

        assertTrue(navigation.goBack())
        assertEquals(WearScreen.Dm("peer-a"), navigation.screen)

        assertTrue(navigation.goBack())
        assertEquals(WearScreen.Chat, navigation.screen)
        assertFalse(navigation.canGoBack)
    }

    @Test
    fun `normal app launch resets an open dm to main chat`() {
        val navigation = WearNavigationState()
        navigation.openDmFromNotification("peer-a")

        navigation.openChat()

        assertEquals(WearScreen.Chat, navigation.screen)
        assertFalse(navigation.canGoBack)
    }

    @Test
    fun `user detail and verification return through dm before main chat`() {
        val navigation = WearNavigationState()
        navigation.openDmFromNotification("peer-a")
        navigation.navigate(WearScreen.UserDetail("peer-a"))
        navigation.navigate(WearScreen.Verification("peer-a"))

        assertTrue(navigation.goBack())
        assertEquals(WearScreen.UserDetail("peer-a"), navigation.screen)

        assertTrue(navigation.goBack())
        assertEquals(WearScreen.Dm("peer-a"), navigation.screen)

        assertTrue(navigation.goBack())
        assertEquals(WearScreen.Chat, navigation.screen)
    }

    @Test
    fun `navigation state survives activity recreation`() {
        val navigation = WearNavigationState()
        navigation.openDmFromNotification("peer-a")
        navigation.navigate(WearScreen.UserDetail("peer-a"))
        navigation.navigate(WearScreen.Verification("peer-a"))

        val restored = WearNavigationState.restore(navigation.toSavedStateValues())

        requireNotNull(restored)
        assertEquals(WearScreen.Verification("peer-a"), restored.screen)
        assertTrue(restored.goBack())
        assertEquals(WearScreen.UserDetail("peer-a"), restored.screen)
        assertTrue(restored.goBack())
        assertEquals(WearScreen.Dm("peer-a"), restored.screen)
        assertTrue(restored.goBack())
        assertEquals(WearScreen.Chat, restored.screen)
    }

    @Test
    fun `unhandled notification launch survives activity recreation`() {
        val request = WearLaunchRequest(
            id = 42L,
            target = WearLaunchTarget.Dm("peer-a")
        )

        val restored = restoreWearLaunchRequest(request.toSavedStateValues())

        assertEquals(request, restored)
    }
}
