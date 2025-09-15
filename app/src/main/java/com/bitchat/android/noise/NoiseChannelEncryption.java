package com.bitchat.android.noise;

import android.util.Log;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.security.spec.KeySpec;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Gère le chiffrement pour les canaux protégés par mot de passe.
 * Utilise PBKDF2 pour la dérivation de clé et AES-256-GCM pour le chiffrement.
 */
public class NoiseChannelEncryption {

    private static final String TAG = "NoiseChannelEncryption";
    private static final int PBKDF2_ITERATIONS = 100000;
    private static final int KEY_LENGTH = 256;

    private final Map<String, SecretKeySpec> channelKeys = new ConcurrentHashMap<>();
    private final Map<String, String> channelPasswords = new ConcurrentHashMap<>();

    public void setChannelPassword(String password, String channel) {
        try {
            if (password == null || password.isEmpty()) {
                Log.w(TAG, "Empty password provided for channel " + channel);
                return;
            }
            SecretKeySpec key = deriveChannelKey(password, channel);
            channelKeys.put(channel, key);
            channelPasswords.put(channel, password);
            Log.d(TAG, "Password set for channel " + channel);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set password for channel " + channel, e);
        }
    }

    public void removeChannelPassword(String channel) {
        channelKeys.remove(channel);
        channelPasswords.remove(channel);
        Log.d(TAG, "Removed password for channel " + channel);
    }

    public boolean hasChannelKey(String channel) {
        return channelKeys.containsKey(channel);
    }

    public byte[] encryptChannelMessage(String message, String channel) throws Exception {
        SecretKeySpec key = channelKeys.get(channel);
        if (key == null) {
            throw new IllegalStateException("No key available for channel " + channel);
        }

        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);

        byte[] iv = cipher.getIV();
        byte[] encryptedData = cipher.doFinal(messageBytes);

        byte[] result = new byte[iv.length + encryptedData.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(encryptedData, 0, result, iv.length, encryptedData.length);
        return result;
    }

    public String decryptChannelMessage(byte[] encryptedData, String channel) throws Exception {
        SecretKeySpec key = channelKeys.get(channel);
        if (key == null) {
            throw new IllegalStateException("No key available for channel " + channel);
        }

        if (encryptedData.length < 12) { // IV pour GCM est typiquement de 12 bytes
            throw new IllegalArgumentException("Encrypted data too short");
        }

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(128, encryptedData, 0, 12);
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);

        byte[] decryptedBytes = cipher.doFinal(encryptedData, 12, encryptedData.length - 12);
        return new String(decryptedBytes, StandardCharsets.UTF_8);
    }

    private SecretKeySpec deriveChannelKey(String password, String channel) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] salt = channel.getBytes(StandardCharsets.UTF_8);
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH);
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    public void clear() {
        channelKeys.clear();
        channelPasswords.clear();
        Log.d(TAG, "Cleared all channel encryption data");
    }
}
