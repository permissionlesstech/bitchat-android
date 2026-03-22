package com.bitchat.android.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.profiling.UserExtendedProfile
import com.bitchat.android.profiling.Traits

@Composable
fun ProfileSetupScreen(
    modifier: Modifier = Modifier,
    onComplete: (UserExtendedProfile) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    val selectedTraits = remember { mutableStateMapOf<String, String>() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "PROFILE SETUP",
            fontSize = 24.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Display Name", fontFamily = FontFamily.Monospace) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
        )

        OutlinedTextField(
            value = age,
            onValueChange = { if (it.isEmpty() || it.all { char -> char.isDigit() }) age = it },
            label = { Text("Age", fontFamily = FontFamily.Monospace) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
        )

        OutlinedTextField(
            value = bio,
            onValueChange = { bio = it },
            label = { Text("Bio / Motto", fontFamily = FontFamily.Monospace) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
        )

        Text(
            text = "TRAITS",
            fontSize = 18.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Start)
        )

        Traits.ALL_TRAITS.forEach { (category, options) ->
            Text(
                text = category,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Start)
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEach { option ->
                    FilterChip(
                        selected = selectedTraits[category] == option,
                        onClick = { selectedTraits[category] = option },
                        label = { Text(option, fontFamily = FontFamily.Monospace) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                onComplete(UserExtendedProfile(
                    name = name,
                    age = age.toIntOrNull() ?: 0,
                    bio = bio,
                    traits = selectedTraits.toMap()
                ))
            },
            enabled = name.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium
        ) {
            Text("FINISH", fontFamily = FontFamily.Monospace)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: @Composable () -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier,
        horizontalArrangement = horizontalArrangement,
        content = { content() }
    )
}
