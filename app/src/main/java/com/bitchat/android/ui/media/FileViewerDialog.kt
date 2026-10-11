package com.bitchat.android.ui.media

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.bitchat.android.R
import com.bitchat.android.features.file.FileUtils
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FileViewerDialog(attachment: FileAttachment, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failed by remember(attachment.path) { mutableStateOf(false) }
    var busy by remember(attachment.path) { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(attachment.mimeType),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                failed = false
                try {
                    withContext(Dispatchers.IO) {
                        requireNotNull(context.contentResolver.openOutputStream(uri)).use(attachment::copyTo)
                    }
                    onDismiss()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                } finally {
                    busy = false
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(attachment.fileName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.file_viewer_size, FileUtils.formatFileSize(attachment.fileSize)))
                Text(stringResource(R.string.file_viewer_type, attachment.mimeType))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failed) Text(stringResource(R.string.attachment_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                failed = false
                try { save.launch(attachment.fileName) } catch (_: Exception) { failed = true }
            }) { Text(stringResource(R.string.attachment_save)) }
        },
        dismissButton = {
            Row {
                TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        failed = false
                        try {
                            val uri = withContext(Dispatchers.IO) {
                                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(attachment.path))
                            }
                            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, attachment.mimeType)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                            onDismiss()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            failed = true
                        } finally { busy = false }
                    }
                }) { Text(stringResource(R.string.attachment_open)) }
                TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel_lower)) }
            }
        },
    )
}
