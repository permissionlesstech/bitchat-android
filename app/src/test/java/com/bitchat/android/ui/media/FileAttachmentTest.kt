package com.bitchat.android.ui.media

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileAttachmentTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `export copies the actual bytes to the selected destination`() {
        val file = temporary.newFile("synthetic.txt").apply { writeText("Synthetic attachment") }
        val attachment = FileAttachment.fromPath(file.path)!!
        val output = ByteArrayOutputStream()
        attachment.copyTo(output)
        assertArrayEquals(file.readBytes(), output.toByteArray())
        assertEquals(file.length(), attachment.fileSize)
    }

    @Test(expected = IOException::class)
    fun `failed export propagates failure rather than reporting success`() {
        val file = temporary.newFile("synthetic.txt").apply { writeText("Synthetic attachment") }
        FileAttachment.fromPath(file.path)!!.copyTo(object : OutputStream() {
            override fun write(value: Int) { throw IOException("Synthetic failure") }
        })
    }

    @Test fun `missing file does not produce an attachment`() {
        assertNull(FileAttachment.fromPath(temporary.root.resolve("missing.txt").path))
    }
}
