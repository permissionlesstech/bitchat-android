package com.bitchat.android.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Matrix-style encryption animation for messages during PoW computation
 * Animates message content with random characters that gradually resolve to real text
 */
@Composable
fun AnimatedMatrixText(
    targetText: String,
    isAnimating: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null
) {
    // Animation state for each character
    var animatedText by remember(targetText) { mutableStateOf(targetText) }
    var characterStates by remember(targetText) { mutableStateOf(
        targetText.indices.map { CharacterAnimationState.ENCRYPTED }.toList()
    ) }
    
    // Start animation when isAnimating becomes true
    LaunchedEffect(isAnimating, targetText) {
        if (isAnimating && targetText.isNotEmpty()) {
            // Reset character states for new animation
            characterStates = targetText.indices.map { CharacterAnimationState.ENCRYPTED }.toList()
            
            // Animate all characters in parallel with staggered start times
            targetText.forEachIndexed { index, targetChar ->
                launch { // Use the LaunchedEffect's CoroutineScope
                    // Stagger character animations (50ms per character like the JS example)
                    delay(index * 50L)
                    
                    animateCharacter(
                        index = index,
                        targetChar = targetChar,
                        currentText = { animatedText },
                        updateText = { newText -> animatedText = newText },
                        updateState = { newStates -> characterStates = newStates }
                    )
                }
            }
        } else {
            // Not animating, show final text immediately
            animatedText = targetText
            characterStates = targetText.indices.map { CharacterAnimationState.FINAL }.toList()
        }
    }
    
    // Build annotated string with proper styling for each character state
    val styledText = buildAnnotatedString {
        animatedText.forEachIndexed { index, char ->
            val state = characterStates.getOrNull(index) ?: CharacterAnimationState.FINAL
            val charColor = when (state) {
                CharacterAnimationState.ENCRYPTED -> Color(0xFF00FF41) // Matrix green
                CharacterAnimationState.DECRYPTING -> Color(0xFFFFFF41) // Yellow transition
                CharacterAnimationState.FINAL -> color
            }
            
            withStyle(
                style = SpanStyle(
                    color = charColor,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (state == CharacterAnimationState.ENCRYPTED) FontWeight.Bold else fontWeight
                )
            ) {
                append(char)
            }
        }
    }
    
    Text(
        text = styledText,
        modifier = modifier,
        fontSize = fontSize,
        fontFamily = FontFamily.Monospace
    )
}

/**
 * Animation state for individual characters
 */
private enum class CharacterAnimationState {
    ENCRYPTED,    // Showing random encrypted characters
    DECRYPTING,   // Transitioning to final character
    FINAL         // Showing final decrypted character
}

/**
 * Animate a single character from encrypted to final state
 */
private suspend fun animateCharacter(
    index: Int,
    targetChar: Char,
    currentText: () -> String,
    updateText: (String) -> Unit,
    updateState: (List<CharacterAnimationState>) -> Unit
) {
    val encryptedChars = "!@#$%^&*()_+-=[]{}|;:,.<>?~`".toCharArray()
    val animationDuration = Random.nextLong(1000, 3000) // 1-3 seconds of encryption
    val startTime = System.currentTimeMillis()
    
    // Phase 1: Animate with random encrypted characters
    while (System.currentTimeMillis() - startTime < animationDuration) {
        val randomChar = encryptedChars[Random.nextInt(encryptedChars.size)]
        
        // Update the character at this index
        val text = currentText()
        if (index < text.length) {
            val newText = text.toCharArray()
            newText[index] = randomChar
            updateText(String(newText))
        }
        
        delay(100) // Change character every 100ms
    }
    
    // Phase 2: Brief transition state (yellow)
    val states = currentText().indices.map { i ->
        when {
            i == index -> CharacterAnimationState.DECRYPTING
            i < index -> CharacterAnimationState.FINAL  // Already revealed
            else -> CharacterAnimationState.ENCRYPTED   // Still encrypted
        }
    }
    updateState(states)
    delay(200) // Brief yellow flash
    
    // Phase 3: Reveal final character
    val text = currentText()
    if (index < text.length) {
        val newText = text.toCharArray()
        newText[index] = targetChar
        updateText(String(newText))
        
        val finalStates = currentText().indices.map { i ->
            when {
                i <= index -> CharacterAnimationState.FINAL
                else -> CharacterAnimationState.ENCRYPTED
            }
        }
        updateState(finalStates)
    }
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
 * This version integrates with the full message display system
 */
@Composable
fun MessageWithMatrixAnimation(
    message: com.bitchat.android.model.BitchatMessage,
    currentUserNickname: String,
    meshService: com.bitchat.android.mesh.BluetoothMeshService,
    colorScheme: androidx.compose.material3.ColorScheme,
    timeFormatter: java.text.SimpleDateFormat,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((com.bitchat.android.model.BitchatMessage) -> Unit)?,
    modifier: Modifier = Modifier
) {
    // For simplicity, just animate the entire message content
    val baseAnnotatedText = formatMessageAsAnnotatedString(
        message = message,
        currentUserNickname = currentUserNickname,
        meshService = meshService,
        colorScheme = colorScheme,
        timeFormatter = timeFormatter
    )
    
    // Use matrix animation only for the content
    AnimatedMatrixText(
        targetText = message.content,
        isAnimating = shouldAnimateMessage(message.id),
        color = colorScheme.onSurface,
        fontSize = TextUnit.Unspecified,
        fontWeight = if (message.sender == currentUserNickname || message.sender.startsWith("$currentUserNickname#")) {
            FontWeight.Bold
        } else {
            null
        },
        modifier = modifier
    )
}

/**
 * Find the start index of the message content (after timestamp and sender)
 */
private fun findContentStartIndex(messageText: String): Int {
    // Look for pattern like "] <@sender> " to find where content starts
    val pattern = """] <[^>]+> """.toRegex()
    val match = pattern.find(messageText)
    return match?.range?.last?.plus(1) ?: -1
}

/**
 * Find the end index of the message content (before final timestamp)
 */
private fun findContentEndIndex(messageText: String): Int {
    // Look for pattern like " [HH:mm:ss]" at the end
    val pattern = """ \[\d{2}:\d{2}:\d{2}]$""".toRegex()
    val match = pattern.find(messageText)
    return match?.range?.first ?: messageText.length
}
