package com.bitchat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.net.TorBridgePreferenceManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorBridgeSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { TorBridgePreferenceManager.init(context) }

    val enabled by TorBridgePreferenceManager.enabledFlow.collectAsState()
    val lines by TorBridgePreferenceManager.linesFlow.collectAsState()
    var input by remember { mutableStateOf("") }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colorScheme = MaterialTheme.colorScheme

    if (isPresented) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            modifier = modifier
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "tor bridges",
                            fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colorScheme.onSurface
                        )
                        Text(
                            text = "add custom bridges when direct connections are blocked.",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                item {
                    // Enable toggle
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = enabled,
                            onCheckedChange = { TorBridgePreferenceManager.setEnabled(context, it) }
                        )
                        Text(
                            text = if (enabled) "bridges enabled" else "bridges disabled",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "changes apply on next tor restart (toggle tor off/on)",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "format examples:",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = "• Bridge 192.0.2.66:443 FPR\n• Bridge obfs4 192.0.2.77:443 FPR cert=... iat-mode=0",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                item {
                    // Input row
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                            label = { Text("paste bridge line", fontFamily = FontFamily.Monospace) }
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledTonalIconButton(
                            onClick = {
                                val normalized = TorBridgePreferenceManager.normalizeBridgeLine(input)
                                if (!normalized.isNullOrBlank()) {
                                    TorBridgePreferenceManager.addLine(context, normalized)
                                    TorBridgePreferenceManager.setEnabled(context, true)
                                    input = ""
                                }
                            },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0xFFFF9500))
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "add bridge", tint = Color.Black)
                        }
                    }
                }

                if (lines.isNotEmpty()) {
                    item {
                        Text(
                            text = "configured bridges",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                    }
                    items(lines) { line ->
                        Surface(
                            color = colorScheme.surfaceVariant.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = line,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colorScheme.onSurface
                                )
                                IconButton(onClick = { TorBridgePreferenceManager.removeLine(context, line) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "remove", tint = Color(0xFFBF1A1A))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
