package com.bitchat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Main app navigation container with bottom tab bar
 * Preserves existing chat functionality while adding healthcare features
 */

enum class AppScreen(
    val title: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon
) {
    DASHBOARD("Dashboard", Icons.Default.Dashboard),
    PATIENTS("Patients", Icons.Default.People),
    CHAT("Chat", Icons.Default.Chat)
}

@Composable
fun MainAppNavigation(
    chatViewModel: ChatViewModel,
    patientViewModel: PatientViewModel,
    modifier: Modifier = Modifier
) {
    var currentScreen by remember { mutableStateOf(AppScreen.DASHBOARD) }
    var navigationStack by remember { mutableStateOf(listOf<NavigationState>()) }
    
    // Handle back navigation
    val onBack = {
        if (navigationStack.isNotEmpty()) {
            navigationStack = navigationStack.dropLast(1)
        }
    }
    
    Column(modifier = modifier.fillMaxSize()) {
        // Main content area
        Box(modifier = Modifier.weight(1f)) {
            when {
                navigationStack.isNotEmpty() -> {
                    // Show navigation stack screens
                    when (val currentState = navigationStack.last()) {
                        is NavigationState.PatientDetail -> {
                            PatientDetailScreen(
                                patientId = currentState.patientId,
                                patientViewModel = patientViewModel,
                                onBack = onBack,
                                onEdit = { patient ->
                                    navigationStack = navigationStack + NavigationState.EditPatient(patient)
                                }
                            )
                        }
                        is NavigationState.AddPatient -> {
                            AddPatientScreen(
                                patientViewModel = patientViewModel,
                                onBack = onBack,
                                onPatientAdded = onBack
                            )
                        }
                        is NavigationState.EditPatient -> {
                            EditPatientScreen(
                                patient = currentState.patient,
                                patientViewModel = patientViewModel,
                                onBack = onBack,
                                onPatientUpdated = onBack
                            )
                        }
                    }
                }
                else -> {
                    // Show main tab screens
                    when (currentScreen) {
                        AppScreen.DASHBOARD -> {
                            DashboardScreen(
                                chatViewModel = chatViewModel,
                                patientViewModel = patientViewModel
                            )
                        }
                        AppScreen.PATIENTS -> {
                            PatientsScreen(
                                patientViewModel = patientViewModel,
                                onNavigateToDetail = { patient ->
                                    patient?.let { patient ->
                                    navigationStack = navigationStack + NavigationState.PatientDetail(patient.patientId)
                                }
                                },
                                onNavigateToAdd = {
                                    navigationStack = navigationStack + NavigationState.AddPatient
                                },
                                onSyncPressed = {
                                    // Convert patient data to JSON and send to chat
                                    val patientsJson = patientViewModel.getPatientsAsJson()
                                    chatViewModel.sendMessage("Patient Records Synced: $patientsJson")
                                }
                            )
                        }
                        AppScreen.CHAT -> {
                            ChatScreen(viewModel = chatViewModel)
                        }
                    }
                }
            }
        }
        
        // Bottom navigation bar (only show when not in navigation stack)
        if (navigationStack.isEmpty()) {
            BottomNavigationBar(
                currentScreen = currentScreen,
                onScreenSelected = { screen ->
                    currentScreen = screen
                }
            )
        }
    }
}

@Composable
fun BottomNavigationBar(
    currentScreen: AppScreen,
    onScreenSelected: (AppScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary
    ) {
        AppScreen.values().forEach { screen ->
            NavigationBarItem(
                icon = {
                    Icon(
                        imageVector = if (currentScreen == screen) screen.selectedIcon else screen.icon,
                        contentDescription = screen.title
                    )
                },
                label = { Text(screen.title) },
                selected = currentScreen == screen,
                onClick = { onScreenSelected(screen) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    }
}

/**
 * Navigation state for handling detailed screens that stack on top of main tabs
 */
sealed class NavigationState {
    data class PatientDetail(val patientId: String) : NavigationState()
    object AddPatient : NavigationState()
    data class EditPatient(val patient: com.bitchat.android.model.PatientRecord) : NavigationState()
}
