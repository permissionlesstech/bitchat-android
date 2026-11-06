package com.bitchat.android.ui.media

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoCamera
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.bitchat.android.ui.theme.BitchatMotion
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.bitchat.android.features.media.ImageUtils
import com.bitchat.android.ui.ComposerActionSurface
import com.bitchat.android.ui.ComposerIconSize
import java.io.File

/**
 * Composer camera button. With [onVideo] set, a tap expands it into a Photo / Video choice;
 * without it (or on long-press) it keeps the original photo behavior: tap picks from the
 * gallery, long-press opens the camera.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImagePickerButton(
    modifier: Modifier = Modifier,
    onVideo: (() -> Unit)? = null,
    onImageReady: (String) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
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
        } catch (e: Exception) {
            android.util.Log.e("ImagePickerButton", "Camera capture failed", e)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCameraCapture()
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Shares the composer's button treatment so camera, microphone and send read as one set.
    Box {
        ComposerActionSurface(
            isActive = menuOpen,
            isPressed = isPressed,
            modifier = modifier.combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    if (onVideo != null) menuOpen = !menuOpen else imagePicker.launch("image/*")
                },
                onLongClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        startCameraCapture()
                    } else {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            )
        ) { tint ->
            Icon(
                imageVector = Icons.Filled.PhotoCamera,
                contentDescription = stringResource(
                    if (onVideo != null) com.bitchat.android.R.string.cd_pick_media else com.bitchat.android.R.string.pick_image
                ),
                tint = tint,
                modifier = Modifier.size(ComposerIconSize)
            )
        }
        if (onVideo != null) {
            MediaKindMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                onPhoto = {
                    menuOpen = false
                    imagePicker.launch("image/*")
                },
                onTakePhoto = {
                    menuOpen = false
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        startCameraCapture()
                    } else {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                onVideo = {
                    menuOpen = false
                    onVideo()
                }
            )
        }
    }

    // No custom preview: native camera UI handles confirmation
}

/** The camera button's expanded state: a small card rising above the composer. */
@Composable
private fun MediaKindMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    onVideo: () -> Unit
) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = expanded
    if (!visibility.currentState && !visibility.targetState) return
    val density = LocalDensity.current
    Popup(
        alignment = Alignment.BottomCenter,
        offset = with(density) { IntOffset(0, -(ComposerIconSize + 28.dp).roundToPx()) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        AnimatedVisibility(
            visibleState = visibility,
            enter = fadeIn(tween(BitchatMotion.STANDARD_MS)) +
                scaleIn(initialScale = 0.6f, transformOrigin = TransformOrigin(0.5f, 1f), animationSpec = tween(BitchatMotion.STANDARD_MS)),
            exit = fadeOut(tween(BitchatMotion.QUICK_MS)) +
                scaleOut(targetScale = 0.6f, transformOrigin = TransformOrigin(0.5f, 1f), animationSpec = tween(BitchatMotion.QUICK_MS))
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 6.dp,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MediaKindOption(
                        icon = Icons.Filled.Photo,
                        label = stringResource(com.bitchat.android.R.string.media_type_image),
                        onClick = onPhoto,
                        onLongClick = onTakePhoto
                    )
                    MediaKindOption(
                        icon = Icons.Filled.Videocam,
                        label = stringResource(com.bitchat.android.R.string.media_type_video),
                        onClick = onVideo,
                        onLongClick = null
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaKindOption(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
