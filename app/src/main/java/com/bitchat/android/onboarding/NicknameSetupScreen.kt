package com.bitchat.android.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.bitchat.android.R

@Composable
fun NicknameSetupScreen(
    modifier: Modifier = Modifier,
    onNicknameSaved: (String) -> Unit
) {
    var nickname by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Choose your nickname",
            style = MaterialTheme.typography.headlineMedium
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "This name will be visible to everyone at the festival.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        OutlinedTextField(
            value = nickname,
            onValueChange = { 
                if (it.length <= 15) {
                    nickname = it
                    isError = false
                }
            },
            label = { Text("Nickname") },
            placeholder = { Text("e.g. FestivalFan") },
            isError = isError,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                if (isError) {
                    Text("Please enter a valid nickname (max 15 chars).")
                } else {
                    Text("${nickname.length}/15")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    val trimmed = nickname.trim()
                    if (trimmed.isNotEmpty()) {
                        onNicknameSaved(trimmed)
                    } else {
                        isError = true
                    }
                }
            )
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = {
                val trimmed = nickname.trim()
                if (trimmed.isNotEmpty()) {
                    onNicknameSaved(trimmed)
                } else {
                    isError = true
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }
    }
}
