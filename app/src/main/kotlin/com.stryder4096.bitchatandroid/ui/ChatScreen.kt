 
// Added for context-aware prompts 
@Composable 
fun ChatScreen(viewModel: ChatViewModel) { 
    // Existing UI... 
    if (viewModel.showWifiPrompt) { // Observe from ViewModel 
        AlertDialog( 
            onDismissRequest = { /* Dismiss */ }, 
            title = { Text("Enable Wi-Fi Direct?") }, 
            text = { Text("Message delivery limited by range. Switch for better reach? ^(Battery impact: medium^)") }, 
            confirmButton = { /* Enable logic */ } 
        ) 
    } 
} 
