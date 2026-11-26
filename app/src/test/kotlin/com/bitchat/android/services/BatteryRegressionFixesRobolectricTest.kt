package com.bitchat.android.services

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.nostr.NostrBackgroundEventProcessor
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.ui.DataManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BatteryRegressionFixesRobolectricTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `SeenMessageStore stale flush cannot resurrect IDs after remove or clear and burst mutations do not starve persistence`() {
        val dispatcher = StandardTestDispatcher()
        val testScope = TestScope(dispatcher)
        val store = SeenMessageStore.getInstance(app)
        val scopeField = SeenMessageStore::class.java.getDeclaredField("persistScope")
            .apply { isAccessible = true }
        val originalScope = scopeField.get(store)
        scopeField.set(store, testScope)

        try {
            store.clear()

            // Continuous mutations every 200ms (< 500ms window) must STILL flush within ~500ms (no starvation)
            store.markReadReceiptSent("receipt-early")
            testScope.advanceTimeBy(200L); testScope.runCurrent()
            store.markDelivered("msg-200")
            testScope.advanceTimeBy(200L); testScope.runCurrent()
            store.markDelivered("msg-400")
            testScope.advanceTimeBy(200L); testScope.runCurrent() // 600ms elapsed -> first 500ms batch MUST have persisted

            val rawJson = SecureIdentityStateManager(app).getSecureValue("seen_message_store_v1")
            assertNotNull("early mutation must persist despite continuous updates", rawJson)
            assertTrue("early sent-receipt ID must be persisted during burst", rawJson!!.contains("receipt-early"))

            // Capture generation before panic clear
            val genField = SeenMessageStore::class.java.getDeclaredField("mutationGeneration")
                .apply { isAccessible = true }
            val staleGen = genField.getLong(store)

            store.clear()
            // Simulate an in-flight stale flush waking up with pre-clear generation
            store.persistIfCurrent(staleGen)

            val postClearJson = SecureIdentityStateManager(app).getSecureValue("seen_message_store_v1")!!
            assertFalse("cleared IDs must not reappear in storage", postClearJson.contains("receipt-early"))
            assertFalse("cleared IDs must not reappear in storage", postClearJson.contains("msg-200"))

            // Verify remove() persists asynchronously on persistScope and rejects stale generation
            store.markDelivered("msg-gamma")
            val staleAfterGamma = genField.getLong(store)
            store.remove(setOf("msg-gamma"))
            testScope.runCurrent()
            store.persistIfCurrent(staleAfterGamma)

            val postRemoveJson = SecureIdentityStateManager(app).getSecureValue("seen_message_store_v1")!!
            assertFalse("removed ID must stay deleted in storage", postRemoveJson.contains("msg-gamma"))
        } finally {
            scopeField.set(store, originalScope)
            store.clear()
        }
    }

    @Test
    fun `NostrIdentityBridge wipe clears independent storage and re-derives a fresh geohash identity`() {
        NostrIdentityBridge.clearAllAssociations(app)
        try {
            val secure = SecureIdentityStateManager(app)
            val beforeAccount = NostrIdentityBridge.getCurrentNostrIdentity(app)!!
            val beforeGeo = NostrIdentityBridge.deriveIdentity("u4pruydq", app)

            // Verify persisted directly in SecureIdentityStateManager (independent of cache)
            assertEquals(beforeAccount.privateKeyHex, secure.getSecureValue("nostr_private_key"))
            assertNotNull(secure.getSecureValue("nostr_device_seed"))

            assertTrue(NostrIdentityBridge.clearAllAssociations(app))

            // Storage must be wiped immediately and cache must be empty
            assertNull(secure.getSecureValue("nostr_private_key"))
            assertNull(secure.getSecureValue("nostr_device_seed"))

            val afterAccount = NostrIdentityBridge.getCurrentNostrIdentity(app)!!
            val afterGeo = NostrIdentityBridge.deriveIdentity("u4pruydq", app)
            assertNotEquals(beforeAccount.privateKeyHex, afterAccount.privateKeyHex)
            assertNotEquals(beforeGeo.privateKeyHex, afterGeo.privateKeyHex)
            assertEquals(afterAccount.privateKeyHex, secure.getSecureValue("nostr_private_key"))
        } finally {
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
