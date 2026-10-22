package com.bitchat.android.geohash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLocationAccessPolicyTest {
    @Test
    fun `live location is disabled by default`() {
        val policy = LiveLocationAccessPolicy()

        assertFalse(policy.isEnabled)
        assertNull(policy.captureToken())
    }

    @Test
    fun `disable and re-enable never revives old work`() {
        val policy = LiveLocationAccessPolicy(initialEnabled = true)
        val oldToken = requireNotNull(policy.captureToken())

        policy.update(false)
        assertFalse(policy.accepts(oldToken))

        policy.update(true)
        val newToken = requireNotNull(policy.captureToken())

        assertFalse(policy.accepts(oldToken))
        assertTrue(policy.accepts(newToken))
    }

    @Test
    fun `invalidation prevents a queued action from running`() {
        val policy = LiveLocationAccessPolicy(initialEnabled = true)
        val token = requireNotNull(policy.captureToken())
        var ran = false

        policy.invalidate()
        val accepted = policy.runIfAllowed(token) { ran = true }

        assertFalse(accepted)
        assertFalse(ran)
        assertNull(policy.captureToken())

        policy.resumeAccess()
        assertTrue(policy.captureToken() != null)
    }
}
