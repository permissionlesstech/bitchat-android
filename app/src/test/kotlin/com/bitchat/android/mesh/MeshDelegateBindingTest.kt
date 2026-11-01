package com.bitchat.android.mesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock

class MeshDelegateBindingTest {

    private val chatDelegate: MeshDelegate = mock()

    /** A service whose `delegate` property keeps what was assigned, like the real one. */
    private fun fakeService(): MeshService {
        var held: MeshDelegate? = null
        return mock {
            on { delegate } doAnswer { held }
            on { delegate = anyOrNull() } doAnswer { held = it.getArgument(0); Unit }
        }
    }

    @Test
    fun resumeAttachThenOnboardingThenPause_detaches() {
        val service = fakeService()
        val binding = MeshDelegateBinding(service, chatDelegate)

        // onResume attaches while the app is ready...
        binding.attach()
        assertTrue(binding.attached)
        assertSame(chatDelegate, service.delegate)

        // ...the resume check then sends the app to onboarding, and onPause
        // must still hand notifications back to the service.
        binding.detachIfAttached()

        assertFalse(binding.attached)
        assertNull(service.delegate)
    }

    @Test
    fun detachWhenNeverAttached_leavesTheServiceAlone() {
        val service = fakeService()
        val binding = MeshDelegateBinding(service, chatDelegate)

        binding.detachIfAttached()

        assertFalse(binding.attached)
        assertNull(service.delegate)
    }
}
