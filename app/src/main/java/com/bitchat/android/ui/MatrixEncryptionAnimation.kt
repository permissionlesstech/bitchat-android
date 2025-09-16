package com.bitchat.android.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Animation state for individual characters
 */
private enum class CharacterAnimationState {
    ENCRYPTED,    // Showing random encrypted characters
    DECRYPTING,   // Transitioning to final character
    FINAL         // Showing final decrypted character
}

/**
 * Check if a message should be animated based on its mining state
 */
@Composable
fun shouldAnimateMessage(messageId: String): Boolean {
    val miningMessages by PoWMiningTracker.miningMessages.collectAsState()
    return miningMessages.contains(messageId)
}

/**
 * Tracks which messages are currently being mined for PoW
 * Provides reactive state for UI animations
 */
object PoWMiningTracker {
    private val _miningMessages = MutableStateFlow<Set<String>>(emptySet())
    val miningMessages: StateFlow<Set<String>> = _miningMessages.asStateFlow()
    
    /**
     * Start tracking a message as mining
     */
    fun startMiningMessage(messageId: String) {
        _miningMessages.value = _miningMessages.value + messageId
    }
    
    /**
     * Stop tracking a message as mining
     */
    fun stopMiningMessage(messageId: String) {
        _miningMessages.value = _miningMessages.value - messageId
    }
    
    /**
     * Check if a message is currently mining
     */
    fun isMiningMessage(messageId: String): Boolean {
        return _miningMessages.value.contains(messageId)
    }
    
    /**
     * Clear all mining messages (for cleanup)
     */
    fun clearAllMining() {
        _miningMessages.value = emptySet()
    }
}

/**
 * Enhanced message display that shows matrix animation during PoW mining
 * Formats message like a normal message but animates only the content portion
 */
@Composable
fun MessageWithMatrixAnimation(
    message: com.bitchat.android.model.BitchatMessage,
    messages: List<com.bitchat.android.model.BitchatMessage> = emptyList(),
    currentUserNickname: String,
    meshService: com.bitchat.android.mesh.BluetoothMeshService,
    colorScheme: androidx.compose.material3.ColorScheme,
    timeFormatter: java.text.SimpleDateFormat,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((com.bitchat.android.model.BitchatMessage) -> Unit)?,
    onImageClick: ((String, List<String>, Int) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val isAnimating = shouldAnimateMessage(message.id)
    
    if (isAnimating) {
        // During animation: Show formatted message with animated content
        AnimatedMessageDisplay(
            message = message,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter,
            modifier = modifier
        )
    } else {
        // After animation: Show complete normal message using existing formatter
        val annotatedText = formatMessageAsAnnotatedString(
            message = message,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter
        )
        
        Text(
            text = annotatedText,
            modifier = modifier,
            fontFamily = FontFamily.Monospace,
            softWrap = true
        )
    }
}

/**
 * Display message with proper formatting but animated content
 * Uses IDENTICAL layout structure as normal message for pixel-perfect alignment
 */
@Composable
private fun AnimatedMessageDisplay(
    message: com.bitchat.android.model.BitchatMessage,
    currentUserNickname: String,
    meshService: com.bitchat.android.mesh.BluetoothMeshService,
    colorScheme: androidx.compose.material3.ColorScheme,
    timeFormatter: java.text.SimpleDateFormat,
    modifier: Modifier = Modifier
) {
    // Get the animated content text
    var animatedContent by remember(message.content) { mutableStateOf(message.content) }
    val isAnimating = shouldAnimateMessage(message.id)
    
    // Character-by-character animation state like the JavaScript version
    var characterStates by remember(message.content) { 
        mutableStateOf(message.content.map { char -> 
            if (char == ' ') CharacterAnimationState.FINAL else CharacterAnimationState.ENCRYPTED 
        })
    }
    
    // Update animated content when animation state changes
    LaunchedEffect(isAnimating, message.content) {
        if (isAnimating && message.content.isNotEmpty()) {
            val encryptedChars = "!@$%^&*()_+-=[]{}|;:,<>?".toCharArray()
            
            // Start character animations with staggered delays (like JS version)
            message.content.forEachIndexed { index, targetChar ->
                if (targetChar != ' ') { // Skip spaces
                    launch {
                        delay(index * 50L) // Stagger start like JS version
                        
                        // Animate this character indefinitely in a loop
                        while (true) {
                            // Animate with random characters
                            while (characterStates.getOrNull(index) == CharacterAnimationState.ENCRYPTED) {
                                // Generate random encrypted character for this position
                                val newContent = animatedContent.toCharArray()
                                if (index < newContent.size) {
                                    newContent[index] = encryptedChars[Random.nextInt(encryptedChars.size)]
                                    animatedContent = String(newContent)
                                }
                                
                                delay(100L) // Change character every 100ms like JS
                                
                                // Random chance to reveal (10% like JS version)
                                if (Random.nextFloat() < 0.1f) {
                                    // Reveal the final character
                                    val finalContent = animatedContent.toCharArray()
                                    if (index < finalContent.size) {
                                        finalContent[index] = targetChar
                                        animatedContent = String(finalContent)
                                    }
                                    
                                    // Mark as revealed
                                    val finalStates = characterStates.toMutableList()
                                    finalStates[index] = CharacterAnimationState.FINAL
                                    characterStates = finalStates
                                    break
                                }
                            }
                            
                            // Keep revealed for 2 seconds, then fade back to encrypted (like JS)
                            delay(2000L)
                            
                            // Reset back to encrypted for next cycle
                            val resetStates = characterStates.toMutableList()
                            resetStates[index] = CharacterAnimationState.ENCRYPTED
                            characterStates = resetStates
                        }
                    }
                }
            }
        } else {
            // Not animating, show final content
            animatedContent = message.content
            characterStates = message.content.map { CharacterAnimationState.FINAL }
        }
    }
    
    // Create a temporary message with animated content for formatting
    val animatedMessage = message.copy(content = animatedContent)
    
    // Use formatting function without timestamp during animation
    val annotatedText = if (isAnimating) {
        formatMessageAsAnnotatedStringWithoutTimestamp(
            message = animatedMessage,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme
        )
    } else {
        formatMessageAsAnnotatedString(
            message = animatedMessage,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter
        )
    }
    
    // Use IDENTICAL Text composable structure as normal message
    Text(
        text = annotatedText,
        modifier = modifier,
        fontFamily = FontFamily.Monospace,
        softWrap = true,
        overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
        style = androidx.compose.ui.text.TextStyle(
            color = colorScheme.onSurface
        )
    )
}


/**
 * Format message without timestamp and PoW badge for animation phase
 * Identical to formatMessageAsAnnotatedString but excludes timestamp and PoW badge
 */
private fun formatMessageAsAnnotatedStringWithoutTimestamp(
    message: com.bitchat.android.model.BitchatMessage,
    currentUserNickname: String,
    meshService: com.bitchat.android.mesh.BluetoothMeshService,
    colorScheme: androidx.compose.material3.ColorScheme
): AnnotatedString {
    // Get the full formatted text first
    val timeFormatter = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    val fullText = formatMessageAsAnnotatedString(
        message = message,
        currentUserNickname = currentUserNickname,
        meshService = meshService,
        colorScheme = colorScheme,
        timeFormatter = timeFormatter
    )
    
    // Find and remove the timestamp and PoW badge at the end
    val text = fullText.text
    val timestampPattern = """ \[\d{2}:\d{2}:\d{2}].*$""".toRegex() // Matches " [HH:mm:ss] 12b" or just " [HH:mm:ss]"
    val match = timestampPattern.find(text)
    
    return if (match != null) {
        // Remove timestamp and PoW portion
        val endIndex = match.range.first
        AnnotatedString(
            text = text.substring(0, endIndex),
            spanStyles = fullText.spanStyles.filter { it.end <= endIndex },
            paragraphStyles = fullText.paragraphStyles.filter { it.end <= endIndex }
        )
    } else {
        fullText
    }
}
