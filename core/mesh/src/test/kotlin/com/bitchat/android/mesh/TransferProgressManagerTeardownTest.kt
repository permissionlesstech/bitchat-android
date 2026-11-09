package com.bitchat.android.mesh

import com.bitchat.android.identity.scope.IdentityTeardown
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransferProgressManagerTeardownTest {
    @Test
    fun `an ended identity's progress manager emits nothing more`() = runTest {
        val teardown = IdentityTeardown()
        val manager = TransferProgressManager(UnconfinedTestDispatcher(testScheduler), teardown)
        val seen = mutableListOf<String>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.events.collect { seen += it.transferId }
        }

        manager.start("before", 2)
        teardown.runAll()
        manager.start("after", 2)

        assertEquals(listOf("before"), seen)
        collector.cancel()
    }
}
