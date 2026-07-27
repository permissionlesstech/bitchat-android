package com.bitchat.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GitHubReleaseClientTest {

    @Test
    fun `parses universal apk and GitHub asset digest`() {
        val digest = "a".repeat(64)
        val release = GitHubReleaseClient.parseRelease(
            """
            {
              "tag_name": "v1.7.6",
              "body": "",
              "assets": [
                {
                  "name": "bitchat-android-universal.apk",
                  "browser_download_url": "https://example.test/bitchat.apk",
                  "size": 49283072,
                  "digest": "sha256:$digest"
                }
              ]
            }
            """.trimIndent()
        )

        requireNotNull(release)
        assertEquals("1.7.6", release.versionName)
        assertEquals(49_283_072L, release.universalApkSize)
        assertEquals(digest, release.universalApkSha256)
    }

    @Test
    fun `falls back to checksum in release notes`() {
        val digest = "b".repeat(64)
        val release = GitHubReleaseClient.parseRelease(
            """
            {
              "tag_name": "1.7.6",
              "body": "bitchat-android-universal.apk: $digest",
              "assets": [
                {
                  "name": "bitchat-android-universal.apk",
                  "browser_download_url": "https://example.test/bitchat.apk",
                  "size": 10
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(digest, requireNotNull(release).universalApkSha256)
    }

    @Test
    fun `rejects releases without a universal apk`() {
        val release = GitHubReleaseClient.parseRelease(
            """
            {
              "tag_name": "v1.7.6",
              "assets": [
                {
                  "name": "bitchat-android-arm64.apk",
                  "browser_download_url": "https://example.test/arm64.apk",
                  "size": 10
                }
              ]
            }
            """.trimIndent()
        )

        assertNull(release)
    }

    @Test
    fun `compares release versions`() {
        val release = GitHubReleaseClient.Release(
            tagName = "v1.7.6",
            versionName = "1.7.6",
            universalApkUrl = "https://example.test/bitchat.apk",
            universalApkSha256 = null,
            universalApkSize = 10,
            universalApkName = "bitchat-android-universal.apk"
        )

        assertTrue(GitHubReleaseClient.isNewerVersion("1.7.5", release))
        assertFalse(GitHubReleaseClient.isNewerVersion("1.7.6", release))
        assertFalse(GitHubReleaseClient.isNewerVersion("1.8.0", release))
        assertTrue(GitHubReleaseClient.isNewerVersion("1.7.4", "1.7.5"))
        assertFalse(GitHubReleaseClient.isNewerVersion("1.7.5", "1.7.4"))
    }
}
