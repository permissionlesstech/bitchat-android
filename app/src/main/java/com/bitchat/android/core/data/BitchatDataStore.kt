package com.bitchat.android.core.data

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

val Context.bitchatPrefsDataStore by preferencesDataStore(name = "bitchat_prefs")