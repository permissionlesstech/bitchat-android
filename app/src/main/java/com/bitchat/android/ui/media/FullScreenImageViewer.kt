package com.bitchat.android.ui.media

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bitchat.android.R
import java.io.File
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Check if a path is a URL (http/https)
 */
private fun isUrl(path: String): Boolean {
    return path.startsWith("http://", ignoreCase = true) || 
           path.startsWith("https://", ignoreCase = true)
}

/**
 * Fullscreen image viewer with swipe navigation between multiple images
 * @param imagePaths List of all image file paths in the current chat
 * @param initialIndex Starting index of the current image in the list
 * @param onClose Callback when the viewer should be dismissed
 */
// Backward compatibility for single image (can be removed after updating all callers)
@Composable
fun FullScreenImageViewer(path: String, onClose: () -> Unit) {
    FullScreenImageViewer(listOf(path), 0, onClose)
}

/**
 * Fullscreen image viewer with swipe navigation between multiple images
 * @param imagePaths List of all image file paths in the current chat
 * @param initialIndex Starting index of the current image in the list
 * @param onClose Callback when the viewer should be dismissed
 */
@Composable
fun FullScreenImageViewer(imagePaths: List<String>, initialIndex: Int = 0, onClose: () -> Unit) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = imagePaths::size)

    if (imagePaths.isEmpty()) {
        onClose()
        return
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Color.Black) {
            Box(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val currentPath = imagePaths[page]
                    
                    if (isUrl(currentPath)) {
                        // Load from URL using Coil
                        var isLoading by remember { mutableStateOf(true) }
                        var isError by remember { mutableStateOf(false) }
                        
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(currentPath)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = stringResource(R.string.cd_image_index_of, page + 1, imagePaths.size),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                                onLoading = { isLoading = true; isError = false },
                                onSuccess = { isLoading = false; isError = false },
                                onError = { isLoading = false; isError = true }
                            )
                            
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(48.dp),
                                    color = Color.White
                                )
                            }
                            
                            if (isError) {
                                Text(
                                    text = stringResource(R.string.image_load_failed),
                                    color = Color.White
                                )
                            }
                        }
                    } else {
                        // Load from local file
                        val bmp = remember(currentPath) { 
                            try { android.graphics.BitmapFactory.decodeFile(currentPath) } catch (_: Exception) { null } 
                        }

                        bmp?.let {
                            androidx.compose.foundation.Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = stringResource(R.string.cd_image_index_of, page + 1, imagePaths.size),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        } ?: run {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(text = stringResource(R.string.image_unavailable), color = Color.White)
                            }
                        }
                    }
                }

                // Image counter
                if (imagePaths.size > 1) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .align(Alignment.TopCenter)
                            .background(Color(0x66000000), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.image_counter, (pagerState.currentPage ?: 0) + 1, imagePaths.size),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .align(Alignment.TopEnd),
                    horizontalArrangement = Arrangement.End
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0x66000000), CircleShape)
                            .clickable { saveToDownloads(context, imagePaths[pagerState.currentPage].toString()) },
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Icon(Icons.Filled.Download, stringResource(R.string.cd_save_current_image), tint = Color.White)
                    }
                    Spacer(Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0x66000000), CircleShape)
                            .clickable { onClose() },
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Icon(Icons.Filled.Close, stringResource(R.string.cd_close), tint = Color.White)
                    }
                }
            }
        }
    }
}

private fun saveToDownloads(context: android.content.Context, path: String) {
    // Launch in background thread for URL downloads
    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
        runCatching {
            val isUrlPath = isUrl(path)
            
            // Determine filename and mime type
            val name = if (isUrlPath) {
                // Extract filename from URL, fallback to timestamp-based name
                val urlPath = path.substringBefore('?').substringBefore('#')
                val urlName = urlPath.substringAfterLast('/')
                if (urlName.isNotEmpty() && urlName.contains('.')) {
                    urlName
                } else {
                    "image_${System.currentTimeMillis()}.jpg"
                }
            } else {
                File(path).name
            }
            
            val mime = when {
                name.endsWith(".png", true) -> "image/png"
                name.endsWith(".webp", true) -> "image/webp"
                name.endsWith(".gif", true) -> "image/gif"
                name.endsWith(".avif", true) -> "image/avif"
                else -> "image/jpeg"
            }
            
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }
            
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    if (isUrlPath) {
                        // Download from URL
                        URL(path).openStream().use { it.copyTo(out) }
                    } else {
                        // Copy from local file
                        File(path).inputStream().use { it.copyTo(out) }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val v2 = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    context.contentResolver.update(uri, v2, null, null)
                }
                // Show toast message indicating the image has been saved (on main thread)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.toast_image_saved), Toast.LENGTH_SHORT).show()
                }
            }
        }.onFailure {
            // Show error toast on main thread
            withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.toast_failed_to_save_image), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
