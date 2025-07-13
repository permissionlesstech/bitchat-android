 
// Added for Wi-Fi Direct aware prompts 
fun sendChatMessage(message: String) { 
    hybridMeshManager.sendMessage(message.toByteArray(), target, onFailure = { 
        promptUserForWifiDirect() // Call to show dialog 
    }) 
} 
 
private fun promptUserForWifiDirect() { 
    // Use LiveData or StateFlow to trigger UI prompt, e.g., "Message failed due to range. Enable Wi-Fi Direct?" 
    // Based on environment: if battery < 30%, suggest balanced mode 
} 
