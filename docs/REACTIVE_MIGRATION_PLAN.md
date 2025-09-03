# Modern Android Reactive Architecture Migration Plan

## Phase 1: Replace LiveData with StateFlow/SharedFlow (Week 1)

### 1.1 Create Modern State Management

Replace the current ChatState with StateFlow-based reactive state:

```kotlin
// app/src/main/java/com/bitchat/android/ui/state/ChatUIState.kt
@kotlinx.serialization.Serializable
data class ChatUIState(
    // Core state
    val messages: List<BitchatMessage> = emptyList(),
    val connectedPeers: List<String> = emptyList(),
    val nickname: String = "",
    val isConnected: Boolean = false,
    
    // Private chats
    val privateChats: Map<String, List<BitchatMessage>> = emptyMap(),
    val selectedPrivateChatPeer: String? = null,
    val unreadPrivateMessages: Set<String> = emptySet(),
    
    // Channels
    val joinedChannels: Set<String> = emptySet(),
    val currentChannel: String? = null,
    val channelMessages: Map<String, List<BitchatMessage>> = emptyMap(),
    val unreadChannelMessages: Map<String, Int> = emptyMap(),
    
    // UI state
    val showSidebar: Boolean = false,
    val showPasswordPrompt: Boolean = false,
    val passwordPromptChannel: String? = null,
    
    // Peer metadata (reactive)
    val peerNicknames: Map<String, String> = emptyMap(),
    val peerRSSI: Map<String, Int> = emptyMap(),
    val peerSessionStates: Map<String, SessionState> = emptyMap(),
    val peerFingerprints: Map<String, String> = emptyMap(),
    val favoritePeers: Set<String> = emptySet()
) {
    // Computed properties
    val hasUnreadChannels: Boolean get() = unreadChannelMessages.values.any { it > 0 }
    val hasUnreadPrivateMessages: Boolean get() = unreadPrivateMessages.isNotEmpty()
    
    // Helper functions for safe access
    fun getPeerDisplayName(peerID: String): String = peerNicknames[peerID] ?: peerID
    fun isPeerFavorite(peerID: String): Boolean {
        val fingerprint = peerFingerprints[peerID] ?: return false
        return favoritePeers.contains(fingerprint)
    }
    fun getPeerSessionState(peerID: String): SessionState = peerSessionStates[peerID] ?: SessionState.UNINITIALIZED
}

enum class SessionState {
    UNINITIALIZED, HANDSHAKING, ESTABLISHED, FAILED
}
```

### 1.2 Modern ViewModel with StateFlow

```kotlin
// app/src/main/java/com/bitchat/android/ui/ChatViewModel.kt
class ChatViewModel(
    application: Application,
    private val meshService: BluetoothMeshService,
    private val repository: ChatRepository // New repository layer
) : AndroidViewModel(application) {

    // Single source of truth - StateFlow instead of multiple LiveData
    private val _uiState = MutableStateFlow(ChatUIState())
    val uiState: StateFlow<ChatUIState> = _uiState.asStateFlow()
    
    // Event streams for one-time events
    private val _uiEvents = MutableSharedFlow<ChatUIEvent>()
    val uiEvents: SharedFlow<ChatUIEvent> = _uiEvents.asSharedFlow()
    
    init {
        // Set up reactive streams from repository
        setupReactiveStreams()
    }
    
    private fun setupReactiveStreams() {
        viewModelScope.launch {
            // Combine multiple data streams into single UI state
            combine(
                repository.messages,
                repository.connectedPeers,
                repository.peerMetadata,
                repository.channelData,
                repository.privateChats
            ) { messages, peers, metadata, channels, privateChats ->
                _uiState.value.copy(
                    messages = messages,
                    connectedPeers = peers.peers,
                    peerNicknames = metadata.nicknames,
                    peerRSSI = metadata.rssi,
                    peerSessionStates = metadata.sessionStates,
                    peerFingerprints = metadata.fingerprints,
                    joinedChannels = channels.joinedChannels,
                    channelMessages = channels.messages,
                    privateChats = privateChats.chats,
                    unreadPrivateMessages = privateChats.unreadSenders
                )
            }.collect { newState ->
                _uiState.value = newState
            }
        }
    }
    
    // Action functions with proper state management
    fun toggleFavorite(peerID: String) {
        viewModelScope.launch {
            repository.toggleFavorite(peerID)
            // State automatically updates through reactive stream
        }
    }
    
    fun sendMessage(content: String) {
        viewModelScope.launch {
            val result = repository.sendMessage(content, _uiState.value)
            if (result.isFailure) {
                _uiEvents.emit(ChatUIEvent.ShowError(result.exceptionOrNull()?.message ?: "Failed to send message"))
            }
        }
    }
}

sealed class ChatUIEvent {
    data class ShowError(val message: String) : ChatUIEvent()
    data class NavigateToPrivateChat(val peerID: String) : ChatUIEvent()
    object ShowHapticFeedback : ChatUIEvent()
}
```

## Phase 2: Repository Pattern with Reactive Streams (Week 2)

### 2.1 Create Repository Layer

```kotlin
// app/src/main/java/com/bitchat/android/data/ChatRepository.kt
@Singleton
class ChatRepository @Inject constructor(
    private val meshService: BluetoothMeshService,
    private val dataManager: DataManager,
    private val peerMetadataCollector: PeerMetadataCollector
) {
    // Reactive data streams
    val messages: Flow<List<BitchatMessage>> = meshService.messagesFlow
    val connectedPeers: Flow<PeerListState> = meshService.peerUpdatesFlow
    val peerMetadata: Flow<PeerMetadata> = peerMetadataCollector.metadataFlow
    val channelData: Flow<ChannelState> = meshService.channelUpdatesFlow
    val privateChats: Flow<PrivateChatState> = meshService.privateChatUpdatesFlow
    
    suspend fun toggleFavorite(peerID: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val fingerprint = peerMetadataCollector.getFingerprintForPeer(peerID)
                ?: return@withContext Result.failure(Exception("No fingerprint found for peer"))
            
            if (dataManager.isFavorite(fingerprint)) {
                dataManager.removeFavorite(fingerprint)
            } else {
                dataManager.addFavorite(fingerprint)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun sendMessage(content: String, currentState: ChatUIState): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            when {
                currentState.selectedPrivateChatPeer != null -> {
                    meshService.sendPrivateMessage(content, currentState.selectedPrivateChatPeer, "")
                }
                currentState.currentChannel != null -> {
                    meshService.sendChannelMessage(content, currentState.currentChannel)
                }
                else -> {
                    meshService.sendPublicMessage(content)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

data class PeerMetadata(
    val nicknames: Map<String, String>,
    val rssi: Map<String, Int>,  
    val sessionStates: Map<String, SessionState>,
    val fingerprints: Map<String, String>
)
```

### 2.2 Reactive Data Collectors

```kotlin
// app/src/main/java/com/bitchat/android/data/PeerMetadataCollector.kt
@Singleton
class PeerMetadataCollector @Inject constructor(
    private val meshService: BluetoothMeshService,
    private val fingerprintManager: PeerFingerprintManager
) {
    private val _metadataFlow = MutableStateFlow(PeerMetadata())
    val metadataFlow: StateFlow<PeerMetadata> = _metadataFlow.asStateFlow()
    
    init {
        // Collect from multiple sources and emit combined metadata
        CoroutineScope(Dispatchers.IO).launch {
            // Use proper reactive streams instead of polling
            combine(
                meshService.peerNicknamesFlow,
                meshService.peerRSSIFlow, 
                meshService.sessionStatesFlow,
                fingerprintManager.fingerprintsFlow
            ) { nicknames, rssi, sessions, fingerprints ->
                PeerMetadata(
                    nicknames = nicknames,
                    rssi = rssi,
                    sessionStates = sessions,
                    fingerprints = fingerprints
                )
            }.collect { metadata ->
                _metadataFlow.value = metadata
            }
        }
    }
}
```

## Phase 3: Reactive Mesh Service (Week 3)

### 3.1 Convert BluetoothMeshService to Reactive

```kotlin
// app/src/main/java/com/bitchat/android/mesh/BluetoothMeshService.kt
class BluetoothMeshService {
    // Replace delegate pattern with Flow emissions
    private val _messagesFlow = MutableSharedFlow<List<BitchatMessage>>()
    val messagesFlow: SharedFlow<List<BitchatMessage>> = _messagesFlow.asSharedFlow()
    
    private val _peerUpdatesFlow = MutableStateFlow(PeerListState())
    val peerUpdatesFlow: StateFlow<PeerListState> = _peerUpdatesFlow.asStateFlow()
    
    private val _peerNicknamesFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    val peerNicknamesFlow: StateFlow<Map<String, String>> = _peerNicknamesFlow.asStateFlow()
    
    private val _sessionStatesFlow = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val sessionStatesFlow: StateFlow<Map<String, SessionState>> = _sessionStatesFlow.asStateFlow()
    
    // When peer connects/disconnects
    private fun onPeerListUpdated(peers: List<String>) {
        _peerUpdatesFlow.value = _peerUpdatesFlow.value.copy(peers = peers)
        
        // Update session states for all peers
        val sessionStates = peers.associateWith { peerID ->
            encryptionService.getSessionState(peerID)
        }
        _sessionStatesFlow.value = sessionStates
    }
    
    // When message received  
    private fun handleIncomingMessage(message: BitchatMessage) {
        // Emit to reactive stream instead of delegate
        viewModelScope.launch {
            _messagesFlow.emit(messageManager.getAllMessages())
        }
    }
}
```

## Phase 4: Modern Compose UI (Week 4)

### 4.1 Reactive Compose Components

```kotlin
// app/src/main/java/com/bitchat/android/ui/ChatScreen.kt
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    
    // Handle one-time events
    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is ChatUIEvent.ShowError -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                is ChatUIEvent.ShowHapticFeedback -> {
                    // Trigger haptic feedback
                }
            }
        }
    }
    
    ChatContent(
        uiState = uiState,
        onAction = viewModel::handleAction // Single action handler
    )
}

@Composable
private fun ChatContent(
    uiState: ChatUIState,
    onAction: (ChatAction) -> Unit
) {
    // All UI state is reactive and comes from single source
    Column {
        ChatHeader(
            selectedPeer = uiState.selectedPrivateChatPeer,
            currentChannel = uiState.currentChannel,
            nickname = uiState.nickname,
            peerNicknames = uiState.peerNicknames,
            sessionStates = uiState.peerSessionStates,
            favoritePeers = uiState.favoritePeers,
            peerFingerprints = uiState.peerFingerprints,
            onAction = onAction
        )
        
        MessageList(
            messages = when {
                uiState.selectedPrivateChatPeer != null -> 
                    uiState.privateChats[uiState.selectedPrivateChatPeer] ?: emptyList()
                uiState.currentChannel != null -> 
                    uiState.channelMessages[uiState.currentChannel] ?: emptyList()
                else -> uiState.messages
            },
            onAction = onAction
        )
        
        MessageInput(
            onSendMessage = { content -> onAction(ChatAction.SendMessage(content)) }
        )
    }
    
    // Sidebar
    if (uiState.showSidebar) {
        SidebarContent(
            connectedPeers = uiState.connectedPeers,
            peerNicknames = uiState.peerNicknames,
            peerRSSI = uiState.peerRSSI,
            favoritePeers = uiState.favoritePeers,
            peerFingerprints = uiState.peerFingerprints,
            onAction = onAction
        )
    }
}

// Centralized action handling
sealed class ChatAction {
    data class SendMessage(val content: String) : ChatAction()
    data class ToggleFavorite(val peerID: String) : ChatAction()
    data class StartPrivateChat(val peerID: String) : ChatAction()
    data class JoinChannel(val channel: String) : ChatAction()
    object ShowSidebar : ChatAction()
    object HideSidebar : ChatAction()
}
```

### 4.2 Reactive Header Component

```kotlin
// app/src/main/java/com/bitchat/android/ui/components/ChatHeader.kt
@Composable
fun ChatHeader(
    selectedPeer: String?,
    currentChannel: String?,
    nickname: String,
    peerNicknames: Map<String, String>,
    sessionStates: Map<String, SessionState>,
    favoritePeers: Set<String>,
    peerFingerprints: Map<String, String>,
    onAction: (ChatAction) -> Unit
) {
    when {
        selectedPeer != null -> {
            PrivateChatHeader(
                peerID = selectedPeer,
                peerNickname = peerNicknames[selectedPeer] ?: selectedPeer,
                sessionState = sessionStates[selectedPeer] ?: SessionState.UNINITIALIZED,
                isFavorite = isPeerFavorite(selectedPeer, peerFingerprints, favoritePeers),
                onBackClick = { onAction(ChatAction.EndPrivateChat) },
                onToggleFavorite = { onAction(ChatAction.ToggleFavorite(selectedPeer)) }
            )
        }
        // ... other header states
    }
}

// Pure function for favorite computation - no side effects
private fun isPeerFavorite(
    peerID: String,
    fingerprints: Map<String, String>,
    favorites: Set<String>
): Boolean {
    val fingerprint = fingerprints[peerID] ?: return false
    return favorites.contains(fingerprint)
}
```

## Phase 5: Dependency Injection (Week 5)

### 5.1 Hilt Setup

```kotlin
// app/src/main/java/com/bitchat/android/di/AppModule.kt
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideBluetoothMeshService(
        @ApplicationContext context: Context
    ): BluetoothMeshService = BluetoothMeshService(context)
    
    @Provides  
    @Singleton
    fun provideChatRepository(
        meshService: BluetoothMeshService,
        dataManager: DataManager,
        peerMetadataCollector: PeerMetadataCollector
    ): ChatRepository = ChatRepository(meshService, dataManager, peerMetadataCollector)
    
    @Provides
    @Singleton
    fun provideDataManager(@ApplicationContext context: Context): DataManager = DataManager(context)
}

// app/src/main/java/com/bitchat/android/di/ViewModelModule.kt  
@Module
@InstallIn(ViewModelComponent::class)
object ViewModelModule {
    
    @Provides
    fun provideChatViewModel(
        application: Application,
        meshService: BluetoothMeshService,
        repository: ChatRepository
    ): ChatViewModel = ChatViewModel(application, meshService, repository)
}
```

## Benefits of This Architecture

### 🚀 **Performance Improvements:**
- **No Polling**: Reactive streams eliminate 1-second polling loops
- **Efficient Updates**: Only UI components that need updates get recomposed
- **Memory Efficient**: StateFlow is more memory efficient than multiple LiveData objects

### 🏗️ **Architectural Benefits:** 
- **Single Source of Truth**: All state flows from one place
- **Testable**: Pure functions and dependency injection make testing easy
- **Maintainable**: Clear separation of concerns and reactive data flow
- **Scalable**: Easy to add new features without complex state synchronization

### 🔄 **Reactive Benefits:**
- **Real-time Updates**: UI responds instantly to data changes
- **Declarative**: UI describes what it should look like, not how to update
- **Predictable**: State changes flow in one direction
- **Debuggable**: Easy to trace state changes and debug issues

## Migration Strategy

1. **Week 1**: Replace ChatState with StateFlow-based state
2. **Week 2**: Implement repository pattern and reactive data sources  
3. **Week 3**: Convert mesh service to emit reactive streams
4. **Week 4**: Update Compose UI to use reactive state
5. **Week 5**: Add dependency injection and clean up old code

This migration will give you a modern, performant, and maintainable Android reactive architecture that follows current best practices.
