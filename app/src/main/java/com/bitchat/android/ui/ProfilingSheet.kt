package com.bitchat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.location.NigeriaLocation
import com.bitchat.android.location.LocationHierarchyManager
import com.bitchat.android.profiling.ProfilingManager
import com.bitchat.android.profiling.Traits
import com.bitchat.android.nostr.NostrIdentityBridge
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfilingSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit
) {
    if (!isPresented) return
    val context = LocalContext.current
    val profilingManager = remember { ProfilingManager(context) }
    val locationHierarchyManager = remember { LocationHierarchyManager(context) }
    val coroutineScope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    val selectedTraits = remember { mutableStateMapOf<String, String>() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("SCOUT NEW PROFILE", fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)

            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name", fontFamily = FontFamily.Monospace) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = age, onValueChange = { age = it }, label = { Text("Age", fontFamily = FontFamily.Monospace) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = contact, onValueChange = { contact = it }, label = { Text("Contact Info", fontFamily = FontFamily.Monospace) }, modifier = Modifier.fillMaxWidth())

            Text("TRAITS", fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Traits.ALL_TRAITS.forEach { (cat, opts) ->
                Text(cat, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    opts.forEach { opt ->
                        FilterChip(
                            selected = selectedTraits[cat] == opt,
                            onClick = { selectedTraits[cat] = opt },
                            label = { Text(opt, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
                        )
                    }
                }
            }

            Button(
                onClick = {
                    coroutineScope.launch {
                        val location = locationHierarchyManager.getCurrentAdminLocation() ?: return@launch
                        val identity = NostrIdentityBridge.getCurrentNostrIdentity(context) ?: return@launch
                        profilingManager.scoutPerson(
                            name = name,
                            age = age.toIntOrNull(),
                            gender = null,
                            location = location,
                            skills = emptyList(),
                            contact = contact,
                            traits = selectedTraits.toMap(),
                            scoutPubkey = identity.publicKeyHex
                        )
                        onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = name.isNotEmpty()
            ) {
                Text("SAVE PROFILE", fontFamily = FontFamily.Monospace)
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
