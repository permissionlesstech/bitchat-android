package com.bitchat.android.ui.media

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.bitchat.android.features.media.ImageUtils
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImagePickerButton(
    modifier: Modifier = Modifier,
    onImageReady: (String) -> Unit
) {
    val context = LocalContext.current
    var capturedImagePath by remember { mutableStateOf<String?>(null) }
    
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            val outPath = ImageUtils.downscaleAndSaveToAppFiles(context, uri)
            if (!outPath.isNullOrBlank()) onImageReady(outPath)
        }
    }
    
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val path = capturedImagePath
        if (success && !path.isNullOrBlank()) {
            // Downscale + correct orientation, then send; delete original
            val outPath = com.bitchat.android.features.media.ImageUtils.downscalePathAndSaveToAppFiles(context, path)
            if (!outPath.isNullOrBlank()) {
                onImageReady(outPath)
            }
            runCatching { File(path).delete() }
        } else {
            // Cleanup on cancel/failure
            path?.let { runCatching { File(it).delete() } }
        }
        capturedImagePath = null
    }

    fun startCameraCapture() {
        try {
            val dir = File(context.filesDir, "images/outgoing").apply { mkdirs() }
            val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file
            )
            capturedImagePath = file.absolutePath
            takePictureLauncher.launch(uri)
        } catch (_: Exception) {
            // Ignore errors; no-op
        }
    }

    Box(
        modifier = modifier
            .size(32.dp)
            .combinedClickable(
                onClick = { imagePicker.launch("image/*") },
                onLongClick = { startCameraCapture() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.PhotoCamera,
            contentDescription = stringResource(com.bitchat.android.R.string.pick_image),
            tint = Color.Gray,
            modifier = Modifier.size(20.dp)
        )
    }

    // No custom preview: native camera UI handles confirmation
}
