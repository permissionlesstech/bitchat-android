package com.bitchat.android.ui.media

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bitchat.android.features.media.ImageUtils

@Composable
fun ImagePickerButton(
    modifier: Modifier = Modifier,
    onImageReady: (String) -> Unit
) {
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            val outPath = ImageUtils.downscaleAndSaveToAppFiles(context, uri)
            if (!outPath.isNullOrBlank()) onImageReady(outPath)
        }
    }

    IconButton(
        onClick = { imagePicker.launch("image/*") },
        modifier = modifier.size(32.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Photo,
            contentDescription = "Pick image",
            tint = Color.Gray,
            modifier = Modifier.size(20.dp)
        )
    }
}

