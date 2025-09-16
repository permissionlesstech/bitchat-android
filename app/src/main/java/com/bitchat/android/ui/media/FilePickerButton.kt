package com.bitchat.android.ui.media

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Attachment
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bitchat.android.features.file.FileUtils

@Composable
fun FilePickerButton(
    modifier: Modifier = Modifier,
    onFileReady: (String) -> Unit
) {
    val context = LocalContext.current

    // Use SAF - supports all file types
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // Persist temporary read permission so we can copy
            try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            val path = FileUtils.copyFileForSending(context, uri)
            if (!path.isNullOrBlank()) onFileReady(path)
        }
    }

    Box(
        modifier = modifier
            .size(32.dp)
            .background(color = Color.Gray.copy(alpha = 0.5f), shape = CircleShape)
            .clickable {
                // Allow any MIME type; user asked to choose between image or file at higher level UI
                filePicker.launch(arrayOf("*/*"))
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Attachment,
            contentDescription = "Pick file",
            tint = Color.Black,
            modifier = Modifier.size(20.dp)
        )
    }
}
