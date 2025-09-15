package com.bitchat.android.ui.media

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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

    Box(
        modifier = modifier
            .size(32.dp)
            .background(color = Color.Gray.copy(alpha = 0.5f), shape = CircleShape)
            .clickable { imagePicker.launch("image/*") },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = "Pick image",
            tint = Color.Black,
            modifier = Modifier.size(20.dp)
        )
    }
}

