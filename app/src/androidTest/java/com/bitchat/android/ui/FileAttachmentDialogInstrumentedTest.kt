package com.bitchat.android.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.ui.media.FileAttachment
import com.bitchat.android.ui.media.FileViewerDialog
import com.bitchat.android.ui.theme.BitchatTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FileAttachmentDialogInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun saveExportsBytesAndCancellationKeepsTheDialogOpen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val source = File(app.filesDir, "synthetic-export.txt").apply { writeText("Synthetic file export") }
        val destination = File(app.filesDir, "synthetic-destination.txt").apply { writeText("") }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", destination)
        var cancel = true
        var dismissed by mutableStateOf(false)
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == Intent.ACTION_CREATE_DOCUMENT) {
                    assertEquals("text/plain", intent.type)
                    if (cancel) Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    else Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri))
                } else null
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.setContent {
                BitchatTheme {
                    if (!dismissed) FileViewerDialog(FileAttachment.fromPath(source.path)!!) { dismissed = true }
                }
            }
            compose.onNodeWithText("Save").performClick()
            compose.onNodeWithText(source.name).assertExists()
            assertFalse(dismissed)
            compose.runOnIdle { cancel = false }
            compose.onNodeWithText("Save").performClick()
            compose.waitUntil(5_000) { dismissed }
            assertArrayEquals(source.readBytes(), destination.readBytes())
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }
}
