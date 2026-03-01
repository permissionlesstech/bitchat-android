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
import com.bitchat.android.profiling.ProfilingManager
import com.bitchat.android.profiling.ScoutedProfile
import com.bitchat.android.identity.RoleManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuperAdminDashboard(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    viewModel: ChatViewModel
) {
    if (!isPresented) return
    val context = LocalContext.current
    val profilingManager = remember { ProfilingManager(context) }
    val roleManager = remember { RoleManager(context) }
    var profiles by remember { mutableStateOf(emptyList<ScoutedProfile>()) }

    LaunchedEffect(isPresented) {
        profiles = profilingManager.getAllProfiles()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text("SUPER ADMIN DASHBOARD", fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("Total Profiles: ${profiles.size}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)

            Spacer(modifier = Modifier.height(16.dp))

            // Visualization: Group by State
            val stateCounts = profiles.groupBy { it.location.state }.mapValues { it.value.size }
            Text("PROFILES BY STATE", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
            LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                items(stateCounts.toList()) { (state, count) ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(state, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text(count.toString(), fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text("RECENT PROFILES", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(profiles.sortedByDescending { it.createdAt }.take(10)) { profile ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(profile.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("${profile.location.ward}, ${profile.location.lga}", fontSize = 11.sp)
                            Text("Skills: ${profile.skills.joinToString()}", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
