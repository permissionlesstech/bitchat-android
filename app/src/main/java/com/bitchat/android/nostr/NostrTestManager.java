package com.bitchat.android.nostr;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class NostrTestManager {
    private static final String TAG = "NostrTestManager";

    private final Context context;
    private final ExecutorService testExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private NostrClient nostrClient;

    public NostrTestManager(Context context) {
        this.context = context;
    }

    public void runTests() {
        Log.i(TAG, "🧪 Starting Nostr functionality tests...");
        testExecutor.submit(() -> {
            try {
                testClientInitialization();
                testIdentityManagement();
                testRelayConnections();
                testCryptography();
                testBech32();
                testMessageSubscription();
                Log.i(TAG, "✅ All Nostr tests completed successfully!");
            } catch (Exception e) {
                Log.e(TAG, "❌ Nostr tests failed: " + e.getMessage(), e);
            }
        });
    }

    private void testClientInitialization() throws InterruptedException {
        Log.d(TAG, "Testing client initialization...");
        nostrClient = NostrClient.getInstance(context);
        nostrClient.initialize();
        Thread.sleep(2000);
        Boolean isInitialized = nostrClient.isInitialized.getValue();
        if (isInitialized == null || !isInitialized) {
            throw new IllegalStateException("Client failed to initialize");
        }
        Log.d(TAG, "✅ Client initialization successful");
    }

    private void testIdentityManagement() {
        Log.d(TAG, "Testing identity management...");
        NostrIdentity identity = nostrClient.getCurrentIdentity();
        if (identity == null) throw new IllegalStateException("No current identity");

        Log.d(TAG, "Current identity npub: " + identity.getShortNpub());
        if (!identity.getNpub().startsWith("npub1")) throw new IllegalStateException("Invalid npub format");
        if (identity.getPublicKeyHex().length() != 64) throw new IllegalStateException("Invalid public key length");
        if (identity.getPrivateKeyHex().length() != 64) throw new IllegalStateException("Invalid private key length");

        NostrIdentity geohashIdentity = NostrIdentityBridge.deriveIdentity("u4pruydq", context);
        if (!geohashIdentity.getNpub().startsWith("npub1")) throw new IllegalStateException("Invalid geohash identity npub");
        if (geohashIdentity.getPublicKeyHex().equals(identity.getPublicKeyHex())) throw new IllegalStateException("Geohash identity should be different");

        Log.d(TAG, "Geohash identity npub: " + geohashIdentity.getShortNpub());
        Log.d(TAG, "✅ Identity management test successful");
    }

    private void testRelayConnections() throws InterruptedException {
        Log.d(TAG, "Testing relay connections...");
        Thread.sleep(3000);
        List<NostrRelayManager.Relay> relayInfo = nostrClient.getRelayInfo().getValue();
        if (relayInfo == null || relayInfo.isEmpty()) throw new IllegalStateException("No relays configured");

        Log.d(TAG, "Configured relays: " + relayInfo.size());
        for (NostrRelayManager.Relay relay : relayInfo) {
            Log.d(TAG, "Relay: " + relay.getUrl() + " - Connected: " + relay.isConnected());
        }
        Log.d(TAG, "✅ Relay configuration test successful");
    }

    private void testCryptography() throws Exception {
        Log.d(TAG, "Testing cryptography functions...");
        NostrCrypto.KeyPair keyPair = NostrCrypto.generateKeyPair();
        if (keyPair.privateKey.length() != 64) throw new IllegalStateException("Invalid private key length");
        if (keyPair.publicKey.length() != 64) throw new IllegalStateException("Invalid public key length");
        if (!NostrCrypto.isValidPrivateKey(keyPair.privateKey)) throw new IllegalStateException("Generated private key is invalid");
        if (!NostrCrypto.isValidPublicKey(keyPair.publicKey)) throw new IllegalStateException("Generated public key is invalid");

        String derivedPublic = NostrCrypto.derivePublicKey(keyPair.privateKey);
        if (!derivedPublic.equals(keyPair.publicKey)) throw new IllegalStateException("Key derivation mismatch");

        NostrCrypto.KeyPair recipientKeys = NostrCrypto.generateKeyPair();
        String plaintext = "Hello, Nostr world! This is a test message.";
        String encrypted = NostrCrypto.encryptNIP44(plaintext, recipientKeys.publicKey, keyPair.privateKey);
        if (encrypted.isEmpty()) throw new IllegalStateException("Encryption failed");

        String decrypted = NostrCrypto.decryptNIP44(encrypted, keyPair.publicKey, recipientKeys.privateKey);
        if (!decrypted.equals(plaintext)) throw new IllegalStateException("Decryption failed");

        Log.d(TAG, "✅ Cryptography test successful");
    }

    private void testBech32() throws Exception {
        Log.d(TAG, "Testing Bech32 encoding...");
        byte[] testData = "hello world test data for bech32".getBytes();
        String encoded = Bech32.encode("test", testData);
        if (!encoded.startsWith("test1")) throw new IllegalStateException("Invalid bech32 encoding");

        Bech32.DecodedResult decoded = Bech32.decode(encoded);
        if (!"test".equals(decoded.hrp)) throw new IllegalStateException("HRP mismatch");
        if (!java.util.Arrays.equals(decoded.data, testData)) throw new IllegalStateException("Data mismatch after decode");

        NostrCrypto.KeyPair keyPair = NostrCrypto.generateKeyPair();
        String npub = Bech32.encode("npub", keyPair.publicKey.getBytes());
        if (!npub.startsWith("npub1")) throw new IllegalStateException("Invalid npub encoding");

        Bech32.DecodedResult npubDecoded = Bech32.decode(npub);
        if (!"npub".equals(npubDecoded.hrp)) throw new IllegalStateException("npub HRP mismatch");
        if (!new String(npubDecoded.data).equals(keyPair.publicKey)) throw new IllegalStateException("npub data mismatch");

        Log.d(TAG, "✅ Bech32 test successful");
    }

    private void testMessageSubscription() throws InterruptedException {
        Log.d(TAG, "Testing message subscription...");
        nostrClient.subscribeToPrivateMessages((content, senderNpub, timestamp) -> {
            Log.d(TAG, "📥 Received test private message from " + senderNpub + ": " + content);
        });
        nostrClient.subscribeToGeohash("u4pru", (content, senderPubkey, nickname, timestamp) -> {
             Log.d(TAG, "📥 Received test geohash message from " + senderPubkey.substring(0, 16) + "...: " + content);
        });
        Thread.sleep(2000);
        Log.d(TAG, "✅ Message subscription test successful (no messages expected in test)");
    }

    public void testLoopbackMessage() {
        testExecutor.submit(() -> {
            try {
                NostrIdentity identity = nostrClient.getCurrentIdentity();
                if (identity == null) throw new IllegalStateException("No identity available for loopback test");
                Log.i(TAG, "🔄 Testing loopback private message...");
                nostrClient.sendPrivateMessage(
                    "Test loopback message at " + System.currentTimeMillis(),
                    identity.getNpub(),
                    () -> Log.i(TAG, "✅ Loopback message sent successfully"),
                    error -> Log.e(TAG, "❌ Loopback message failed: " + error)
                );
            } catch (Exception e) {
                Log.e(TAG, "❌ Loopback test failed: " + e.getMessage(), e);
            }
        });
    }

    public void testGeohashMessage() {
        testExecutor.submit(() -> {
            try {
                Log.i(TAG, "🌍 Testing geohash message...");
                nostrClient.sendGeohashMessage(
                    "Test geohash message from Android at " + System.currentTimeMillis(),
                    "u4pru",
                    "android-test",
                    () -> Log.i(TAG, "✅ Geohash message sent successfully"),
                    error -> Log.e(TAG, "❌ Geohash message failed: " + error)
                );
            } catch (Exception e) {
                Log.e(TAG, "❌ Geohash test failed: " + e.getMessage(), e);
            }
        });
    }

    public String getDebugInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Nostr Client Debug Info ===\n");
        NostrIdentity identity = nostrClient.getCurrentIdentity();
        if (identity != null) {
            sb.append("Identity: ").append(identity.getShortNpub()).append("\n");
            sb.append("Public Key: ").append(identity.getPublicKeyHex().substring(0, 16)).append("...\n");
            sb.append("Created: ").append(new java.util.Date(identity.getCreatedAt())).append("\n");
        } else {
            sb.append("No identity loaded\n");
        }
        Boolean isInitialized = nostrClient.isInitialized.getValue();
        sb.append("Initialized: ").append(isInitialized != null && isInitialized).append("\n");
        Boolean isConnected = nostrClient.getRelayConnectionStatus().getValue();
        sb.append("Relay Connected: ").append(isConnected != null && isConnected).append("\n");
        List<NostrRelayManager.Relay> relays = nostrClient.getRelayInfo().getValue();
        if (relays != null) {
            sb.append("Relays (").append(relays.size()).append("):\n");
            for (NostrRelayManager.Relay relay : relays) {
                sb.append("  ").append(relay.getUrl()).append(": ").append(relay.isConnected() ? "✅" : "❌")
                  .append(" (sent: ").append(relay.getMessagesSent())
                  .append(", received: ").append(relay.getMessagesReceived()).append(")\n");
            }
        } else {
            sb.append("Relays (0):\n");
        }
        return sb.toString();
    }

    public void shutdown() {
        testExecutor.shutdownNow();
        nostrClient.shutdown();
    }
}
