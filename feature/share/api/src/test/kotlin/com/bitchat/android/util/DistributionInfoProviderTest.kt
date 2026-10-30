package com.bitchat.android.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class DistributionInfoProviderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `arm64-only APK is not universal`() {
        val apk = createApk("lib/arm64-v8a/libbitchat.so")

        assertFalse(DistributionInfoProvider.isUniversalApk(apk))
        assertEquals(
            ShareableApkVariant.ARM64,
            DistributionInfoProvider.shareableApkVariant(apk)
        )
    }

    @Test
    fun `APK containing every release ABI is universal`() {
        val apk = createApk(
            "lib/arm64-v8a/libbitchat.so",
            "lib/armeabi-v7a/libbitchat.so",
            "lib/x86_64/libbitchat.so",
            "lib/x86/libbitchat.so"
        )

        assertTrue(DistributionInfoProvider.isUniversalApk(apk))
        assertEquals(
            ShareableApkVariant.UNIVERSAL,
            DistributionInfoProvider.shareableApkVariant(apk)
        )
    }

    @Test
    fun `APK without native libraries is architecture independent`() {
        val apk = createApk("classes.dex")

        assertTrue(DistributionInfoProvider.isUniversalApk(apk))
        assertEquals(
            ShareableApkVariant.UNIVERSAL,
            DistributionInfoProvider.shareableApkVariant(apk)
        )
    }

    @Test
    fun `other architecture-only APK is not offered for sharing`() {
        val apk = createApk("lib/x86_64/libbitchat.so")

        assertNull(DistributionInfoProvider.shareableApkVariant(apk))
    }

    private fun createApk(vararg entries: String): File {
        val apk = temporaryFolder.newFile("test-${System.nanoTime()}.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            entries.forEach { path ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
        }
        return apk
    }
}
