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
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

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
    private static final String KEY_SALT = "salt";
    private static final String KEY_IV_STATIC = "iv_static";
    private static final String KEY_IV_SIGNING = "iv_signing";

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
            sharedPreferences = context.getSharedPreferences(PREFS_NAME + "_unencrypted", Context.MODE_PRIVATE);
        }
        this.prefs = sharedPreferences;
    }

    public byte[] getSalt() {
        String saltString = prefs.getString(KEY_SALT, null);
        if (saltString != null) {
            return Base64.decode(saltString, Base64.DEFAULT);
        } else {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            prefs.edit().putString(KEY_SALT, Base64.encodeToString(salt, Base64.DEFAULT)).apply();
            return salt;
        }
    }

    public Pair<byte[], byte[]> loadStaticKey(SecretKey secretKey) {
        try {
            String privateKeyString = prefs.getString(KEY_STATIC_PRIVATE_KEY, null);
            String publicKeyString = prefs.getString(KEY_STATIC_PUBLIC_KEY, null);
            String ivString = prefs.getString(KEY_IV_STATIC, null);
            if (privateKeyString != null && publicKeyString != null && ivString != null) {
                byte[] encryptedPrivateKey = Base64.decode(privateKeyString, Base64.DEFAULT);
                byte[] iv = Base64.decode(ivString, Base64.DEFAULT);
                byte[] privateKey = decrypt(encryptedPrivateKey, secretKey, iv);
                byte[] publicKey = Base64.decode(publicKeyString, Base64.DEFAULT);
                return new Pair<>(privateKey, publicKey);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load static key", e);
        }
        return null;
    }

    public void saveStaticKey(byte[] privateKey, byte[] publicKey, SecretKey secretKey) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] iv = cipher.getIV();
            byte[] encryptedPrivateKey = cipher.doFinal(privateKey);

            String privateKeyString = Base64.encodeToString(encryptedPrivateKey, Base64.DEFAULT);
            String publicKeyString = Base64.encodeToString(publicKey, Base64.DEFAULT);
            String ivString = Base64.encodeToString(iv, Base64.DEFAULT);

            prefs.edit()
                .putString(KEY_STATIC_PRIVATE_KEY, privateKeyString)
                .putString(KEY_STATIC_PUBLIC_KEY, publicKeyString)
                .putString(KEY_IV_STATIC, ivString)
                .apply();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save static key", e);
        }
    }

    public Pair<byte[], byte[]> loadSigningKey(SecretKey secretKey) {
         try {
            String privateKeyString = prefs.getString(KEY_SIGNING_PRIVATE_KEY, null);
            String publicKeyString = prefs.getString(KEY_SIGNING_PUBLIC_KEY, null);
            String ivString = prefs.getString(KEY_IV_SIGNING, null);
            if (privateKeyString != null && publicKeyString != null && ivString != null) {
                byte[] encryptedPrivateKey = Base64.decode(privateKeyString, Base64.DEFAULT);
                byte[] iv = Base64.decode(ivString, Base64.DEFAULT);
                byte[] privateKey = decrypt(encryptedPrivateKey, secretKey, iv);
                byte[] publicKey = Base64.decode(publicKeyString, Base64.DEFAULT);
                return new Pair<>(privateKey, publicKey);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load signing key", e);
        }
        return null;
    }

    public void saveSigningKey(byte[] privateKey, byte[] publicKey, SecretKey secretKey) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] iv = cipher.getIV();
            byte[] encryptedPrivateKey = cipher.doFinal(privateKey);

            String privateKeyString = Base64.encodeToString(encryptedPrivateKey, Base64.DEFAULT);
            String publicKeyString = Base64.encodeToString(publicKey, Base64.DEFAULT);
            String ivString = Base64.encodeToString(iv, Base64.DEFAULT);

            prefs.edit()
                .putString(KEY_SIGNING_PRIVATE_KEY, privateKeyString)
                .putString(KEY_SIGNING_PUBLIC_KEY, publicKeyString)
                .putString(KEY_IV_SIGNING, ivString)
                .apply();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save signing key", e);
        }
    }

    private byte[] decrypt(byte[] encryptedData, SecretKey secretKey, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
        return cipher.doFinal(encryptedData);
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

    public void clearAll() {
        prefs.edit().clear().apply();
    }

    public void storeSecureValue(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    public String getSecureValue(String key) {
        return prefs.getString(key, null);
    }
}
