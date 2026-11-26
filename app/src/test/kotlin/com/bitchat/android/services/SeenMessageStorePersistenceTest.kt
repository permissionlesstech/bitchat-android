package com.bitchat.android.services

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.identity.SecureIdentityStateManager
import com.google.gson.Gson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SeenMessageStorePersistenceTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val gson = Gson()

    @Test
    fun `sustained additive burst commits without starvation and without serialization churn`() {
        val dispatcher = StandardTestDispatcher()
        val testScope = TestScope(dispatcher)
        val serializeCount = AtomicInteger(0)
        var injectConcurrentAdd = false
        lateinit var store: SeenMessageStore
        store = SeenMessageStore(
            context = app,
            persistScope = testScope,
            persistDelayMs = 500L,
            payloadSerializer = { payload ->
                val call = serializeCount.incrementAndGet()
                val json = gson.toJson(payload)
                // Simulate a concurrent additive mutation arriving mid-serialization:
                // the writer must still commit this snapshot once without discarding/spinning,
                // then persist the newly arrived ID in the next bounded batch.
                if (injectConcurrentAdd && call == 1) {
                    injectConcurrentAdd = false
                    store.markDelivered("msg-during-serialize")
                }
                json
            }
        )

        try {
            store.clear()
            serializeCount.set(0)
            injectConcurrentAdd = true

            // Continuous mutations every 200ms (< 500ms window) must STILL flush within 500ms
            store.markReadReceiptSent("receipt-early")
            testScope.advanceTimeBy(200L); testScope.runCurrent()
            store.markDelivered("msg-200")
            testScope.advanceTimeBy(200L); testScope.runCurrent()
            store.markDelivered("msg-400")
            testScope.advanceTimeBy(150L); testScope.runCurrent() // 550ms elapsed -> first 500ms batch runs

            assertEquals(
                "mid-serialization additive write must not cause discard/reserialize spin churn",
                1,
                serializeCount.get()
            )
            val firstJson = SecureIdentityStateManager(app).getSecureValue("seen_message_store_v1")
            assertNotNull("early mutation must persist despite continuous updates", firstJson)
            assertTrue(firstJson!!.contains("receipt-early"))
            assertTrue(firstJson.contains("msg-200"))
            assertTrue(firstJson.contains("msg-400"))

            // Next bounded 500ms follow-up batch persists the ID added during the first serialization
            testScope.advanceTimeBy(550L); testScope.runCurrent()
            assertEquals(2, serializeCount.get())
            val secondJson = SecureIdentityStateManager(app).getSecureValue("seen_message_store_v1")!!
            assertTrue(secondJson.contains("msg-during-serialize"))
        } finally {
            store.clear()
        }
    }

    @Test
    fun `production writer aborts stale snapshot when remove or clear races with serialization`() {
        val dispatcher = StandardTestDispatcher()
        val testScope = TestScope(dispatcher)
        var onSerialize: (() -> Unit)? = null
        val store = SeenMessageStore(
            context = app,
            persistScope = testScope,
            persistDelayMs = 500L,
            payloadSerializer = { payload ->
                val json = gson.toJson(payload)
                onSerialize?.invoke()
                json
            }
        )

        try {
            store.clear()

            // Race 1: remove() runs while the scheduled production flush is serializing
            store.markDelivered("msg-removed-mid-write")
            onSerialize = {
                onSerialize = null
                store.remove(setOf("msg-removed-mid-write"))
            }
            testScope.advanceTimeBy(550L); testScope.runCurrent()

            val postRemoveJson = SecureIdentityStateManager(app).getSecureValue("seen_message_store_v1")!!
            assertFalse(
                "production writer must not resurrect an ID removed during serialization",
                postRemoveJson.contains("msg-removed-mid-write")
            )

            // Race 2: panic clear() runs synchronously while a scheduled production flush is serializing
            store.markDelivered("msg-cleared-mid-write")
            onSerialize = {
                onSerialize = null
                store.clear()
            }
            testScope.advanceTimeBy(550L); testScope.runCurrent()

            val postClearJson = SecureIdentityStateManager(app).getSecureValue("seen_message_store_v1")!!
            assertFalse(
                "production writer must not resurrect an ID wiped by panic clear during serialization",
                postClearJson.contains("msg-cleared-mid-write")
            )
        } finally {
            onSerialize = null
            store.clear()
        }
    }
}
