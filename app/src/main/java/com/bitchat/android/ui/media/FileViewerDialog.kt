package com.bitchat.android.ui.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.bitchat.android.features.file.FileUtils
import com.bitchat.android.model.BitchatFilePacket
import kotlinx.coroutines.launch
import java.io.File

/**
 * Dialog for handling received file messages in modern chat style
 */
@Composable
fun FileViewerDialog(
    packet: BitchatFilePacket,
    onDismiss: () -> Unit,
    onSaveToDevice: (ByteArray, String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // File received header
                Text(
                    text = "📎 File Received",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                // File info
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = "📄 ${packet.fileName}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                    )
                    Text(
                        text = "📏 Size: ${FileUtils.formatFileSize(packet.fileSize)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "🏷️ Type: ${packet.mimeType}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Open/Save button
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                // Try to save to Downloads first
                                try {
                                    onSaveToDevice(packet.content, packet.fileName)
                                    onDismiss()
                                } catch (e: Exception) {
                                    // If save fails, try to open directly
                                    tryOpenFile(context, packet)
                                    onDismiss()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("📂 Open / Save")
                    }

                    // Dismiss button
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Text("❌ Close")
                    }
                }
            }
        }
    }
}

/**
 * Attempts to open a file using system viewers or save to device
 */
private fun tryOpenFile(context: Context, packet: BitchatFilePacket) {
    try {
        // First try to save to temp file and open
        val tempFile = File.createTempFile("bitchat_", ".${packet.fileName.substringAfterLast(".")}", context.cacheDir)
        tempFile.writeBytes(packet.content)
        tempFile.deleteOnExit()

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            tempFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, packet.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // No app can handle this file type - just show a message
            // In a real app, you'd show a toast or snackbar
        }
    } catch (e: Exception) {
        // Handle any errors gracefully
    }
}
