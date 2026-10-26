package com.bitchat.watch.testhook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearUiDemoSeederTest {

    @Test
    fun `auto generated nickname is disposable`() {
        assertTrue(WearUiDemoSeeder.isDisposableIdentity("watch-a1b2", "a1b2c3d4e5f60718", false))
    }

    @Test
    fun `a nickname chosen by a person is refused`() {
        assertFalse(WearUiDemoSeeder.isDisposableIdentity("sam", "a1b2c3d4e5f60718", false))
        assertFalse(WearUiDemoSeeder.isDisposableIdentity("watch-ffff", "a1b2c3d4e5f60718", false))
    }

    @Test
    fun `demo nickname passes only after this seeder set it`() {
        val demo = WearUiDemoSeeder.DEMO_SELF_NICKNAME
        assertFalse(WearUiDemoSeeder.isDisposableIdentity(demo, "a1b2c3d4e5f60718", false))
        assertTrue(WearUiDemoSeeder.isDisposableIdentity(demo, "a1b2c3d4e5f60718", true))
    }
}
