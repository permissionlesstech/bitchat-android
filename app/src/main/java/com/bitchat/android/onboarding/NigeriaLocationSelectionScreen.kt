package com.bitchat.android.onboarding

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
import com.bitchat.android.profiling.AppDatabase
import kotlinx.coroutines.launch

@Composable
fun NigeriaLocationSelectionScreen(
    modifier: Modifier = Modifier,
    onLocationSelected: (NigeriaLocation) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val adminDao = remember { AppDatabase.getDatabase(context).adminDao() }

    var states by remember { mutableStateOf(emptyList<String>()) }
    var regions by remember { mutableStateOf(emptyList<String>()) }
    var lgas by remember { mutableStateOf(emptyList<String>()) }
    var wards by remember { mutableStateOf(emptyList<String>()) }
    var constituencies by remember { mutableStateOf(emptyList<String>()) }

    var selectedState by remember { mutableStateOf("") }
    var selectedRegion by remember { mutableStateOf("") }
    var selectedLga by remember { mutableStateOf("") }
    var selectedWard by remember { mutableStateOf("") }
    var selectedConstituency by remember { mutableStateOf("") }

    var stateSearch by remember { mutableStateOf("") }
    var regionSearch by remember { mutableStateOf("") }
    var lgaSearch by remember { mutableStateOf("") }
    var wardSearch by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        states = adminDao.getAllStates().map { it.name }
    }

    LaunchedEffect(selectedState) {
        if (selectedState.isNotEmpty()) {
            regions = adminDao.getRegionsForState(selectedState).map { it.name }
        }
    }

    LaunchedEffect(selectedState, selectedRegion) {
        if (selectedRegion.isNotEmpty()) {
            lgas = adminDao.getLgasForRegion(selectedState, selectedRegion).map { it.name }
        }
    }

    LaunchedEffect(selectedState, selectedLga) {
        if (selectedLga.isNotEmpty()) {
            wards = adminDao.getWardsForLga(selectedState, selectedLga).map { it.name }
        }
    }

    LaunchedEffect(selectedState, selectedWard) {
        if (selectedWard.isNotEmpty()) {
            constituencies = adminDao.getConstituenciesForWard(selectedState, selectedWard).map { it.name }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "LOCATION SELECTION",
            fontSize = 24.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        DropdownSelection("State", selectedState, states.filter { it.contains(stateSearch, ignoreCase = true) }, stateSearch, { stateSearch = it }) {
            selectedState = it; selectedRegion = ""; selectedLga = ""; selectedWard = ""; selectedConstituency = ""; stateSearch = ""
        }

        if (selectedState.isNotEmpty()) {
            DropdownSelection("Region", selectedRegion, regions.filter { it.contains(regionSearch, ignoreCase = true) }, regionSearch, { regionSearch = it }) {
                selectedRegion = it; selectedLga = ""; selectedWard = ""; selectedConstituency = ""; regionSearch = ""
            }
        }

        if (selectedRegion.isNotEmpty()) {
            DropdownSelection("LGA / District", selectedLga, lgas.filter { it.contains(lgaSearch, ignoreCase = true) }, lgaSearch, { lgaSearch = it }) {
                selectedLga = it; selectedWard = ""; selectedConstituency = ""; lgaSearch = ""
            }
        }

        if (selectedLga.isNotEmpty()) {
            DropdownSelection("Ward", selectedWard, wards.filter { it.contains(wardSearch, ignoreCase = true) }, wardSearch, { wardSearch = it }) {
                selectedWard = it; selectedConstituency = ""; wardSearch = ""
            }
        }

        if (selectedWard.isNotEmpty()) {
            DropdownSelection("Constituency", selectedConstituency, constituencies, "", {}) {
                selectedConstituency = it
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                onLocationSelected(NigeriaLocation(selectedState, selectedRegion, selectedLga, selectedWard, selectedConstituency))
            },
            enabled = selectedState.isNotEmpty() && selectedLga.isNotEmpty() && selectedWard.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium
        ) {
            Text("CONTINUE", fontFamily = FontFamily.Monospace)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownSelection(
    label: String,
    selectedValue: String,
    options: List<String>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selectedValue,
            onValueChange = {},
            readOnly = true,
            label = { Text(label, fontFamily = FontFamily.Monospace) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (options.size > 5) {
                TextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    placeholder = { Text("Search...", fontSize = 12.sp) },
                    singleLine = true
                )
            }
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, fontFamily = FontFamily.Monospace) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

data class NigeriaData(val states: List<StateData>)
data class StateData(val name: String, val regions: List<RegionData>)
data class RegionData(val name: String, val lgas: List<LgaData>)
data class LgaData(val name: String, val wards: List<WardData>)
data class WardData(val name: String, val constituencies: List<String>)
