package com.bitchat.android.di

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.MainActivity
import com.bitchat.android.identity.scope.MeshIdentityEntryPoint
import com.bitchat.android.identity.scope.entryPoint
import com.bitchat.android.navigation.OnboardedApp
import com.bitchat.android.navigation.awaitChat
import com.bitchat.android.testhook.MeshTestAccess
import org.junit.Assert.assertSame
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An identity-scoped type reached through the process graph's bridge and straight from the
 * identity component is one instance, so a consumer on either path sees the same state.
 *
 * Reads the real application graph; it sends nothing and wipes nothing.
 */
@RunWith(AndroidJUnit4::class)
class IdentityScopeWiringTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic
        @BeforeClass
        fun completeOnboarding() = OnboardedApp.prepare()
    }

    private fun graph() = MeshTestAccess.of(rule.activity.applicationContext)

    private fun identityEntry(): MeshIdentityEntryPoint =
        graph().identityHolder().entryPoint()

    @Test
    fun theBridgeAndTheScopeGiveTheSameInstance() {
        rule.awaitChat()
        assertSame(graph().voiceWaveformCache(), identityEntry().voiceWaveformCache())
    }
}
