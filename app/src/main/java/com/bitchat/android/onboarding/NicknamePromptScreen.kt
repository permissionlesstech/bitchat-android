package com.bitchat.android.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun NicknamePromptScreen(
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var nickname by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Choose your nickname", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        TextField(
            value = nickname,
            onValueChange = { nickname = it },
            placeholder = { Text("e.g. BlueFox") }
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                if (nickname.trim().isNotEmpty()) {
                    onSubmit(nickname.trim())
                }
            }
        ) {
            Text("Continue")
        }
    }
}
