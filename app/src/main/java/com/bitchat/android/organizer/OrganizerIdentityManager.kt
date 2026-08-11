package com.bitchat.android.organizer

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.bitchat.android.protocol.BitchatPacket
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

object OrganizerIdentityManager {
    private const val TAG = "OrganizerIdentityManager"
    
    // The canonical public key for the Campus Festival 2026 Organizer.
    // Every attendee app uses this to verify official announcements.
    private const val ORGANIZER_PUB_KEY_HEX = "b70c287c5292cb599bf7cb455da47dc1536b586ec6b8a41ef771fc5da789baf8"
    
    // Passcode for accessing the organizer mode UI.
    private const val ORGANIZER_PASSCODE = "FESTIVAL2026"
    private const val MAX_PASSCODE_ATTEMPTS = 5
    private const val LOCKOUT_DURATION_MS = 30_000L // 30 seconds lockout

    private var failedPasscodeAttempts = 0
    private var lockoutUntilMs = 0L

    private const val PREFS_FILE = "organizer_secure_prefs"
    private const val KEY_ORGANIZER_PRIV = "organizer_priv_key_base64"

    private lateinit var prefs: SharedPreferences
    private var privateKeyParams: Ed25519PrivateKeyParameters? = null
    private val publicKeyParams: Ed25519PublicKeyParameters by lazy {
        Ed25519PublicKeyParameters(hexStringToByteArray(ORGANIZER_PUB_KEY_HEX), 0)
    }

    fun init(context: Context) {
        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            prefs = EncryptedSharedPreferences.create(
                PREFS_FILE,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            
            // Load key if provisioned
            val storedPriv = prefs.getString(KEY_ORGANIZER_PRIV, null)
            if (storedPriv != null) {
                try {
                    val privBytes = Base64.decode(storedPriv, Base64.DEFAULT)
                    privateKeyParams = Ed25519PrivateKeyParameters(privBytes, 0)
                    Log.d(TAG, "Loaded organizer identity.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse stored organizer private key.", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Organizer EncryptedSharedPreferences", e)
        }
    }

    /**
     * Checks if the device has the organizer private key provisioned.
     */
    fun isOrganizerProvisioned(): Boolean {
        return privateKeyParams != null
    }

    /**
     * Checks if organizer passcode entry is currently locked out.
     */
    fun isLockedOut(): Boolean {
        return System.currentTimeMillis() < lockoutUntilMs
    }

    /**
     * Resets lockout state (primarily for unit tests).
     */
    fun resetLockoutForTesting() {
        failedPasscodeAttempts = 0
        lockoutUntilMs = 0L
    }

    /**
     * Validates the passcode to access the Organizer UI.
     * Enforces rate limiting: locks out after 5 consecutive failed attempts for 30 seconds.
     */
    fun validatePasscode(passcode: String): Boolean {
        if (isLockedOut()) {
            Log.w(TAG, "Passcode entry attempted while locked out.")
            return false
        }
        return if (passcode == ORGANIZER_PASSCODE) {
            failedPasscodeAttempts = 0
            true
        } else {
            failedPasscodeAttempts++
            if (failedPasscodeAttempts >= MAX_PASSCODE_ATTEMPTS) {
                lockoutUntilMs = System.currentTimeMillis() + LOCKOUT_DURATION_MS
                Log.w(TAG, "Excessive passcode attempts. Locked out for ${LOCKOUT_DURATION_MS / 1000}s.")
            }
            false
        }
    }

    /**
     * Provisions the organizer private key (hex string).
     */
    fun provisionOrganizer(privateKeyHex: String): Boolean {
        return try {
            val privBytes = hexStringToByteArray(privateKeyHex)
            if (privBytes.size != 32) {
                Log.e(TAG, "Invalid private key size. Must be 32 bytes.")
                return false
            }
            val privParams = Ed25519PrivateKeyParameters(privBytes, 0)
            
            // Validate it matches the hardcoded public key
            val derivedPubKey = privParams.generatePublicKey()
            if (!derivedPubKey.encoded.contentEquals(publicKeyParams.encoded)) {
                Log.e(TAG, "Provisioned private key does not match the official public key!")
                return false
            }

            privateKeyParams = privParams
            val b64 = Base64.encodeToString(privBytes, Base64.DEFAULT)
            prefs.edit().putString(KEY_ORGANIZER_PRIV, b64).apply()
            Log.d(TAG, "Organizer successfully provisioned.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error provisioning organizer.", e)
            false
        }
    }

    /**
     * Clears the organizer private key (logout).
     */
    fun clearOrganizer() {
        privateKeyParams = null
        prefs.edit().remove(KEY_ORGANIZER_PRIV).apply()
        Log.d(TAG, "Organizer credentials cleared.")
    }

    /**
     * Signs a BitchatPacket if provisioned.
     * Modifies the packet in-place to add the signature and returns true if successful.
     */
    fun signAnnouncement(packet: BitchatPacket): Boolean {
        val privKey = privateKeyParams
        if (privKey == null) {
            Log.e(TAG, "Cannot sign announcement. Device is not provisioned as organizer.")
            return false
        }

        return try {
            val dataToSign = packet.toBinaryDataForSigning()
            if (dataToSign == null) {
                Log.e(TAG, "Failed to canonicalize packet for signing.")
                return false
            }

            val signer = Ed25519Signer()
            signer.init(true, privKey)
            signer.update(dataToSign, 0, dataToSign.size)
            val signature = signer.generateSignature()
            
            packet.signature = signature
            Log.d(TAG, "Successfully signed announcement packet.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error signing announcement packet.", e)
            false
        }
    }

    /**
     * Verifies that the packet was signed by the official Organizer.
     */
    fun verifyAnnouncement(packet: BitchatPacket): Boolean {
        val signature = packet.signature
        if (signature == null || signature.isEmpty()) {
            Log.w(TAG, "Announcement packet has no signature.")
            return false
        }

        return try {
            val dataToVerify = packet.toBinaryDataForSigning()
            if (dataToVerify == null) {
                Log.e(TAG, "Failed to canonicalize packet for verification.")
                return false
            }

            val verifier = Ed25519Signer()
            verifier.init(false, publicKeyParams)
            verifier.update(dataToVerify, 0, dataToVerify.size)
            val isValid = verifier.verifySignature(signature)
            
            if (isValid) {
                Log.d(TAG, "Announcement signature verified successfully.")
            } else {
                Log.w(TAG, "Announcement signature verification failed!")
            }
            isValid
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying announcement packet.", e)
            false
        }
    }

    /**
     * Returns the 8-byte Organizer ID (truncated public key) to be used as senderID
     */
    fun getOrganizerSenderId(): ByteArray {
        return publicKeyParams.encoded.take(8).toByteArray()
    }

    private fun hexStringToByteArray(hexString: String): ByteArray {
        val len = hexString.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hexString[i], 16) shl 4)
                    + Character.digit(hexString[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
