package com.bitchat.android.wallet.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.bitchat.android.wallet.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*
import android.util.Log

/**
 * Repository for managing wallet data persistence
 */
class WalletRepository private constructor(context: Context) {
    
    private val gson = Gson()
    private val sharedPrefs: SharedPreferences
    
    companion object {
        private const val TAG = "WalletRepository"
        private const val PREFS_NAME = "cashu_wallet_prefs"
        private const val KEY_MINTS = "mints"
        private const val KEY_ACTIVE_MINT = "active_mint"
        private const val KEY_TRANSACTIONS = "transactions"
        private const val KEY_MINT_QUOTES = "mint_quotes"
        private const val KEY_MELT_QUOTES = "melt_quotes"
        
        @Volatile
        private var INSTANCE: WalletRepository? = null
        
        fun getInstance(context: Context): WalletRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WalletRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    init {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        sharedPrefs = EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    /**
     * Save a mint to the local storage
     */
    suspend fun saveMint(mint: Mint): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val mints = getMints().getOrElse { emptyList() }.toMutableList()
            
            // Remove existing mint with same URL
            mints.removeAll { it.url == mint.url }
            
            // Add the new/updated mint
            mints.add(mint)
            
            val json = gson.toJson(mints)
            sharedPrefs.edit().putString(KEY_MINTS, json).apply()
            
            Log.d(TAG, "Saved mint: ${mint.url}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save mint", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get all mints from local storage
     */
    suspend fun getMints(): Result<List<Mint>> = withContext(Dispatchers.IO) {
        try {
            val json = sharedPrefs.getString(KEY_MINTS, null)
            if (json != null) {
                val type = object : TypeToken<List<Mint>>() {}.type
                val mints: List<Mint> = gson.fromJson(json, type)
                Result.success(mints)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get mints", e)
            Result.failure(e)
        }
    }
    
    /**
     * Set the active mint URL
     */
    suspend fun setActiveMint(mintUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            sharedPrefs.edit().putString(KEY_ACTIVE_MINT, mintUrl).apply()
            Log.d(TAG, "Set active mint: $mintUrl")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set active mint", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get the active mint URL
     */
    suspend fun getActiveMint(): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val mintUrl = sharedPrefs.getString(KEY_ACTIVE_MINT, null)
            Result.success(mintUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get active mint", e)
            Result.failure(e)
        }
    }
    
    /**
     * Save a transaction to the local storage
     */
    suspend fun saveTransaction(transaction: WalletTransaction): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val transactions = getTransactions().getOrElse { emptyList() }.toMutableList()
            
            // Remove existing transaction with same ID
            transactions.removeAll { it.id == transaction.id }
            
            // Add the new/updated transaction
            transactions.add(0, transaction) // Add to front
            
            // Keep only the last 100 transactions
            if (transactions.size > 100) {
                transactions.subList(100, transactions.size).clear()
            }
            
            val json = gson.toJson(transactions)
            sharedPrefs.edit().putString(KEY_TRANSACTIONS, json).apply()
            
            Log.d(TAG, "Saved transaction: ${transaction.id}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save transaction", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get all transactions from local storage
     */
    suspend fun getTransactions(): Result<List<WalletTransaction>> = withContext(Dispatchers.IO) {
        try {
            val json = sharedPrefs.getString(KEY_TRANSACTIONS, null)
            if (json != null) {
                val type = object : TypeToken<List<WalletTransaction>>() {}.type
                val transactions: List<WalletTransaction> = gson.fromJson(json, type)
                Result.success(transactions)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get transactions", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get all transactions (alias for getTransactions)
     */
    suspend fun getAllTransactions(): Result<List<WalletTransaction>> = getTransactions()
    
    /**
     * Get the last N transactions
     */
    suspend fun getLastTransactions(count: Int = 5): Result<List<WalletTransaction>> = withContext(Dispatchers.IO) {
        try {
            val allTransactions = getTransactions().getOrElse { emptyList() }
            val lastTransactions = allTransactions.take(count)
            Result.success(lastTransactions)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get last transactions", e)
            Result.failure(e)
        }
    }
    
    /**
     * Save a mint quote
     */
    suspend fun saveMintQuote(quote: MintQuote): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val quotes = getMintQuotes().getOrElse { emptyList() }.toMutableList()
            
            // Remove existing quote with same ID
            quotes.removeAll { it.id == quote.id }
            
            // Add the new/updated quote
            quotes.add(0, quote)
            
            // Keep only the last 50 quotes
            if (quotes.size > 50) {
                quotes.subList(50, quotes.size).clear()
            }
            
            val json = gson.toJson(quotes)
            sharedPrefs.edit().putString(KEY_MINT_QUOTES, json).apply()
            
            Log.d(TAG, "Saved mint quote: ${quote.id}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save mint quote", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get all mint quotes
     */
    suspend fun getMintQuotes(): Result<List<MintQuote>> = withContext(Dispatchers.IO) {
        try {
            val json = sharedPrefs.getString(KEY_MINT_QUOTES, null)
            if (json != null) {
                val type = object : TypeToken<List<MintQuote>>() {}.type
                val quotes: List<MintQuote> = gson.fromJson(json, type)
                Result.success(quotes)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get mint quotes", e)
            Result.failure(e)
        }
    }
    
    /**
     * Save a melt quote
     */
    suspend fun saveMeltQuote(quote: MeltQuote): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val quotes = getMeltQuotes().getOrElse { emptyList() }.toMutableList()
            
            // Remove existing quote with same ID
            quotes.removeAll { it.id == quote.id }
            
            // Add the new/updated quote
            quotes.add(0, quote)
            
            // Keep only the last 50 quotes
            if (quotes.size > 50) {
                quotes.subList(50, quotes.size).clear()
            }
            
            val json = gson.toJson(quotes)
            sharedPrefs.edit().putString(KEY_MELT_QUOTES, json).apply()
            
            Log.d(TAG, "Saved melt quote: ${quote.id}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save melt quote", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get all melt quotes
     */
    suspend fun getMeltQuotes(): Result<List<MeltQuote>> = withContext(Dispatchers.IO) {
        try {
            val json = sharedPrefs.getString(KEY_MELT_QUOTES, null)
            if (json != null) {
                val type = object : TypeToken<List<MeltQuote>>() {}.type
                val quotes: List<MeltQuote> = gson.fromJson(json, type)
                Result.success(quotes)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get melt quotes", e)
            Result.failure(e)
        }
    }
    
    /**
     * Remove expired quotes
     */
    suspend fun cleanupExpiredQuotes(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val now = Date()
            
            // Clean mint quotes
            val mintQuotes = getMintQuotes().getOrElse { emptyList() }
                .filter { it.expiry.after(now) }
            val mintJson = gson.toJson(mintQuotes)
            sharedPrefs.edit().putString(KEY_MINT_QUOTES, mintJson).apply()
            
            // Clean melt quotes
            val meltQuotes = getMeltQuotes().getOrElse { emptyList() }
                .filter { it.expiry.after(now) }
            val meltJson = gson.toJson(meltQuotes)
            sharedPrefs.edit().putString(KEY_MELT_QUOTES, meltJson).apply()
            
            Log.d(TAG, "Cleaned up expired quotes")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup expired quotes", e)
            Result.failure(e)
        }
    }
    
    /**
     * Clear all wallet data
     */
    suspend fun clearAllData(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            sharedPrefs.edit().clear().apply()
            Log.d(TAG, "Cleared all wallet data")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear wallet data", e)
            Result.failure(e)
        }
    }
}
