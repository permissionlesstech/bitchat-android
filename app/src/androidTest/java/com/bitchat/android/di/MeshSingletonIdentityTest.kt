package com.bitchat.android.di

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.MainActivity
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.navigation.OnboardedApp
import com.bitchat.android.navigation.awaitChat
import com.bitchat.android.testhook.ChatSessionTestAccess
import com.bitchat.android.testhook.MeshTestAccess
import com.bitchat.android.ui.PanicClear
import org.junit.Assert.assertSame
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app graph builds each shared mesh store once, and the mesh and the panic wipe hold that one.
 * If the mesh wrote to one `AppStateStore` and the screens read another, messages and peers would
 * silently vanish; if the panic wipe reset a second `SpamFilter` or favourites store, the real one
 * would survive it.
 *
 * Reads the real application graph through a debug entry point; it sends nothing and wipes nothing.
 */
@RunWith(AndroidJUnit4::class)
class MeshSingletonIdentityTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic
        @BeforeClass
        fun completeOnboarding() = OnboardedApp.prepare()
    }

    private fun graph() = MeshTestAccess.of(rule.activity.applicationContext)

    private fun mesh(): BluetoothMeshService =
        graph().meshServiceHolder().getOrCreate(rule.activity.applicationContext)

    private fun panicClear(): PanicClear {
        lateinit var panicClear: PanicClear
        rule.runOnUiThread { panicClear = ChatSessionTestAccess.of(rule.activity).panicClear() }
        return panicClear
    }

    @Test
    fun eachStoreIsBuiltOnce() {
        val first = graph()
        val second = graph()
        assertSame(first.appStateStore(), second.appStateStore())
        assertSame(first.contactDirectory(), second.contactDirectory())
        assertSame(first.transportBridgeService(), second.transportBridgeService())
        assertSame(first.favorites(), second.favorites())
        assertSame(first.spamFilter(), second.spamFilter())
        assertSame(first.seenMessageStore(), second.seenMessageStore())
    }

    @Test
    fun theMeshHoldsTheGraphsStores() {
        rule.awaitChat()
        val mesh = mesh()

        assertSame(graph().appStateStore(), mesh.appStateStore)
        assertSame(graph().contactDirectory(), mesh.contactDirectory)
        assertSame(graph().transportBridgeService(), mesh.transportBridgeService)
        assertSame(graph().favorites(), mesh.favoritesPersistenceService.get())
        assertSame(graph().spamFilter(), mesh.spamFilter)
        assertSame(graph().seenMessageStore(), mesh.seenMessageStore.get())
    }

    @Test
    fun thePanicWipeResetsTheGraphsStores() {
        rule.awaitChat()
        val panicClear = panicClear()

        assertSame(graph().appStateStore(), panicClear.appStateStore)
        assertSame(graph().spamFilter(), panicClear.spamFilter)
        assertSame(graph().favorites(), panicClear.favorites.get())
        assertSame(graph().seenMessageStore(), panicClear.seenMessageStoreLazy.get())
    }
}
