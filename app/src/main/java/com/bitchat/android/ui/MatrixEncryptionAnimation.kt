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
            // Start with encrypted content
            val encryptedChars = "!@#$%^&*()_+-=[]{}|;:,.<>?~`".toCharArray()
            
            // Animate each character gradually
            message.content.forEachIndexed { index, targetChar ->
                launch {
                    // Stagger character animations
                    delay(index * 50L)
                    
                    val animationDuration = Random.nextLong(1000, 3000)
                    val startTime = System.currentTimeMillis()
                    
                    // Phase 1: Random encrypted characters
                    while (System.currentTimeMillis() - startTime < animationDuration) {
                        val randomChar = encryptedChars[Random.nextInt(encryptedChars.size)]
                        val currentText = animatedContent.toCharArray()
                        if (index < currentText.size) {
                            currentText[index] = randomChar
                            animatedContent = String(currentText)
                        }
                        delay(100)
                    }
                    
                    // Phase 2: Reveal final character
                    val finalText = animatedContent.toCharArray()
                    if (index < finalText.size) {
                        finalText[index] = targetChar
                        animatedContent = String(finalText)
                    }
                }
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


