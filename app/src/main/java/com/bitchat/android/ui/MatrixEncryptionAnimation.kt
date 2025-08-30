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
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random
import com.bitchat.android.ui.theme.BASE_FONT_SIZE

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
    // Continuous animation: loop random characters until isAnimating becomes false
    var animatedText by remember(targetText) { mutableStateOf(targetText) }

    LaunchedEffect(isAnimating, targetText) {
        if (isAnimating && targetText.isNotEmpty()) {
            val encryptedChars = "!@#$%^&*()_+-=[]{}|;:,.<>?~`".toCharArray()
            while (true) {
                val frame = CharArray(targetText.length) {
                    encryptedChars[Random.nextInt(encryptedChars.size)]
                }
                animatedText = String(frame)
                delay(80L)
            }
        } else {
            animatedText = targetText
        }
    }

    val styledText = buildAnnotatedString {
        animatedText.forEach { char ->
            withStyle(
                style = SpanStyle(
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = fontWeight
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
 * Formats message like a normal message but animates only the content portion
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
    
    // Update animated content when animation state changes
    LaunchedEffect(isAnimating, message.content) {
        if (isAnimating && message.content.isNotEmpty()) {
            val encryptedChars = "!@#$%^&*()_+-=[]{}|;:,.<>?~`".toCharArray()
            val baseLength = message.content.length
            
            // Loop animation continuously until PoW stops (LaunchedEffect will cancel on recomposition)
            while (true) {
                // Generate a new random frame of the same length as the real content
                val frame = CharArray(baseLength) {
                    encryptedChars[Random.nextInt(encryptedChars.size)]
                }
                animatedContent = String(frame)
                
                // Small delay for smooth animation; adjust if needed
                delay(80L)
            }
        } else {
            // Not animating, show final content
            animatedContent = message.content
        }
    }
    
    // Create a temporary message with animated content for formatting
    val animatedMessage = message.copy(content = animatedContent)
    
    // Use the EXACT same formatting function as normal messages, including timestamp
    val annotatedText = formatMessageAsAnnotatedString(
        message = animatedMessage,
        currentUserNickname = currentUserNickname,
        meshService = meshService,
        colorScheme = colorScheme,
        timeFormatter = timeFormatter
    )
    
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


