package com.bitchat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.location.NigeriaLocationNotesManager
import com.bitchat.android.location.NigeriaLocation
import com.bitchat.android.location.LocationHierarchyManager
import com.bitchat.android.identity.RoleManager
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.nostr.LocationNotesManager
import com.google.gson.Gson

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NigeriaDashboardSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    viewModel: ChatViewModel
) {
    if (!isPresented) return

    val context = LocalContext.current
    val notesManager = remember { NigeriaLocationNotesManager.getInstance() }
    val locationHierarchyManager = remember { LocationHierarchyManager(context) }
    val roleManager = remember { RoleManager(context) }
    val notes by notesManager.notes.collectAsStateWithLifecycle()
    val scope by notesManager.scope.collectAsStateWithLifecycle()
    val currentLocation = remember { locationHierarchyManager.getCurrentAdminLocation() }
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val nostrRelayManager = NostrRelayManager.shared

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "NIGERIA DASHBOARD",
                fontSize = 20.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (currentLocation != null) {
                Text(
                    text = "${currentLocation.ward}, ${currentLocation.lga}, ${currentLocation.state}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Scope selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val scopes = listOf("ward", "lga", "state", "constituency")
                    scopes.forEach { s ->
                        FilterChip(
                            selected = scope == s,
                            onClick = {
                                val identity = NostrIdentityBridge.getCurrentNostrIdentity(context)
                                if (identity != null) {
                                    notesManager.setScope(s, currentLocation, nostrRelayManager, identity)
                                }
                            },
                            label = { Text(s.uppercase(), fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(notes) { note ->
                    NigeriaNoteItem(note)
                }
            }

            // Input section
            var draft by remember { mutableStateOf("") }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Post to $scope...", fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                )
                IconButton(onClick = {
                    if (draft.isNotEmpty() && currentLocation != null) {
                        val identity = NostrIdentityBridge.getCurrentNostrIdentity(context)
                        if (identity != null) {
                            notesManager.sendNote(draft, currentLocation, identity, nickname, nostrRelayManager)
                            draft = ""
                        }
                    }
                }) {
                    Text("SEND", fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
fun NigeriaNoteItem(note: LocationNotesManager.Note) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = note.displayName,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = note.content,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
