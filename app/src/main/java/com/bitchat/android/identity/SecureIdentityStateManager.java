package com.bitchat.android.identity;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.bitchat.android.util.BinaryEncodingUtils;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Gère le stockage persistant et sécurisé de l'identité.
 * Utilise les EncryptedSharedPreferences d'Android pour protéger les clés.
 */
public class SecureIdentityStateManager {

    private static final String TAG = "SecureIdentityStateManager";
    private static final String PREFS_NAME = "bitchat_identity";
    private static final String KEY_STATIC_PRIVATE_KEY = "static_private_key";
    private static final String KEY_STATIC_PUBLIC_KEY = "static_public_key";
    private static final String KEY_SIGNING_PRIVATE_KEY = "signing_private_key";
    private static final String KEY_SIGNING_PUBLIC_KEY = "signing_public_key";

    private final SharedPreferences prefs;

    public SecureIdentityStateManager(Context context) {
        SharedPreferences sharedPreferences = null;
        try {
            MasterKey masterKey = new MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();

            sharedPreferences = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Failed to create encrypted shared preferences", e);
            // En cas d'échec, utiliser les SharedPreferences non chiffrées comme solution de repli
            // (non idéal pour la production, mais assure la robustesse).
            sharedPreferences = context.getSharedPreferences(PREFS_NAME + "_unencrypted", Context.MODE_PRIVATE);
        }
        this.prefs = sharedPreferences;
    }

    public Pair<byte[], byte[]> loadStaticKey() {
        try {
            String privateKeyString = prefs.getString(KEY_STATIC_PRIVATE_KEY, null);
            String publicKeyString = prefs.getString(KEY_STATIC_PUBLIC_KEY, null);
            if (privateKeyString != null && publicKeyString != null) {
                byte[] privateKey = Base64.decode(privateKeyString, Base64.DEFAULT);
                byte[] publicKey = Base64.decode(publicKeyString, Base64.DEFAULT);
                if (privateKey.length == 32 && publicKey.length == 32) {
                    return new Pair<>(privateKey, publicKey);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load static key", e);
        }
        return null;
    }

    public void saveStaticKey(byte[] privateKey, byte[] publicKey) {
        if (privateKey.length != 32 || publicKey.length != 32) {
            throw new IllegalArgumentException("Invalid key sizes");
        }
        String privateKeyString = Base64.encodeToString(privateKey, Base64.DEFAULT);
        String publicKeyString = Base64.encodeToString(publicKey, Base64.DEFAULT);
        prefs.edit()
            .putString(KEY_STATIC_PRIVATE_KEY, privateKeyString)
            .putString(KEY_STATIC_PUBLIC_KEY, publicKeyString)
            .apply();
    }

    public Pair<byte[], byte[]> loadSigningKey() {
         try {
            String privateKeyString = prefs.getString(KEY_SIGNING_PRIVATE_KEY, null);
            String publicKeyString = prefs.getString(KEY_SIGNING_PUBLIC_KEY, null);
            if (privateKeyString != null && publicKeyString != null) {
                byte[] privateKey = Base64.decode(privateKeyString, Base64.DEFAULT);
                byte[] publicKey = Base64.decode(publicKeyString, Base64.DEFAULT);
                if (privateKey.length == 32 && publicKey.length == 32) {
                    return new Pair<>(privateKey, publicKey);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load signing key", e);
        }
        return null;
    }

    public void saveSigningKey(byte[] privateKey, byte[] publicKey) {
        if (privateKey.length != 32 || publicKey.length != 32) {
            throw new IllegalArgumentException("Invalid signing key sizes");
        }
        String privateKeyString = Base64.encodeToString(privateKey, Base64.DEFAULT);
        String publicKeyString = Base64.encodeToString(publicKey, Base64.DEFAULT);
        prefs.edit()
            .putString(KEY_SIGNING_PRIVATE_KEY, privateKeyString)
            .putString(KEY_SIGNING_PUBLIC_KEY, publicKeyString)
            .apply();
    }

    public String generateFingerprint(byte[] publicKeyData) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(publicKeyData);
            return BinaryEncodingUtils.hexEncodedString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public void clearIdentityData() {
        prefs.edit().clear().apply();
    }

    public void storeSecureValue(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    public String getSecureValue(String key) {
        return prefs.getString(key, null);
    }
}
