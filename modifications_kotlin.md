# 🚧 Hack4Gaza Medical Records - BitChat Android Modifications

**DIRECTIVE: Do not change the core functionality of this project**  
**DIRECTIVE: Write concise, maintainable and top-tier code**

This document outlines modifications to adapt the **Android BitChat project** for offline medical record management and sync in scenarios like **Hack4Gaza**, disaster relief, or remote medical operations.

## 🎯 **CORE OBJECTIVES**

- **Extend existing chat functionality** to support patient records
- **Leverage current Noise Protocol encryption** for medical data security
- **Use existing BLE mesh networking** for offline record synchronization
- **Build on current store-and-forward architecture** for reliable delivery
- **Add governance system** for access control and data integrity

---

## 📋 **PHASE 1: FOUNDATION - Extend Current Message System**

### 1. 🏗️ **Generic Record Infrastructure**

**Extend existing `BitchatMessage` data class to support different record types:**

```kotlin
// Extend existing model in BitchatMessage.kt
enum class RecordType(val value: String) {
    CHAT_MESSAGE("chat_message"),
    CHAT_MESSAGE("chat_message"),
    PATIENT_RECORD("patient_record"),
    VOTE("vote"),
    PROPOSAL("proposal"),
    MEDICAL_UPDATE("medical_update"),
    DEVICE_STATUS("device_status")
}

@Parcelize
data class GenericRecord(
    val recordId: String,           // UUID
    val type: RecordType,
    val payload: ByteArray,         // JSON or binary data
    val authorKey: String,          // Public key fingerprint
    val timestamp: Date,
    val signature: ByteArray?,      // Ed25519 signature
    val replicationLevel: Int       // How many copies needed
) : Parcelable
```

**Action Items:**
- [ ] Extend `BitchatMessage` to include `recordType` field
- [ ] Extend `recordType` to comment style patient history as detained in PatientDetailScreen.kt, AddPatientScreen.kt, and PatientViewModel.kt
- [ ] Add `RecordStore` service using existing message storage patterns
- [ ] Integrate with current `store-and-forward` caching system
- [ ] Use existing `DeliveryTracker` for record delivery confirmation

---

## 📋 **PHASE 2: PATIENT RECORD SYSTEM**

### 2. 🏥 **Patient Record Models**

```kotlin
// New file: PatientModels.kt
@Parcelize
data class PatientRecord(
    val id: String = UUID.randomUUID().toString(),  // Auto-generated UUID
    val patientId: String,                          // Human-readable ID (P123456)
    val name: String,                               // Encrypted in transmission
    val age: Int? = null,
    val gender: String? = null,
    val bloodType: String? = null,
    val allergies: List<String> = emptyList(),
    val currentMedications: List<String> = emptyList(),
    val medicalHistory: String = "",
    val presentingComplaint: String = "",
    val treatment: String = "",
    val status: PatientStatus,
    val priority: Priority,
    val location: String? = null,
    val authorFingerprint: String,                  // Doctor/medic who created
    val lastModified: Date,
    val version: Int = 1                            // For conflict resolution
) : Parcelable

enum class PatientStatus(val value: String) {
    STABLE("stable"),
    CRITICAL("critical"),
    TREATED("treated"),
    TRANSFERRED("transferred"),
}

enum class Priority(val value: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    URGENT("urgent")
}

@Parcelize
data class MedicalUpdate(
    val id: String = UUID.randomUUID().toString(),
    val patientId: String,
    val updateType: UpdateType,
    val notes: String,
    val vitals: Vitals? = null,
    val authorFingerprint: String,
    val timestamp: Date
) : Parcelable

enum class UpdateType(val value: String) {
    ASSESSMENT("assessment"),
    TREATMENT("treatment"),
    STATUS_CHANGE("statusChange"),
    TRANSFER("transfer")
}

@Parcelize
data class Vitals(
    val bloodPressure: String? = null,
    val heartRate: Int? = null,
    val temperature: Double? = null,
    val oxygenSaturation: Int? = null,
    val painLevel: Int? = null                      // 1-10 scale
) : Parcelable
```

**Action Items:**
- [ ] Create `PatientModels.kt` with above structures
- [ ] Extend `RecordStore` to handle patient-specific queries
- [ ] Add patient ID generation and validation
- [ ] Implement conflict resolution for concurrent edits

---

## 📋 **PHASE 3: UI IMPLEMENTATION**

### 3. 📱 **New Composables and Navigation**

**Extend existing `MainActivity` with medical tabs:**

```kotlin
// Modify MainActivity.kt to add medical functionality
enum class AppMode(val title: String) {
    CHAT("Chat"),
    PATIENTS("Patients"),
    DASHBOARD("Dashboard"),
    GOVERNANCE("Governance")
}

// Add to MainActivity Composable
@Composable
fun MainContent(
    chatViewModel: ChatViewModel,
    patientViewModel: PatientViewModel,
    deviceDashboardViewModel: DeviceDashboardViewModel
) {
    var currentMode by remember { mutableStateOf(AppMode.CHAT) }
    
    // Tab navigation implementation
}
```

**New Composable Files to Create:**
- [ ] `PatientListScreen.kt` - Browse all patients
- [ ] `PatientDetailScreen.kt` - View/edit individual patient
- [ ] `AddPatientScreen.kt` - Create new patient record
- [ ] `MedicalUpdateScreen.kt` - Add updates to existing patients
- [ ] `DeviceDashboardScreen.kt` - Show peer status and sync
- [ ] `GovernanceScreen.kt` - Voting and proposals

**Action Items:**
- [ ] Add tab bar or segment control to switch between modes
- [ ] Create patient list with search/filter capabilities
- [ ] Add form validation for medical data
- [ ] Implement patient record encryption before sync

---

## 📋 **PHASE 4: DATA PERSISTENCE & SYNC**

### 4. 💾 **Extend Current Storage System**

**Build on existing `ChatViewModel` and `BluetoothMeshService`:**

```kotlin
// New file: PatientViewModel.kt
class PatientViewModel(
    application: Application,
    private val meshService: BluetoothMeshService
) : AndroidViewModel(application) {
    
    private val _patients = MutableLiveData<List<PatientRecord>>()
    val patients: LiveData<List<PatientRecord>> = _patients
    
    private val _medicalUpdates = MutableLiveData<Map<String, List<MedicalUpdate>>>()
    val medicalUpdates: LiveData<Map<String, List<MedicalUpdate>>> = _medicalUpdates
    
    private val _syncStatus = MutableLiveData<Map<String, SyncStatus>>()
    val syncStatus: LiveData<Map<String, SyncStatus>> = _syncStatus
    
    private val recordStore = RecordStore.getInstance(application.applicationContext)
    
    init {
        loadLocalPatients()
    }
    
    fun savePatient(patient: PatientRecord) {
        viewModelScope.launch {
            // Encrypt sensitive fields
            val encryptedPatient = encryptSensitiveFields(patient)
            
            // Store locally
            recordStore.save(encryptedPatient, RecordType.PATIENT_RECORD)
            
            // Sync across mesh network
            meshService.broadcastRecord(encryptedPatient)
        }
    }
    
    private fun loadLocalPatients() {
        viewModelScope.launch {
            val storedPatients = recordStore.load(RecordType.PATIENT_RECORD, PatientRecord::class.java)
            _patients.postValue(storedPatients)
        }
    }
    
    private suspend fun encryptSensitiveFields(patient: PatientRecord): PatientRecord {
        // Implementation using existing EncryptionService
        return patient // Placeholder
    }
}

// New file: RecordStore.kt
class RecordStore private constructor(private val context: Context) {
    
    companion object {
        @Volatile
        private var INSTANCE: RecordStore? = null
        
        fun getInstance(context: Context): RecordStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RecordStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Extend existing SharedPreferences/File storage
    private val prefs = context.getSharedPreferences("bitchat_records", Context.MODE_PRIVATE)
    private val recordsDir = File(context.filesDir, "medical_records")
    
    init {
        if (!recordsDir.exists()) {
            recordsDir.mkdirs()
        }
    }
    
    suspend fun <T : Parcelable> save(record: T, type: RecordType) = withContext(Dispatchers.IO) {
        // Use existing storage patterns from MessageRetentionService
    }
    
    suspend fun <T : Parcelable> load(type: RecordType, clazz: Class<T>): List<T> = withContext(Dispatchers.IO) {
        // Leverage existing data loading patterns
        emptyList()
    }
}
```

**Action Items:**
- [ ] Create `PatientViewModel` following existing `ChatViewModel` patterns
- [ ] Extend `RecordStore` using existing storage mechanisms
- [ ] Integrate with current `store-and-forward` message caching
- [ ] Add encryption for sensitive patient data

---

## 📋 **PHASE 5: DEVICE DASHBOARD & SYNC**

### 5. 📡 **Network Status & Peer Management**

**Extend existing peer tracking from `BluetoothMeshService`:**

```kotlin
// New file: DeviceDashboardViewModel.kt
class DeviceDashboardViewModel(
    application: Application,
    private val meshService: BluetoothMeshService
) : AndroidViewModel(application) {
    
    private val _connectedPeers = MutableLiveData<List<PeerInfo>>()
    val connectedPeers: LiveData<List<PeerInfo>> = _connectedPeers
    
    private val _syncProgress = MutableLiveData<Map<String, Double>>()
    val syncProgress: LiveData<Map<String, Double>> = _syncProgress
    
    private val _storageInfo = MutableLiveData<StorageInfo>()
    val storageInfo: LiveData<StorageInfo> = _storageInfo
    
    @Parcelize
    data class PeerInfo(
        val peerID: String,
        val nickname: String,
        val rssi: Int,
        val recordsShared: Int,
        val storageAvailable: Long,
        val lastSeen: Date,
        val trustLevel: TrustLevel
    ) : Parcelable
    
    @Parcelize
    data class StorageInfo(
        val totalPatients: Int = 0,
        val localStorage: Long = 0,
        val distributedCopies: Int = 0,
        val redundancyLevel: Double = 0.0
    ) : Parcelable
    
    enum class TrustLevel {
        UNKNOWN, LOW, MEDIUM, HIGH, VERIFIED
    }
}
```

**Action Items:**
- [ ] Extend existing peer management from `BluetoothMeshService`
- [ ] Add storage tracking and quota management
- [ ] Show real-time sync progress using existing delivery tracking
- [ ] Display network topology and signal strength

---

## 📋 **PHASE 6: GOVERNANCE & SECURITY**

### 6. 🗳️ **Access Control & Voting System**

**Build on existing identity system from `EncryptionService`:**

```kotlin
// New file: GovernanceModels.kt
@Parcelize
data class Proposal(
    val id: String = UUID.randomUUID().toString(),
    val type: ProposalType,
    val title: String,
    val description: String,
    val targetFingerprint: String? = null,
    val proposerFingerprint: String,
    val createdAt: Date,
    val votingDeadline: Date,
    val requiredQuorum: Int
) : Parcelable

enum class ProposalType(val value: String) {
    ADD_MEDIC("ADD_MEDIC"),
    ADD_DOCTOR("ADD_DOCTOR"),
    REMOVE_MEMBER("REMOVE_MEMBER"),
    CHANGE_PERMISSIONS("CHANGE_PERMISSIONS")
}

@Parcelize
data class Vote(
    val id: String = UUID.randomUUID().toString(),
    val proposalId: String,
    val vote: VoteChoice,
    val voterFingerprint: String,
    val timestamp: Date,
    val signature: ByteArray
) : Parcelable

enum class VoteChoice(val value: String) {
    YES("yes"),
    NO("no"),
    ABSTAIN("abstain")
}

// Extend existing identity system
enum class UserRole(val value: String) {
    DOCTOR("doctor"),
    MEDIC("medic"),
    VOLUNTEER("volunteer"),
    OBSERVER("observer");
    
    val permissions: Set<Permission>
        get() = when (this) {
            DOCTOR -> setOf(
                Permission.CREATE_PATIENT,
                Permission.EDIT_PATIENT,
                Permission.VIEW_ALL,
                Permission.VOTE,
                Permission.PROPOSE
            )
            MEDIC -> setOf(
                Permission.CREATE_PATIENT,
                Permission.EDIT_PATIENT,
                Permission.VIEW_ALL,
                Permission.VOTE
            )
            VOLUNTEER -> setOf(
                Permission.CREATE_PATIENT,
                Permission.VIEW_LIMITED
            )
            OBSERVER -> setOf(
                Permission.VIEW_LIMITED
            )
        }
}

enum class Permission(val value: String) {
    CREATE_PATIENT("createPatient"),
    EDIT_PATIENT("editPatient"),
    VIEW_ALL("viewAll"),
    VIEW_LIMITED("viewLimited"),
    VOTE("vote"),
    PROPOSE("propose")
}
```

**Action Items:**
- [ ] Extend existing `EncryptionService` identity system
- [ ] Add role-based access control using public key fingerprints
- [ ] Implement proposal creation and voting UI
- [ ] Use existing signature verification for vote integrity

---

## 📋 **PHASE 7: SECURITY & PRIVACY**

### 7. 🔐 **Medical Data Protection**

**Leverage existing encryption infrastructure:**

```kotlin
// Extend EncryptionService.kt
class MedicalEncryptionService(
    private val context: Context,
    private val encryptionService: EncryptionService
) {
    
    fun encryptPatientData(patient: PatientRecord, forRole: UserRole): ByteArray? {
        // Encrypt based on access level
        return when (forRole) {
            UserRole.DOCTOR, UserRole.MEDIC -> encryptFull(patient)
            UserRole.VOLUNTEER -> encryptLimited(patient) // Hide sensitive info
            UserRole.OBSERVER -> encryptBasic(patient)   // Only basic stats
        }
    }
    
    private fun encryptFull(patient: PatientRecord): ByteArray? {
        // Full encryption using existing EncryptionService
        return null // Placeholder
    }
    
    private fun encryptLimited(patient: PatientRecord): ByteArray? {
        // Limited encryption hiding sensitive fields
        return null // Placeholder
    }
    
    private fun encryptBasic(patient: PatientRecord): ByteArray? {
        // Basic encryption with only statistics
        return null // Placeholder
    }
}

// Add panic wipe functionality
class PatientViewModel {
    fun panicWipe() {
        viewModelScope.launch {
            // Clear all patient data from memory and storage
            _patients.postValue(emptyList())
            _medicalUpdates.postValue(emptyMap())
            recordStore.deleteAllPatientData()
            
            // Use existing emergency wipe patterns from ChatViewModel
            // (Similar to triple-tap functionality)
        }
    }
}
```

**Action Items:**
- [ ] Add medical data encryption using existing Noise protocols
- [ ] Implement role-based data filtering
- [ ] Extend existing panic wipe to include medical data
- [ ] Add data anonymization for non-critical roles

---

## 📋 **PHASE 8: TESTING & RELIABILITY**

### 8. 🧪 **Development Tools & Testing**

**Extend existing testing infrastructure:**

```kotlin
// Add to existing test files or create MedicalTestingActivity.kt
class MedicalTestingActivity : ComponentActivity() {
    
    private lateinit var patientViewModel: PatientViewModel
    private lateinit var meshService: BluetoothMeshService
    
    fun testPatientSync() {
        // Create test patients and verify sync
    }
    
    fun testGovernanceVoting() {
        // Simulate voting scenarios
    }
    
    fun testPanicWipe() {
        // Verify complete data deletion
    }
    
    fun simulateNetworkPartition() {
        // Test sync recovery after connectivity loss
    }
}
```

**Action Items:**
- [ ] Add medical record sync testing
- [ ] Create patient data generation tools
- [ ] Test governance voting scenarios
- [ ] Verify encryption/decryption across peers

---

## ✅ **IMPLEMENTATION PRIORITY**

### **Phase 1-2 (Week 1): Core Infrastructure**
1. Extend message system for generic records
2. Create patient record models
3. Basic patient CRUD operations

### **Phase 3-4 (Week 2): UI & Storage**
1. Patient list and detail views
2. Data persistence and sync
3. Basic medical record encryption

### **Phase 5-6 (Week 3): Advanced Features**
1. Device dashboard and network status
2. Governance and voting system
3. Role-based access control

### **Phase 7-8 (Week 4): Security & Testing**
1. Advanced encryption and privacy
2. Testing tools and reliability
3. Documentation and deployment

---

## 🎯 **SUCCESS METRICS**

- [ ] **Patient records sync** reliably across 5+ devices
- [ ] **Offline operation** for 24+ hours with data integrity
- [ ] **Role-based access** working with governance voting
- [ ] **Panic wipe** clears all medical data in <5 seconds
- [ ] **Mesh network** maintains connectivity over 300m range
- [ ] **Data encryption** protects patient privacy end-to-end

---

## 📝 **TECHNICAL NOTES**

- **Leverage existing** `BitchatMessage` and `BluetoothMeshService` architecture
- **Extend current** encryption using `EncryptionService`
- **Build on** existing store-and-forward caching mechanisms  
- **Follow established** patterns from `ChatViewModel` and `MainActivity`
- **Maintain compatibility** with existing chat functionality
- **Use Jetpack Compose** for new UI components following Android best practices
- **Implement proper lifecycle management** with ViewModels and LiveData/StateFlow
- **Follow Android security guidelines** for sensitive medical data storage

This approach ensures we **preserve all existing functionality** while adding powerful medical record capabilities for emergency and disaster relief scenarios using modern Android development practices.