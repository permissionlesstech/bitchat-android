package com.bitchat.android.features.file

import android.os.Build
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The panic wipe only reports success when every stage confirms; media deletion
 * must therefore say when a directory survived.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class FileUtilsClearAllMediaTest {

    private val context = RuntimeEnvironment.getApplication()
    private val imagesDir = File(context.filesDir, "images")

    @After
    fun tearDown() {
        imagesDir.setWritable(true)
        imagesDir.deleteRecursively()
    }

    @Test
    fun `reports success once media directories are gone`() {
        val incoming = File(imagesDir, "incoming").apply { mkdirs() }
        File(incoming, "synthetic.jpg").writeBytes(byteArrayOf(1, 2, 3))

        assertTrue(FileUtils.clearAllMedia(context))
        assertFalse(incoming.exists())
    }

    @Test
    fun `reports failure when a media directory survives`() {
        val incoming = File(imagesDir, "incoming").apply { mkdirs() }
        File(incoming, "synthetic.jpg").writeBytes(byteArrayOf(1, 2, 3))
        // A read-only parent keeps its children from being unlinked.
        assumeTrue(imagesDir.setWritable(false) && !imagesDir.canWrite())

        assertFalse(FileUtils.clearAllMedia(context))
    }
}
