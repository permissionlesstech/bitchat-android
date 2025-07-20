package com.bitchat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bitchat.android.model.PatientRecord
import com.bitchat.android.model.PatientStatus
import com.bitchat.android.model.Priority
import com.bitchat.android.model.PatientHistoryEntry
import java.util.*

/**
 * Screen for adding a new patient record
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPatientScreen(
    patientViewModel: PatientViewModel,
    onBack: () -> Unit,
    onPatientAdded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    
    // Form state
    var patientId by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("") }
    var bloodType by remember { mutableStateOf("") }
    var allergies by remember { mutableStateOf("") }
    var medications by remember { mutableStateOf("") }
    var medicalHistory by remember { mutableStateOf("") }
    var presentingComplaint by remember { mutableStateOf("") }
    var treatment by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var initialComment by remember { mutableStateOf("") }  // Added for patient history
    var selectedStatus by remember { mutableStateOf(PatientStatus.STABLE) }
    var selectedPriority by remember { mutableStateOf(Priority.LOW) }
    
    // Validation state
    var showErrors by remember { mutableStateOf(false) }
    
    // Auto-generate patient ID if empty
    LaunchedEffect(Unit) {
        if (patientId.isEmpty()) {
            patientId = "P${(100000..999999).random()}"
        }
    }
    
    val isFormValid = name.isNotBlank() && patientId.isNotBlank() && presentingComplaint.isNotBlank()
    
    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Header
        AddPatientHeader(
            onBack = onBack,
            onSave = {
                if (isFormValid) {
                    val newPatient = PatientRecord(
                        patientId = patientId.trim(),
                        name = name.trim(),
                        age = age.toIntOrNull(),
                        gender = gender.trim().takeIf { it.isNotBlank() },
                        bloodType = bloodType.trim().takeIf { it.isNotBlank() },
                        allergies = allergies.split(",").map { it.trim() }.filter { it.isNotBlank() },
                        currentMedications = medications.split(",").map { it.trim() }.filter { it.isNotBlank() },
                        medicalHistory = medicalHistory.trim(),
                        presentingComplaint = presentingComplaint.trim(),
                        treatment = treatment.trim(),
                        status = selectedStatus,
                        priority = selectedPriority,
                        location = location.trim().takeIf { it.isNotBlank() },
                        authorFingerprint = "current_user", // In real app, get from auth
                        lastModified = Date(),
                        historyEntries = if (initialComment.isNotBlank()) {
                            listOf(
                                PatientHistoryEntry(
                                    text = initialComment.trim(),
                                    authorFingerprint = "current_user", // In real app, get from auth
                                    timestamp = Date()
                                )
                            )
                        } else {
                            emptyList()
                        }
                    )
                    
                    patientViewModel.addPatient(newPatient)
                    onPatientAdded()
                } else {
                    showErrors = true
                }
            },
            isFormValid = isFormValid,
            colorScheme = colorScheme
        )
        
        // Form content
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Patient identification
            item {
                PatientIdentificationSection(
                    patientId = patientId,
                    onPatientIdChange = { patientId = it },
                    name = name,
                    onNameChange = { name = it },
                    showErrors = showErrors,
                    colorScheme = colorScheme
                )
            }
            
            // Demographics
            item {
                DemographicsSection(
                    age = age,
                    onAgeChange = { age = it },
                    gender = gender,
                    onGenderChange = { gender = it },
                    bloodType = bloodType,
                    onBloodTypeChange = { bloodType = it },
                    colorScheme = colorScheme
                )
            }
            
            // Status and Priority
            item {
                StatusPrioritySection(
                    selectedStatus = selectedStatus,
                    onStatusChange = { selectedStatus = it },
                    selectedPriority = selectedPriority,
                    onPriorityChange = { selectedPriority = it },
                    colorScheme = colorScheme
                )
            }
            
            // Medical information
            item {
                MedicalInformationSection(
                    presentingComplaint = presentingComplaint,
                    onPresentingComplaintChange = { presentingComplaint = it },
                    treatment = treatment,
                    onTreatmentChange = { treatment = it },
                    medicalHistory = medicalHistory,
                    onMedicalHistoryChange = { medicalHistory = it },
                    showErrors = showErrors,
                    colorScheme = colorScheme
                )
            }
            
            // Allergies and medications
            item {
                AllergiesMedicationsSection(
                    allergies = allergies,
                    onAllergiesChange = { allergies = it },
                    medications = medications,
                    onMedicationsChange = { medications = it },
                    colorScheme = colorScheme
                )
            }
            
            // Location
            item {
                LocationSection(
                    location = location,
                    onLocationChange = { location = it },
                    colorScheme = colorScheme
                )
            }
            
            // Initial Comment/History Entry
            item {
                PatientInitialHistorySection(
                    initialComment = initialComment,
                    onInitialCommentChange = { initialComment = it },
                    colorScheme = colorScheme
                )
            }
            
            // Bottom padding for FAB
            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPatientHeader(
    onBack: () -> Unit,
    onSave: () -> Unit,
    isFormValid: Boolean,
    colorScheme: ColorScheme
) {
    TopAppBar(
        title = {
            Text(
                text = "Add New Patient",
                fontWeight = FontWeight.Bold
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back"
                )
            }
        },
        actions = {
            TextButton(
                onClick = onSave,
                enabled = isFormValid
            ) {
                Text("Save")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colorScheme.surface
        )
    )
}

@Composable
fun PatientIdentificationSection(
    patientId: String,
    onPatientIdChange: (String) -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    showErrors: Boolean,
    colorScheme: ColorScheme
) {
    FormSection(
        title = "Patient Identification",
        icon = Icons.Default.Badge
    ) {
        OutlinedTextField(
            value = patientId,
            onValueChange = onPatientIdChange,
            label = { Text("Patient ID") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = showErrors && patientId.isBlank(),
            supportingText = if (showErrors && patientId.isBlank()) {
                { Text("Patient ID is required") }
            } else null
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("Full Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = showErrors && name.isBlank(),
            supportingText = if (showErrors && name.isBlank()) {
                { Text("Patient name is required") }
            } else null
        )
    }
}

@Composable
fun DemographicsSection(
    age: String,
    onAgeChange: (String) -> Unit,
    gender: String,
    onGenderChange: (String) -> Unit,
    bloodType: String,
    onBloodTypeChange: (String) -> Unit,
    colorScheme: ColorScheme
) {
    FormSection(
        title = "Demographics",
        icon = Icons.Default.Person
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = age,
                onValueChange = { if (it.all { char -> char.isDigit() } && it.length <= 3) onAgeChange(it) },
                label = { Text("Age") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            
            GenderDropdown(
                selectedGender = gender,
                onGenderChange = onGenderChange,
                modifier = Modifier.weight(1f)
            )
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        BloodTypeDropdown(
            selectedBloodType = bloodType,
            onBloodTypeChange = onBloodTypeChange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun StatusPrioritySection(
    selectedStatus: PatientStatus,
    onStatusChange: (PatientStatus) -> Unit,
    selectedPriority: Priority,
    onPriorityChange: (Priority) -> Unit,
    colorScheme: ColorScheme
) {
    FormSection(
        title = "Status & Priority",
        icon = Icons.Default.Flag
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusDropdown(
                selectedStatus = selectedStatus,
                onStatusChange = onStatusChange,
                modifier = Modifier.weight(1f)
            )
            
            PriorityDropdown(
                selectedPriority = selectedPriority,
                onPriorityChange = onPriorityChange,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun MedicalInformationSection(
    presentingComplaint: String,
    onPresentingComplaintChange: (String) -> Unit,
    treatment: String,
    onTreatmentChange: (String) -> Unit,
    medicalHistory: String,
    onMedicalHistoryChange: (String) -> Unit,
    showErrors: Boolean,
    colorScheme: ColorScheme
) {
    FormSection(
        title = "Medical Information",
        icon = Icons.Default.LocalHospital
    ) {
        OutlinedTextField(
            value = presentingComplaint,
            onValueChange = onPresentingComplaintChange,
            label = { Text("Presenting Complaint") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
            isError = showErrors && presentingComplaint.isBlank(),
            supportingText = if (showErrors && presentingComplaint.isBlank()) {
                { Text("Presenting complaint is required") }
            } else null
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        OutlinedTextField(
            value = treatment,
            onValueChange = onTreatmentChange,
            label = { Text("Treatment Plan") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        OutlinedTextField(
            value = medicalHistory,
            onValueChange = onMedicalHistoryChange,
            label = { Text("Medical History") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4
        )
    }
}

@Composable
fun AllergiesMedicationsSection(
    allergies: String,
    onAllergiesChange: (String) -> Unit,
    medications: String,
    onMedicationsChange: (String) -> Unit,
    colorScheme: ColorScheme
) {
    FormSection(
        title = "Allergies & Medications",
        icon = Icons.Default.Warning
    ) {
        OutlinedTextField(
            value = allergies,
            onValueChange = onAllergiesChange,
            label = { Text("Allergies") },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Separate with commas") },
            minLines = 1,
            maxLines = 3
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        OutlinedTextField(
            value = medications,
            onValueChange = onMedicationsChange,
            label = { Text("Current Medications") },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Separate with commas") },
            minLines = 1,
            maxLines = 3
        )
    }
}

@Composable
fun LocationSection(
    location: String,
    onLocationChange: (String) -> Unit,
    colorScheme: ColorScheme
) {
    FormSection(
        title = "Location",
        icon = Icons.Default.LocationOn
    ) {
        OutlinedTextField(
            value = location,
            onValueChange = onLocationChange,
            label = { Text("Current Location") },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Ward, Room, Bed number") },
            singleLine = true
        )
    }
}

@Composable
fun FormSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenderDropdown(
    selectedGender: String,
    onGenderChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val genderOptions = listOf("Male", "Female", "Other", "Prefer not to say")
    
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedGender,
            onValueChange = {},
            readOnly = true,
            label = { Text("Gender") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            singleLine = true
        )
        
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            genderOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onGenderChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BloodTypeDropdown(
    selectedBloodType: String,
    onBloodTypeChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val bloodTypes = listOf("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-")
    
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedBloodType,
            onValueChange = {},
            readOnly = true,
            label = { Text("Blood Type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            singleLine = true
        )
        
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            bloodTypes.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type) },
                    onClick = {
                        onBloodTypeChange(type)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusDropdown(
    selectedStatus: PatientStatus,
    onStatusChange: (PatientStatus) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedStatus.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Status") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            singleLine = true
        )
        
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            PatientStatus.values().forEach { status ->
                DropdownMenuItem(
                    text = { Text(status.displayName) },
                    onClick = {
                        onStatusChange(status)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun PatientInitialHistorySection(
    initialComment: String,
    onInitialCommentChange: (String) -> Unit,
    colorScheme: ColorScheme
) {
    FormSection(
        title = "Patient History",
        icon = Icons.Default.Comment
    ) {
        OutlinedTextField(
            value = initialComment,
            onValueChange = onInitialCommentChange,
            label = { Text("Initial Comment") },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Add initial notes or comments about the patient") },
            minLines = 2,
            maxLines = 4
        )
        
        if (initialComment.isBlank()) {
            Text(
                text = "This is optional. Any comment added here will be saved in patient history.",
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PriorityDropdown(
    selectedPriority: Priority,
    onPriorityChange: (Priority) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedPriority.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Priority") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            singleLine = true
        )
        
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            Priority.values().forEach { priority ->
                DropdownMenuItem(
                    text = { Text(priority.displayName) },
                    onClick = {
                        onPriorityChange(priority)
                        expanded = false
                    }
                )
            }
        }
    }
}
