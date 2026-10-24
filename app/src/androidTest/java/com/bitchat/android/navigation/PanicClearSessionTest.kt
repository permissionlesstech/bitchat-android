package com.bitchat.android.navigation

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.MainActivity
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.testhook.ChatSessionTestAccess
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A panic clear replaces the mesh with one under a fresh identity, and the
 * chat session has to follow it: the session holds the mesh rather than
 * re-reading it, so this is the one place it changes.
 *
 * Wipes the app's data, which the clean emulator is for.
 */
@RunWith(AndroidJUnit4::class)
class PanicClearSessionTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic
        @BeforeClass
        fun completeOnboarding() = OnboardedApp.prepare()
    }

    @Test
    fun aPanicClearMovesTheSessionOntoTheRecreatedMesh() {
        rule.awaitChat()
        val before = sessionMesh()

        rule.runOnUiThread { session().panicClear().run() }
        rule.waitUntil(timeoutMillis = 15_000) { sessionMesh() !== before }

        val after = sessionMesh()
        val context = rule.activity.applicationContext
        assertSame(MeshServiceHolder.getUnifiedOrCreate(context), after)
        assertNotEquals(before.myPeerID, after.myPeerID)
        // The recreated mesh reports to the session's delegate.
        assertSame(rule.activity.chatMeshDelegate, after.delegate)
    }

    @Test
    fun theMeshReportsToTheSessionOnceTheAppHasStarted() {
        rule.awaitChat()

        val mesh = MeshServiceHolder.getUnifiedOrCreate(rule.activity.applicationContext)
        rule.waitUntil(timeoutMillis = 5_000) { mesh.delegate === rule.activity.chatMeshDelegate }
    }

    private fun session() = ChatSessionTestAccess.of(rule.activity)

    private fun sessionMesh(): MeshService {
        lateinit var mesh: MeshService
        rule.runOnUiThread { mesh = session().sessionMesh().unified }
        return mesh
    }
}
