package com.bitchat.android.ui.media

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Attachment
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.bitchat.android.R
import com.bitchat.android.features.file.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FilePickerButton(
    modifier: Modifier = Modifier,
    onFileReady: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Use SAF - supports all file types
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // Persist temporary read permission so we can copy
            try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            
            scope.launch {
                // Query file size on IO dispatcher to avoid blocking main thread
                val fileSize = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                                if (sizeIndex >= 0) cursor.getLong(sizeIndex) else null
                            } else null
                        }
                    } catch (_: Exception) { null }
                }

                // Check size limit: 50MB
                val maxLimit = com.bitchat.android.util.AppConstants.Media.MAX_FILE_SIZE_BYTES
                if (fileSize != null && fileSize > maxLimit) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "File is too large (max ${maxLimit / (1024 * 1024)}MB)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                // Perform copy operation on IO thread to prevent UI freezing / ANR
                val path = withContext(Dispatchers.IO) {
                    FileUtils.copyFileForSending(context, uri)
                }

                if (!path.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        onFileReady(path)
                    }
                }
            }
        }
    }

    IconButton(
        onClick = {
            // Allow any MIME type; user asked to choose between image or file at higher level UI
            filePicker.launch(arrayOf("*/*"))
        },
        modifier = modifier.size(32.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Attachment,
            contentDescription = stringResource(R.string.cd_pick_file),
            tint = Color.Gray,
            modifier = Modifier.size(20.dp).rotate(90f)
        )
    }
}
