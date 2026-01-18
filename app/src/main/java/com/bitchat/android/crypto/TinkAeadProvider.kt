package com.bitchat.android.crypto

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.IOException
import java.security.GeneralSecurityException

object TinkAeadProvider {
    private const val KEYSET_NAME = "bitchat_tink_keyset"
    private const val PREF_FILE_NAME = "bitchat_tink_keyset_prefs"
    private const val MASTER_KEY_ALIAS = "bitchat_tink_master_key"
    private const val MASTER_KEY_URI = "android-keystore://$MASTER_KEY_ALIAS"

    @Volatile
    private var cachedAead: Aead? = null

    @Throws(GeneralSecurityException::class, IOException::class)
    fun getAead(context: Context): Aead {
        cachedAead?.let { return it }
        synchronized(this) {
            cachedAead?.let { return it }
            AeadConfig.register()

            val keysetHandle = AndroidKeysetManager.Builder()
                .withSharedPref(context, KEYSET_NAME, PREF_FILE_NAME)
                .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle

            val aead = keysetHandle.getPrimitive(Aead::class.java)
            cachedAead = aead
            return aead
        }
    }
}
