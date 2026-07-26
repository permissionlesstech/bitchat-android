package com.bitchat.android.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import kotlin.random.Random

/**
 * Animation state for individual characters
 */
private enum class CharacterAnimationState {
    ENCRYPTED,    // Showing random encrypted characters
    FINAL         // Showing final decrypted character
}

/**
 * Check if a message should be animated based on its mining state
 */
@Composable
fun shouldAnimateMessage(messageId: String): Boolean {
    val miningMessages by PoWMiningTracker.miningMessages.collectAsStateWithLifecycle()
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
 * Shows the active PoW animation inside the same two-row layout used by static text messages.
 */
@Composable
fun MessageWithMatrixAnimation(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: MeshService,
    colorScheme: ColorScheme,
    timeFormatter: SimpleDateFormat,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((BitchatMessage) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    AnimatedMessageDisplay(
        message = message,
        currentUserNickname = currentUserNickname,
        meshService = meshService,
        colorScheme = colorScheme,
        timeFormatter = timeFormatter,
        onNicknameClick = onNicknameClick,
        onMessageLongPress = onMessageLongPress,
        modifier = modifier,
    )
}

/**
 * Animates only the body content; sender, metadata, gestures, and spacing remain stable.
 */
@Composable
private fun AnimatedMessageDisplay(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: MeshService,
    colorScheme: ColorScheme,
    timeFormatter: SimpleDateFormat,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((BitchatMessage) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var animatedContent by remember(message.id, message.content) {
        mutableStateOf(message.content)
    }
    
    // Character-by-character animation state like the JavaScript version
    var characterStates by remember(message.id, message.content) {
        mutableStateOf(message.content.map { char -> 
            if (char == ' ') CharacterAnimationState.FINAL else CharacterAnimationState.ENCRYPTED 
        })
    }
    
    LaunchedEffect(message.id, message.content) {
        if (message.content.isEmpty()) return@LaunchedEffect

        val encryptedChars = "!@$%^&*()_+-=[]{}|;:,<>?".toCharArray()

        // Start character animations with staggered delays (like JS version).
        message.content.forEachIndexed { index, targetChar ->
            if (targetChar != ' ') {
                launch {
                    delay(index * 50L)

                    while (true) {
                        while (characterStates.getOrNull(index) == CharacterAnimationState.ENCRYPTED) {
                            val newContent = animatedContent.toCharArray()
                            if (index < newContent.size) {
                                newContent[index] = encryptedChars[Random.nextInt(encryptedChars.size)]
                                animatedContent = String(newContent)
                            }

                            delay(100L)

                            if (Random.nextFloat() < 0.1f) {
                                val finalContent = animatedContent.toCharArray()
                                if (index < finalContent.size) {
                                    finalContent[index] = targetChar
                                    animatedContent = String(finalContent)
                                }

                                val finalStates = characterStates.toMutableList()
                                finalStates[index] = CharacterAnimationState.FINAL
                                characterStates = finalStates
                                break
                            }
                        }

                        delay(2000L)

                        val resetStates = characterStates.toMutableList()
                        resetStates[index] = CharacterAnimationState.ENCRYPTED
                        characterStates = resetStates
                    }
                }
            }
        }
    }

    TextMessageLayout(
        message = message,
        currentUserNickname = currentUserNickname,
        meshService = meshService,
        colorScheme = colorScheme,
        timeFormatter = timeFormatter,
        onNicknameClick = onNicknameClick,
        onMessageLongPress = onMessageLongPress,
        modifier = modifier,
        bodyContent = animatedContent,
    )
}
