package com.bitchat.android.ui.media

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bitchat.android.R
import com.bitchat.android.features.media.ImageUtils

@Composable
fun CameraCapturePreview(
    imagePath: String,
    onUse: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(color = Color.Black) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Image content
                val bmp = remember(imagePath) { ImageUtils.loadBitmapWithExifOrientation(imagePath) }
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = stringResource(R.string.cd_image),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = stringResource(R.string.image_unavailable), color = Color.White)
                    }
                }

                // Controls overlay (bottom)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ControlPill(text = stringResource(R.string.cd_cancel), onClick = onCancel)
                    Spacer(Modifier.width(8.dp))
                    ControlPill(text = stringResource(R.string.retry), onClick = onRetry)
                    Spacer(Modifier.width(8.dp))
                    ControlPill(text = stringResource(R.string.use_photo), onClick = onUse, highlighted = true)
                }
            }
        }
    }
}

@Composable
private fun ControlPill(text: String, onClick: () -> Unit, highlighted: Boolean = false) {
    val bg = if (highlighted) Color(0xFF00C851) else Color(0x66000000)
    val fg = if (highlighted) Color.Black else Color.White
    Box(
        modifier = Modifier
            .height(40.dp)
            .background(bg, CircleShape)
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = fg,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 16.sp
        )
    }
}
