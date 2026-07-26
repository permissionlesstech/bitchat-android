package com.bitchat.android.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.core.ui.component.button.CloseButton
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.nostr.NostrIdentity
import com.bitchat.android.nostr.NostrIdentityBridge

/**
 * Row component for displaying key information
 */
@Composable
private fun KeyDisplayRow(
    icon: ImageVector,
    label: String,
    value: String,
    showValue: Boolean,
    onToggleVisibility: () -> Unit,
    onCopy: () -> Unit,
    isSecret: Boolean = false
) {
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSecret) colorScheme.error else colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface
                )
            }
            
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isSecret) {
                    IconButton(onClick = onToggleVisibility, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = if (showValue) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showValue) "Hide" else "Show",
                            tint = colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "Copy",
                        tint = colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = if (showValue || !isSecret) value else value.take(8) + "..." + value.takeLast(4),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                ),
                color = colorScheme.onSurface.copy(alpha = 0.8f),
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

/**
 * Nostr Account Management Sheet
 * Allows users to view, export, and import their Nostr identity
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NostrAccountSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    onIdentityChanged: (() -> Unit)? = null,
    onNostrNameFound: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    
    // State
    var currentIdentity by remember { mutableStateOf<NostrIdentity?>(null) }
    var showNsec by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importNsecText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var showImportConfirmation by remember { mutableStateOf(false) }
    
    // State for profile name confirmation
    var showNameConfirmation by remember { mutableStateOf(false) }
    var foundNostrName by remember { mutableStateOf<String?>(null) }
    
    // Load identity on display
    LaunchedEffect(isPresented) {
        if (isPresented) {
            currentIdentity = NostrIdentityBridge.getCurrentNostrIdentity(context)
            showNsec = false
        }
    }
    
    val lazyListState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 0
        }
    }
    val topBarAlpha by animateFloatAsState(
        targetValue = if (isScrolled) 0.98f else 0f,
        label = "topBarAlpha"
    )

    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
    
    if (isPresented) {
        BitchatBottomSheet(
            modifier = modifier,
            onDismissRequest = onDismiss,
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 80.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Header
                    item(key = "header") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Nostr Account",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 24.sp,
                                    letterSpacing = 1.sp
                                ),
                                color = colorScheme.onBackground
                            )
                            Text(
                                text = "Your decentralized identity",
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colorScheme.onBackground.copy(alpha = 0.6f)
                            )
                        }
                    }

                    // Current Identity Display
                    item(key = "identity") {
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Text(
                                text = "YOUR KEYS",
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onBackground.copy(alpha = 0.5f),
                                letterSpacing = 0.5.sp,
                                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                            )
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = colorScheme.surface,
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column {
                                    currentIdentity?.let { identity ->
                                        // Public Key (npub)
                                        KeyDisplayRow(
                                            icon = Icons.Filled.Person,
                                            label = "Public Key (npub)",
                                            value = identity.npub,
                                            showValue = true,
                                            onToggleVisibility = {},
                                            onCopy = {
                                                clipboardManager.setText(AnnotatedString(identity.npub))
                                                Toast.makeText(context, "Public key copied", Toast.LENGTH_SHORT).show()
                                            },
                                            isSecret = false
                                        )
                                        
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 56.dp),
                                            color = colorScheme.outline.copy(alpha = 0.12f)
                                        )
                                        
                                        // Private Key (nsec)
                                        KeyDisplayRow(
                                            icon = Icons.Filled.Key,
                                            label = "Private Key (nsec)",
                                            value = identity.getNsec(),
                                            showValue = showNsec,
                                            onToggleVisibility = { showNsec = !showNsec },
                                            onCopy = {
                                                if (showNsec) {
                                                    clipboardManager.setText(AnnotatedString(identity.getNsec()))
                                                    Toast.makeText(context, "Private key copied - keep it safe!", Toast.LENGTH_LONG).show()
                                                } else {
                                                    Toast.makeText(context, "Reveal the key first to copy", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            isSecret = true
                                        )
                                    } ?: run {
                                        Text(
                                            text = "No identity found",
                                            modifier = Modifier.padding(16.dp),
                                            color = colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Security Warning
                    item(key = "warning") {
                        Surface(
                            modifier = Modifier
                                .padding(horizontal = 20.dp)
                                .fillMaxWidth(),
                            color = colorScheme.error.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "Keep your nsec private",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colorScheme.error
                                    )
                                    Text(
                                        text = "Your private key (nsec) gives full access to your Nostr identity. Never share it with anyone. Back it up securely.",
                                        fontSize = 13.sp,
                                        color = colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }

                    // Import Key Section
                    item(key = "import") {
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Text(
                                text = "IMPORT PRIVATE KEY",
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onBackground.copy(alpha = 0.5f),
                                letterSpacing = 0.5.sp,
                                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                            )
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = colorScheme.surface,
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "Have an existing Nostr account? Import your private key (nsec) to sign and publish messages.",
                                        fontSize = 13.sp,
                                        color = colorScheme.onSurface.copy(alpha = 0.7f),
                                        modifier = Modifier.padding(bottom = 12.dp)
                                    )
                                    
                                    Button(
                                        onClick = { showImportDialog = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Key,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = "Import Private Key (nsec)",
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Clear Private Key Section
                    item(key = "clear_private") {
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Text(
                                text = "PRIVATE KEY",
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onBackground.copy(alpha = 0.5f),
                                letterSpacing = 0.5.sp,
                                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                            )
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = colorScheme.surface,
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "You can remove your private key from this device while keeping the public-only npub for subscriptions.",
                                        fontSize = 13.sp,
                                        color = colorScheme.onSurface.copy(alpha = 0.7f),
                                        modifier = Modifier.padding(bottom = 12.dp)
                                    )
                                    Button(onClick = {
                                        NostrIdentityBridge.clearPrivateKey(context)
                                        currentIdentity = NostrIdentityBridge.getCurrentNostrIdentity(context)
                                        Toast.makeText(context, "Private key removed from device", Toast.LENGTH_LONG).show()
                                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = colorScheme.error), shape = RoundedCornerShape(12.dp)) {
                                        Text("Remove Private Key", color = Color.White)
                                    }
                                }
                            }
                        }
                    }

                    // Footer
                    item(key = "footer") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Your keys are stored securely on this device",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                        }
                    }
                }

                // TopBar
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(64.dp)
                        .background(MaterialTheme.colorScheme.background.copy(alpha = topBarAlpha))
                ) {
                    CloseButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(horizontal = 16.dp),
                    )
                }
            }
        }
        
        // Import Dialog
        if (showImportDialog) {
            AlertDialog(
                onDismissRequest = { 
                    showImportDialog = false
                    importNsecText = ""
                    importError = null
                },
                title = {
                    Text(
                        text = "Import nsec",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Paste your nsec below. This will replace your current identity.",
                            fontSize = 14.sp,
                            color = colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        
                        OutlinedTextField(
                            value = importNsecText,
                            onValueChange = { 
                                importNsecText = it
                                importError = null
                            },
                            label = { Text("nsec1...") },
                            placeholder = { Text("nsec1...") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { keyboardController?.hide() }
                            ),
                            isError = importError != null,
                            supportingText = importError?.let { { Text(it, color = colorScheme.error) } }
                        )
                        
                        Surface(
                            color = colorScheme.error.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "This will permanently replace your current key!",
                                    fontSize = 12.sp,
                                    color = colorScheme.error
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (importNsecText.isBlank()) {
                                importError = "Please enter an nsec"
                                return@TextButton
                            }
                            
                            if (!NostrIdentityBridge.isValidNsec(importNsecText)) {
                                importError = "Invalid nsec format"
                                return@TextButton
                            }
                            
                            showImportConfirmation = true
                        }
                    ) {
                        Text("Import", color = if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { 
                            showImportDialog = false
                            importNsecText = ""
                            importError = null
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }
        
        // Confirmation Dialog
        if (showImportConfirmation) {
            AlertDialog(
                onDismissRequest = { showImportConfirmation = false },
                title = {
                    Text(
                        text = "Confirm Import",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "Are you sure you want to replace your current Nostr identity? This action cannot be undone. Make sure you have backed up your current nsec if needed.",
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val newIdentity = NostrIdentityBridge.importFromNsec(importNsecText, context)
                            if (newIdentity != null) {
                                currentIdentity = newIdentity
                                showImportConfirmation = false
                                showImportDialog = false
                                importNsecText = ""
                                importError = null
                                showNsec = false
                                onIdentityChanged?.invoke()
                                Toast.makeText(context, "Identity imported successfully!", Toast.LENGTH_LONG).show()
                                
                                // Fetch profile from Nostr relays to get the user's name
                                NostrIdentityBridge.fetchProfileFromRelays(newIdentity.publicKeyHex) { name, displayName, _, _, _ ->
                                    // Prefer name (username like @avillagran), fallback to display_name
                                    val nostrName = name?.takeIf { it.isNotBlank() } ?: displayName?.takeIf { it.isNotBlank() }
                                    if (nostrName != null) {
                                        foundNostrName = nostrName
                                        showNameConfirmation = true
                                    }
                                }
                            } else {
                                showImportConfirmation = false
                                importError = "Failed to import key"
                            }
                        }
                    ) {
                        Text("Replace", color = colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showImportConfirmation = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
        
        // Name confirmation dialog - shown when a Nostr profile with a name is found
        if (showNameConfirmation && foundNostrName != null) {
            AlertDialog(
                onDismissRequest = { 
                    showNameConfirmation = false
                    foundNostrName = null
                },
                title = {
                    Text(
                        text = "Use Nostr Name?",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "We found your Nostr profile name:",
                            fontSize = 14.sp
                        )
                        Surface(
                            color = colorScheme.primaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = foundNostrName!!,
                                modifier = Modifier.padding(12.dp),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = colorScheme.onPrimaryContainer
                            )
                        }
                        Text(
                            text = "Would you like to use this as your BitChat username?",
                            fontSize = 14.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            foundNostrName?.let { name ->
                                onNostrNameFound?.invoke(name)
                                Toast.makeText(context, "Username updated to: $name", Toast.LENGTH_SHORT).show()
                            }
                            showNameConfirmation = false
                            foundNostrName = null
                        }
                    ) {
                        Text("Yes, use this name", color = if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { 
                            showNameConfirmation = false
                            foundNostrName = null
                        }
                    ) {
                        Text("No, keep current")
                    }
                }
            )
        }
    }
}
