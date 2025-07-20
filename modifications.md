# 🚧 Hack4Gaza Medical Records - BitChat iOS Modifications

**DIRECTIVE: Do not change the core functionality of this project**  
**DIRECTIVE: Write concise, maintainable and top-tier code**

This document outlines modifications to adapt the **iOS BitChat project** for offline medical record management and sync in scenarios like **Hack4Gaza**, disaster relief, or remote medical operations.

## 🎯 **CORE OBJECTIVES**

- **Extend existing chat functionality** to support patient records
- **Leverage current Noise Protocol encryption** for medical data security
- **Use existing BLE mesh networking** for offline record synchronization
- **Build on current store-and-forward architecture** for reliable delivery
- **Add governance system** for access control and data integrity

---

## 📋 **PHASE 1: FOUNDATION - Extend Current Message System**

### 1. 🏗️ **Generic Record Infrastructure**

**Extend existing `BitchatMessage` protocol to support different record types:**

```swift
// Extend existing protocol in BitchatProtocol.swift
enum RecordType: String, Codable {
    case chatMessage = "chat_message"
    case patientRecord = "patient_record" 
    case vote = "vote"
    case proposal = "proposal"
    case medicalUpdate = "medical_update"
    case deviceStatus = "device_status"
}

struct GenericRecord: Codable {
    let recordId: String            // UUID
    let type: RecordType
    let payload: Data               // JSON or binary data
    let authorKey: String           // Public key fingerprint
    let timestamp: Date
    let signature: Data?            // Ed25519 signature
    let replicationLevel: Int       // How many copies needed
}
```

**Action Items:**
- [ ] Extend `BitchatMessage` to include `recordType` field
- [ ] Add `RecordStore` service using existing message storage patterns
- [ ] Integrate with current `store-and-forward` caching system
- [ ] Use existing `DeliveryTracker` for record delivery confirmation

---

## 📋 **PHASE 2: PATIENT RECORD SYSTEM**

### 2. 🏥 **Patient Record Models**

```swift
// New file: PatientModels.swift
struct PatientRecord: Codable, Identifiable {
    let id: String                  // Auto-generated UUID
    let patientId: String           // Human-readable ID (P123456)
    let name: String                // Encrypted in transmission
    let age: Int?
    let gender: String?
    let bloodType: String?
    let allergies: [String]
    let currentMedications: [String]
    let medicalHistory: String
    let presentingComplaint: String
    let treatment: String
    let status: PatientStatus
    let priority: Priority
    let location: String?
    let authorFingerprint: String   // Doctor/medic who created
    let lastModified: Date
    let version: Int                // For conflict resolution
}

enum PatientStatus: String, Codable, CaseIterable {
    case stable, critical, treated, transferred, deceased
}

enum Priority: String, Codable, CaseIterable {
    case low, medium, high, urgent
}

struct MedicalUpdate: Codable {
    let id: String
    let patientId: String
    let updateType: UpdateType
    let notes: String
    let vitals: Vitals?
    let authorFingerprint: String
    let timestamp: Date
}

enum UpdateType: String, Codable {
    case assessment, treatment, statusChange, transfer
}

struct Vitals: Codable {
    let bloodPressure: String?
    let heartRate: Int?
    let temperature: Double?
    let oxygenSaturation: Int?
    let painLevel: Int?            // 1-10 scale
}
```

**Action Items:**
- [ ] Create `PatientModels.swift` with above structures
- [ ] Extend `RecordStore` to handle patient-specific queries
- [ ] Add patient ID generation and validation
- [ ] Implement conflict resolution for concurrent edits

---

## 📋 **PHASE 3: UI IMPLEMENTATION**

### 3. 📱 **New Views and Navigation**

**Extend existing `ContentView` with medical tabs:**

```swift
// Modify ContentView.swift to add medical functionality
enum AppMode: String, CaseIterable {
    case chat = "Chat"
    case patients = "Patients"
    case dashboard = "Dashboard"
    case governance = "Governance"
}

// Add to ContentView
@State private var currentMode: AppMode = .chat
@StateObject private var patientViewModel = PatientViewModel()
@StateObject private var deviceDashboardViewModel = DeviceDashboardViewModel()
```

**New View Files to Create:**
- [ ] `PatientListView.swift` - Browse all patients
- [ ] `PatientDetailView.swift` - View/edit individual patient
- [ ] `AddPatientView.swift` - Create new patient record
- [ ] `MedicalUpdateView.swift` - Add updates to existing patients
- [ ] `DeviceDashboardView.swift` - Show peer status and sync
- [ ] `GovernanceView.swift` - Voting and proposals

**Action Items:**
- [ ] Add tab bar or segment control to switch between modes
- [ ] Create patient list with search/filter capabilities
- [ ] Add form validation for medical data
- [ ] Implement patient record encryption before sync

---

## 📋 **PHASE 4: DATA PERSISTENCE & SYNC**

### 4. 💾 **Extend Current Storage System**

**Build on existing `ChatViewModel` and `BluetoothMeshService`:**

```swift
// New file: PatientViewModel.swift
class PatientViewModel: ObservableObject {
    @Published var patients: [PatientRecord] = []
    @Published var medicalUpdates: [String: [MedicalUpdate]] = [:]
    @Published var syncStatus: [String: SyncStatus] = [:]
    
    private let recordStore = RecordStore.shared
    private let meshService: BluetoothMeshService
    
    // Leverage existing mesh service from ChatViewModel
    init(meshService: BluetoothMeshService) {
        self.meshService = meshService
        loadLocalPatients()
    }
    
    func savePatient(_ patient: PatientRecord) {
        // Encrypt sensitive fields
        let encryptedPatient = encryptSensitiveFields(patient)
        
        // Store locally
        recordStore.save(encryptedPatient, type: .patientRecord)
        
        // Sync across mesh network
        meshService.broadcastRecord(encryptedPatient)
    }
}

// New file: RecordStore.swift
class RecordStore {
    static let shared = RecordStore()
    
    // Extend existing UserDefaults/Keychain storage
    private let userDefaults = UserDefaults.standard
    private let keychainManager = KeychainManager.shared
    
    func save<T: Codable>(_ record: T, type: RecordType) {
        // Use existing storage patterns from ChatViewModel
    }
    
    func load<T: Codable>(type: RecordType, as: T.Type) -> [T] {
        // Leverage existing data loading patterns
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

```swift
// New file: DeviceDashboardViewModel.swift
class DeviceDashboardViewModel: ObservableObject {
    @Published var connectedPeers: [PeerInfo] = []
    @Published var syncProgress: [String: Double] = [:]
    @Published var storageInfo: StorageInfo = StorageInfo()
    
    private let meshService: BluetoothMeshService
    
    struct PeerInfo {
        let peerID: String
        let nickname: String
        let rssi: Int
        let recordsShared: Int
        let storageAvailable: Int64
        let lastSeen: Date
        let trustLevel: TrustLevel
    }
    
    struct StorageInfo {
        let totalPatients: Int
        let localStorage: Int64
        let distributedCopies: Int
        let redundancyLevel: Double
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

**Build on existing identity system from `NoiseEncryptionService`:**

```swift
// New file: GovernanceModels.swift
struct Proposal: Codable {
    let id: String
    let type: ProposalType
    let title: String
    let description: String
    let targetFingerprint: String?
    let proposerFingerprint: String
    let createdAt: Date
    let votingDeadline: Date
    let requiredQuorum: Int
}

enum ProposalType: String, Codable {
    case addMedic = "ADD_MEDIC"
    case addDoctor = "ADD_DOCTOR"
    case removeMember = "REMOVE_MEMBER"
    case changePermissions = "CHANGE_PERMISSIONS"
}

struct Vote: Codable {
    let id: String
    let proposalId: String
    let vote: VoteChoice
    let voterFingerprint: String
    let timestamp: Date
    let signature: Data
}

enum VoteChoice: String, Codable {
    case yes, no, abstain
}

// Extend existing identity system
enum UserRole: String, Codable {
    case doctor, medic, volunteer, observer
    
    var permissions: Set<Permission> {
        switch self {
        case .doctor: return [.createPatient, .editPatient, .viewAll, .vote, .propose]
        case .medic: return [.createPatient, .editPatient, .viewAll, .vote]
        case .volunteer: return [.createPatient, .viewLimited]
        case .observer: return [.viewLimited]
        }
    }
}

enum Permission: String, Codable {
    case createPatient, editPatient, viewAll, viewLimited, vote, propose
}
```

**Action Items:**
- [ ] Extend existing `NoiseEncryptionService` identity system
- [ ] Add role-based access control using public key fingerprints
- [ ] Implement proposal creation and voting UI
- [ ] Use existing signature verification for vote integrity

---

## 📋 **PHASE 7: SECURITY & PRIVACY**

### 7. 🔐 **Medical Data Protection**

**Leverage existing encryption infrastructure:**

```swift
// Extend NoiseChannelEncryption.swift
extension NoiseChannelEncryption {
    func encryptPatientData(_ patient: PatientRecord, for role: UserRole) -> Data? {
        // Encrypt based on access level
        switch role {
        case .doctor, .medic:
            return encryptFull(patient)
        case .volunteer:
            return encryptLimited(patient) // Hide sensitive info
        case .observer:
            return encryptBasic(patient)   // Only basic stats
        }
    }
}

// Add panic wipe functionality
extension PatientViewModel {
    func panicWipe() {
        // Clear all patient data from memory and storage
        patients.removeAll()
        medicalUpdates.removeAll()
        RecordStore.shared.deleteAllPatientData()
        
        // Use existing emergency wipe from ChatViewModel
        // (Already implemented with triple-tap)
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

```swift
// Add to existing NoiseTestingView.swift
struct MedicalTestingView: View {
    func testPatientSync() {
        // Create test patients and verify sync
    }
    
    func testGovernanceVoting() {
        // Simulate voting scenarios
    }
    
    func testPanicWipe() {
        // Verify complete data deletion
    }
    
    func simulateNetworkPartition() {
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
- **Extend current** encryption using `NoiseEncryptionService`
- **Build on** existing store-and-forward caching mechanisms  
- **Follow established** patterns from `ChatViewModel` and `ContentView`
- **Maintain compatibility** with existing chat functionality

This approach ensures we **preserve all existing functionality** while adding powerful medical record capabilities for emergency and disaster relief scenarios.