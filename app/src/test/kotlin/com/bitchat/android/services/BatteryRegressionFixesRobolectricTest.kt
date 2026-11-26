package com.bitchat.android.services

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.nostr.NostrBackgroundEventProcessor
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.ui.DataManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class BatteryRegressionFixesRobolectricTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `SeenMessageStore stale debounced flush cannot resurrect IDs after remove or clear`() {
        val store = SeenMessageStore.getInstance(app)
        store.clear()

        store.markDelivered("msg-alpha")
        store.markReadLocally("msg-beta")
        // Capture generation right after marking
        val genField = SeenMessageStore::class.java.getDeclaredField("mutationGeneration")
            .apply { isAccessible = true }
        val staleGen = genField.getLong(store)

        // Panic clear wipes memory and disk synchronously
        store.clear()
        // Simulate an in-flight debounced flush waking up with the pre-clear generation
        store.persistIfCurrent(staleGen)

        // Re-load from disk into the same store and verify wiped IDs did NOT come back
        val loadMethod = SeenMessageStore::class.java.getDeclaredMethod("load")
            .apply { isAccessible = true }
        loadMethod.invoke(store)

        assertFalse(store.hasDelivered("msg-alpha"))
        assertFalse(store.hasBeenReadLocally("msg-beta"))

        // Also verify remove() invalidates pending debounced flush generation
        store.markDelivered("msg-gamma")
        val staleAfterGamma = genField.getLong(store)
        store.remove(setOf("msg-gamma"))
        store.persistIfCurrent(staleAfterGamma)
        loadMethod.invoke(store)
        assertFalse(store.hasDelivered("msg-gamma"))
    }

    @Test
    fun `NostrIdentityBridge concurrent lookup and panic wipe keep cache and disk consistent`() {
        NostrIdentityBridge.clearAllAssociations(app)
        val pool = Executors.newFixedThreadPool(4)
        try {
            repeat(10) {
                val start = CountDownLatch(1)
                val done = CountDownLatch(2)
                pool.submit {
                    start.await()
                    NostrIdentityBridge.getCurrentNostrIdentity(app)
                    done.countDown()
                }
                pool.submit {
                    start.await()
                    NostrIdentityBridge.clearAllAssociations(app)
                    done.countDown()
                }
                start.countDown()
                assertTrue(done.await(5, TimeUnit.SECONDS))

                // After concurrent race settles, cached identity (if any) must match disk!
                val cachedField = NostrIdentityBridge::class.java.getDeclaredField("cachedCurrentIdentity")
                    .apply { isAccessible = true }
                val cached = cachedField.get(NostrIdentityBridge)
                val persisted = NostrIdentityBridge.getCurrentNostrIdentity(app)
                assertNotNull(persisted)
                if (cached != null) {
                    assertEquals(cached, persisted)
                }
            }
        } finally {
            pool.shutdownNow()
            NostrIdentityBridge.clearAllAssociations(app)
        }
    }

    @Test
    @Config(sdk = [29])
    fun `DataManager clearAllData invalidates NostrBackgroundEventProcessor blocklists on API 29`() {
        val externalDataManager = DataManager(app)
        externalDataManager.clearAllData()
        val blockedPubkey = "a".repeat(64)
        externalDataManager.addGeohashBlockedUser(blockedPubkey)

        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val processor = NostrBackgroundEventProcessor(app, scope)
            val dmField = NostrBackgroundEventProcessor::class.java.getDeclaredField("dataManager")
                .apply { isAccessible = true }
            val processorDataManager = dmField.get(processor) as DataManager
            assertTrue(processorDataManager.isGeohashUserBlocked(blockedPubkey))

            // Simulate panic wipe through DataManager.clearAllData() (calls Editor.clear(),
            // which omits OnSharedPreferenceChangeListener callbacks on Android API 26-29)
            externalDataManager.clearAllData()

            val refreshMethod = NostrBackgroundEventProcessor::class.java.getDeclaredMethod("refreshBlockLists")
                .apply { isAccessible = true }
            refreshMethod.invoke(processor)

            assertFalse(
                "post-wipe processor must clear pre-wipe geohash blocklist on API 29",
                processorDataManager.isGeohashUserBlocked(blockedPubkey)
            )
        } finally {
            scope.cancel()
            externalDataManager.clearAllData()
        }
    }
}
