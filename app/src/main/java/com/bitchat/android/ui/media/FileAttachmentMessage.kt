package com.bitchat.android.ui.media

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.bitchat.android.R
import com.bitchat.android.features.file.FileUtils
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.isFromSelf
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Display metadata; file contents are read only when an explicit open/save action needs them. */
data class FileAttachment(val path: String, val fileName: String, val fileSize: Long, val mimeType: String) {
    companion object {
        fun fromPath(path: String): FileAttachment? {
            val file = File(path)
            return if (file.isFile) FileAttachment(
                path, file.name, file.length(), FileUtils.getMimeTypeFromExtension(file.name),
            ) else null
        }
    }

    fun copyTo(output: OutputStream) {
        File(path).inputStream().use { it.copyTo(output) }
    }
}

@Composable
internal fun FileAttachmentMessage(
    message: BitchatMessage,
    currentUserNickname: String,
    myPeerID: String,
    timeFormatter: SimpleDateFormat,
    showSender: Boolean,
    bubbles: Boolean,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((BitchatMessage) -> Unit)?,
    onCancelTransfer: ((BitchatMessage) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val path = message.content.trim()
    val attachment by produceState<FileAttachment?>(null, path) {
        value = withContext(Dispatchers.IO) { runCatching { FileAttachment.fromPath(path) }.getOrNull() }
    }
    val progress = mediaTransferProgress(message, message.isFromSelf(currentUserNickname, myPeerID))
    MediaMessageLayout(
        message, currentUserNickname, myPeerID, timeFormatter,
        showSender, bubbles, onNicknameClick, onMessageLongPress, modifier,
    ) {
        Box {
            val file = attachment
            when {
                file == null -> Text(stringResource(R.string.file_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                progress != null -> FileSendingAnimation(fileName = file.fileName, progress = progress)
                else -> FileMessageItem(
                    attachment = file,
                    onLongPress = onMessageLongPress?.let { action -> { action(message) } },
                )
            }
            if (progress != null && onCancelTransfer != null) {
                Box(Modifier.align(Alignment.TopEnd)) {
                    CancelMediaTransferButton { onCancelTransfer(message) }
                }
            }
        }
    }
}
