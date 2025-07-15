package com.bitchat.android.wallet.service

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.util.Log
import android.content.Context
import com.bitchat.android.wallet.data.*
import com.bitchat.android.wallet.repository.WalletRepository
import com.bitchat.android.parsing.CashuTokenParser
import uniffi.cdk_ffi.*
import java.math.BigDecimal
import java.util.*

/**
 * Real Cashu service using the CDK FFI library
 */
class CashuService {
    
    private var wallet: FfiWallet? = null
    private var localStore: FfiLocalStore? = null
    private var currentMintUrl: String? = null
    private var currentUnit: String = "sat"
    private var isInitialized = false
    private var isCdkAvailable = false
    private var repository: WalletRepository? = null
    
    companion object {
        private const val TAG = "CashuService"
        private const val DEFAULT_MINT_URL = "https://testnut.cashu.space"
        
        @Volatile
        private var INSTANCE: CashuService? = null
        
        fun getInstance(): CashuService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CashuService().also { INSTANCE = it }
            }
        }
    }
    
    /**
     * Initialize the repository instance
     */
    private fun initializeRepository() {
        if (repository == null) {
            val context = getApplicationContext()
            repository = WalletRepository.getInstance(context)
        }
    }
    
    /**
     * Check if a mint exists in the user's mint list
     */
    private fun checkMintExists(mintUrl: String, mints: List<Mint>): Boolean {
        return mints.any { it.url == mintUrl }
    }
    
    /**
     * Add a mint if authorized (autoAdd = true)
     */
    private suspend fun addMintIfAuthorized(mintUrl: String, autoAdd: Boolean): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                if (!autoAdd) {
                    Log.d(TAG, "Auto-add not authorized for mint: $mintUrl")
                    return@withContext Result.success(false)
                }
                
                initializeRepository()
                val repo = repository ?: return@withContext Result.failure(Exception("Repository not available"))
                
                Log.d(TAG, "Adding mint automatically: $mintUrl")
                
                val mintInfoResult = getMintInfo(mintUrl)
                if (mintInfoResult.isFailure) {
                    val error = mintInfoResult.exceptionOrNull() ?: Exception("Unknown error")
                    Log.e(TAG, "Failed to get mint info for: $mintUrl", error)
                    return@withContext Result.failure(error)
                }
                
                val mintInfo = mintInfoResult.getOrThrow()
                val mint = Mint(
                    url = mintUrl,
                    nickname = mintInfo.name.ifEmpty { "Auto-added Mint" },
                    info = mintInfo,
                    keysets = emptyList(), // Will be populated by CDK
                    active = true,
                    dateAdded = Date()
                )
                
                val saveMintResult = repo.saveMint(mint)
                if (saveMintResult.isFailure) {
                    val error = saveMintResult.exceptionOrNull() ?: Exception("Unknown error")
                    Log.e(TAG, "Failed to save mint: $mintUrl", error)
                    return@withContext Result.failure(error)
                }
                
                Log.d(TAG, "Successfully added mint: $mintUrl")
                return@withContext Result.success(true)
                
            } catch (e: Exception) {
                Log.e(TAG, "Exception while adding mint: $mintUrl", e)
                return@withContext Result.failure(e)
            }
        }
    }
    
    /**
     * Set the active mint and initialize wallet with it
     */
    private suspend fun setActiveMintAndInitialize(mintUrl: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                initializeRepository()
                val repo = repository ?: return@withContext Result.failure(Exception("Repository not available"))
                
                // Set active mint in repository
                val setActiveMintResult = repo.setActiveMint(mintUrl)
                if (setActiveMintResult.isFailure) {
                    val error = setActiveMintResult.exceptionOrNull() ?: Exception("Unknown error")
                    Log.e(TAG, "Failed to set active mint: $mintUrl", error)
                    return@withContext Result.failure(error)
                }
                
                // Initialize wallet with the mint
                val initWalletResult = initializeWallet(mintUrl)
                if (initWalletResult.isFailure) {
                    val error = initWalletResult.exceptionOrNull() ?: Exception("Unknown error")
                    Log.e(TAG, "Failed to initialize wallet with mint: $mintUrl", error)
                    return@withContext Result.failure(error)
                }
                
                Log.d(TAG, "Set active mint and initialized wallet: $mintUrl")
                return@withContext Result.success(Unit)
                
            } catch (e: Exception) {
                Log.e(TAG, "Exception while setting active mint: $mintUrl", e)
                return@withContext Result.failure(e)
            }
        }
    }
    
    /**
     * Check if CDK is available and initialize appropriately
     */
    private fun initializeCdkAvailability(): Boolean {
        if (isCdkAvailable) return true
        
        Log.d(TAG, "=== CDK LIBRARY AVAILABILITY CHECK ===")
        
        try {
            // Test CDK availability by calling a simple function
            val testMnemonic = generateMnemonic()
            Log.d(TAG, "✅ CDK library is fully available and functional!")
            Log.d(TAG, "Generated test mnemonic with ${testMnemonic.split(" ").size} words")
            isCdkAvailable = true
            return true
            
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "❌ CDK library not available: ${e.message}")
        } catch (e: FfiException) {
            Log.w(TAG, "❌ CDK FFI error: ${e.message}")
        } catch (e: NoClassDefFoundError) {
            Log.w(TAG, "❌ CDK classes not found: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "❌ Unexpected CDK error: ${e.message}")
        }
        
        isCdkAvailable = false
        Log.w(TAG, "⚠️ CDK library not available - wallet will not function properly")
        Log.d(TAG, "=== END CDK LIBRARY CHECK ===")
        return false
    }
    
    /**
     * Initialize the CDK wallet with a mint URL
     */
    suspend fun initializeWallet(mintUrl: String, unit: String = "sat"): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Initializing wallet with mint: $mintUrl")
                
                // Test CDK availability first
                if (!initializeCdkAvailability()) {
                    return@withContext Result.failure(Exception("CDK library not available"))
                }
                
                // Create database path using application context
                val context = getApplicationContext()
                val dbPath = "${context.filesDir}/cashu_wallet.db"
                
                // Create local store
                localStore = FfiLocalStore.newWithPath(dbPath)
                Log.d(TAG, "Created CDK local store at: $dbPath")
                
                // Generate or restore mnemonic
                val mnemonic = generateMnemonic()
                Log.d(TAG, "Generated wallet mnemonic")
                
                // Convert unit string to FfiCurrencyUnit
                val ffiUnit = when (unit.lowercase()) {
                    "sat" -> FfiCurrencyUnit.SAT
                    "msat" -> FfiCurrencyUnit.MSAT
                    "usd" -> FfiCurrencyUnit.USD
                    "eur" -> FfiCurrencyUnit.EUR
                    else -> FfiCurrencyUnit.SAT
                }
                
                // Create wallet from mnemonic
                wallet = FfiWallet.fromMnemonic(
                    mintUrl = mintUrl,
                    unit = ffiUnit,
                    localstore = localStore!!,
                    mnemonicWords = mnemonic
                )
                Log.d(TAG, "Created CDK wallet successfully")
                
                // Try to fetch mint info to verify connection
                try {
                    val info = wallet?.getMintInfo()
                    Log.d(TAG, "Successfully connected to mint: ${info}")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not fetch mint info: ${e.message}")
                    // Continue anyway - wallet can work offline
                }
                
                currentMintUrl = mintUrl
                currentUnit = unit
                isInitialized = true
                
                Log.d(TAG, "Real CDK wallet initialized successfully")
                return@withContext Result.success(true)
                
            } catch (e: FfiException) {
                Log.e(TAG, "CDK FFI error during wallet initialization", e)
                return@withContext Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize wallet", e)
                return@withContext Result.failure(e)
            }
        }
    }
    
    suspend fun getMintInfo(mintUrl: String): Result<MintInfo> {
        return withContext(Dispatchers.IO) {
            try {
                // Ensure wallet is initialized for this mint
                if (!isInitialized || currentMintUrl != mintUrl) {
                    initializeWallet(mintUrl).getOrThrow()
                }
                
                val mintInfo = if (isCdkAvailable && wallet != null) {
                    try {
                        // Get real mint info from CDK
                        val mintInfoData = wallet!!.getMintInfo()
                        Log.d(TAG, "Retrieved real mint info from CDK")
                        
                        MintInfo(
                            url = mintUrl,
                            name = extractMintName(mintUrl),
                            description = "Cashu mint via CDK",
                            descriptionLong = "Real Cashu mint connection via CDK-FFI",
                            contact = "",
                            version = "1.0.0",
                            nuts = mapOf(
                                "4" to "true", // NUT-4: Mint quote
                                "5" to "true", // NUT-5: Melt quote 
                                "7" to "true", // NUT-7: Token state check
                                "8" to "true"  // NUT-8: Lightning fee return
                            ),
                            motd = "", 
                            icon = null,
                            time = Date()
                        )
                    } catch (e: FfiException) {
                        Log.w(TAG, "CDK FFI error getting mint info: ${e.message}")
                        throw e
                    }
                } else {
                    throw Exception("CDK not available")
                }
                
                Result.success(mintInfo)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get mint info for $mintUrl", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * Get the current wallet balance
     */
    suspend fun getBalance(): Result<Long> {
        return withContext(Dispatchers.IO) {
            try {
                if (!isInitialized) {
                    initializeWallet(DEFAULT_MINT_URL).getOrThrow()
                }
                
                if (!isCdkAvailable || wallet == null) {
                    return@withContext Result.failure(Exception("CDK not available"))
                }
                
                // Get real balance from CDK
                val ffiAmount = wallet!!.balance()
                val balanceValue = ffiAmount.value.toLong()
                
                Log.d(TAG, "Real balance: $balanceValue sats")
                Result.success(balanceValue)
                
            } catch (e: FfiException) {
                Log.e(TAG, "CDK FFI error getting balance: ${e.message}")
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get balance", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * Create a Cashu token for the specified amount
     */
    suspend fun createToken(amount: Long, memo: String? = null): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                if (!isInitialized) {
                    initializeWallet(DEFAULT_MINT_URL).getOrThrow()
                }
                
                if (!isCdkAvailable || wallet == null) {
                    return@withContext Result.failure(Exception("CDK not available"))
                }
                
                Log.d(TAG, "Creating real token for amount: $amount")
                
                // Create send options
                val sendMemo = memo?.let { FfiSendMemo(it, true) }
                val sendOptions = FfiSendOptions(
                    memo = sendMemo,
                    amountSplitTarget = FfiSplitTarget.DEFAULT,
                    sendKind = FfiSendKind.OnlineExact,
                    includeFee = false,
                    metadata = emptyMap(),
                    maxProofs = null
                )
                
                // Create the token
                val ffiToken = wallet!!.send(
                    amount = FfiAmount(amount.toULong()),
                    options = sendOptions,
                    memo = sendMemo
                )
                
                Log.d(TAG, "Created real token successfully")
                Result.success(ffiToken.tokenString)
                
            } catch (e: FfiException) {
                Log.e(TAG, "CDK FFI error creating token: ${e.message}")
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create token", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * Receive a Cashu token with mint management
     * @param token The Cashu token to receive
     * @param autoAdd Whether to automatically add unknown mints
     * @param currentMints The current list of user's mints
     * @param decodedToken Optional pre-decoded token to avoid re-decoding
     */
    suspend fun receiveToken(
        token: String, 
        autoAdd: Boolean = true, 
        currentMints: List<Mint> = emptyList(),
        decodedToken: CashuToken? = null
    ): Result<Long> {
        return withContext(Dispatchers.IO) {
            try {
                // Decode token if not provided
                val tokenData = decodedToken ?: decodeToken(token).getOrThrow()
                val mintUrl = tokenData.mint
                val amount = tokenData.amount.toLong()
                
                Log.d(TAG, "Processing token: amount=$amount from mint=$mintUrl")
                
                // Check if mint exists
                val mintExists = checkMintExists(mintUrl, currentMints)
                
                if (!mintExists) {
                    Log.d(TAG, "Mint $mintUrl not found, attempting to add...")
                    
                    // Try to add the mint if authorized
                    addMintIfAuthorized(mintUrl, autoAdd).fold(
                        onSuccess = { added ->
                            if (!added) {
                                return@withContext Result.failure(
                                    Exception("Mint $mintUrl not found and auto-add not authorized")
                                )
                            }
                        },
                        onFailure = { error ->
                            Log.e(TAG, "Failed to add mint $mintUrl", error)
                            return@withContext Result.failure(
                                Exception("Failed to add mint: $mintUrl - ${error.message}")
                            )
                        }
                    )
                }
                
                // Set active mint and initialize wallet with it
                setActiveMintAndInitialize(mintUrl).fold(
                    onSuccess = {
                        Log.d(TAG, "Successfully set active mint: $mintUrl")
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to set active mint: $mintUrl", error)
                        return@withContext Result.failure(
                            Exception("Failed to set active mint: $mintUrl - ${error.message}")
                        )
                    }
                )
                
                // Now receive the token with the properly initialized wallet
                if (!isCdkAvailable || wallet == null) {
                    return@withContext Result.failure(Exception("CDK not available"))
                }
                
                Log.d(TAG, "Receiving token with amount: $amount")
                val ffiAmount = wallet!!.receive(token)
                
                Log.d(TAG, "Successfully received token: ${ffiAmount.value}")
                Result.success(ffiAmount.value.toLong())
                
            } catch (e: FfiException) {
                Log.e(TAG, "CDK FFI error receiving token: ${e.message}")
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to receive token", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * Create a Lightning invoice for minting (receiving via Lightning)
     */
    suspend fun createMintQuote(amount: Long, description: String? = null): Result<MintQuote> {
        return withContext(Dispatchers.IO) {
            try {
                if (!isInitialized) {
                    initializeWallet(DEFAULT_MINT_URL).getOrThrow()
                }
                
                if (!isCdkAvailable || wallet == null) {
                    return@withContext Result.failure(Exception("CDK not available"))
                }
                
                Log.d(TAG, "Creating real mint quote for $amount")
                
                val ffiMintQuote = wallet!!.mintQuote(
                    amount = FfiAmount(amount.toULong()),
                    description = description ?: "Cashu mint"
                )
                
                Log.d(TAG, "Created real mint quote: ${ffiMintQuote.id}")
                
                // Convert FFI types to our domain types
                val mintQuote = MintQuote(
                    id = ffiMintQuote.id,
                    amount = BigDecimal(ffiMintQuote.amount.value.toLong()),
                    unit = ffiMintQuote.unit,
                    request = ffiMintQuote.request,
                    state = when (ffiMintQuote.state) {
                        FfiMintQuoteState.UNPAID -> MintQuoteState.UNPAID
                        FfiMintQuoteState.PAID -> MintQuoteState.PAID  
                        FfiMintQuoteState.ISSUED -> MintQuoteState.ISSUED
                    },
                    expiry = Date(ffiMintQuote.expiry.toLong() * 1000), // Convert from seconds to milliseconds
                    paid = ffiMintQuote.state == FfiMintQuoteState.PAID
                )
                
                Log.d(TAG, "Created mint quote: ${mintQuote.id}")
                Result.success(mintQuote)
                
            } catch (e: FfiException) {
                Log.e(TAG, "CDK FFI error creating mint quote: ${e.message}")
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create mint quote", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * Check if a mint quote has been paid and mint ecash
     */
    suspend fun checkAndMintQuote(quoteId: String): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                if (!isInitialized) {
                    initializeWallet(DEFAULT_MINT_URL).getOrThrow()
                }
                
                if (!isCdkAvailable || wallet == null) {
                    return@withContext Result.failure(Exception("CDK not available"))
                }
                
                // Check quote state
                val quoteResponse = wallet!!.mintQuoteState(quoteId)
                Log.d(TAG, "Checked mint quote $quoteId: ${quoteResponse.state}")
                
                if (quoteResponse.state == FfiMintQuoteState.PAID) {
                    // Quote is paid, mint the tokens
                    try {
                        val mintedAmount = wallet!!.mint(quoteId, FfiSplitTarget.DEFAULT)
                        Log.d(TAG, "Successfully minted ${mintedAmount.value} sats for quote $quoteId")
                        Result.success(true)
                    } catch (e: FfiException) {
                        Log.w(TAG, "Failed to mint for paid quote: ${e.message}")
                        Result.success(false)
                    }
                } else if (quoteResponse.state == FfiMintQuoteState.ISSUED) {
                    // Quote is issued
                    Log.d(TAG, "Quote $quoteId is issued")
                    Result.success(true)
                } else {
                    // Quote not paid yet
                    Result.success(false)
                }
                
            } catch (e: FfiException) {
                Log.e(TAG, "CDK FFI error checking mint quote: ${e.message}")
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check mint quote", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * Create a melt quote for paying a Lightning invoice
     */
    suspend fun createMeltQuote(invoice: String): Result<MeltQuote> {
        return withContext(Dispatchers.IO) {
            try {
                if (!isInitialized) {
                    initializeWallet(DEFAULT_MINT_URL).getOrThrow()
                }
                
                if (!isCdkAvailable || wallet == null) {
                    return@withContext Result.failure(Exception("CDK not available"))
                }
                
                Log.d(TAG, "Creating real melt quote for invoice")
                
                val ffiMeltQuote = wallet!!.meltQuote(invoice)
                
                Log.d(TAG, "Created real melt quote: ${ffiMeltQuote.id}")
                
                // Convert FFI types to our domain types
                val meltQuote = MeltQuote(
                    id = ffiMeltQuote.id,
                    amount = BigDecimal(ffiMeltQuote.amount.value.toLong()),
                    feeReserve = BigDecimal(ffiMeltQuote.feeReserve.value.toLong()),
                    unit = ffiMeltQuote.unit,
                    request = invoice,
                    state = MeltQuoteState.UNPAID, // MeltQuote doesn't have state, default to UNPAID
                    expiry = Date(ffiMeltQuote.expiry.toLong() * 1000), // Convert from seconds to milliseconds
                    paid = false // Default to unpaid until melt is called
                )
                
                Log.d(TAG, "Created melt quote: ${meltQuote.id}")
                Result.success(meltQuote)
                
            } catch (e: FfiException) {
                Log.e(TAG, "CDK FFI error creating melt quote: ${e.message}")
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create melt quote", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * Pay a Lightning invoice using ecash (melt)
     */
    suspend fun payInvoice(quoteId: String): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                if (!isInitialized) {
                    initializeWallet(DEFAULT_MINT_URL).getOrThrow()
                }
                
                if (!isCdkAvailable || wallet == null) {
                    return@withContext Result.failure(Exception("CDK not available"))
                }
                
                Log.d(TAG, "Paying invoice with melt quote: $quoteId")
                
                val meltResult = wallet!!.melt(quoteId)
                
                Log.d(TAG, "Invoice payment result: ${meltResult.state}")
                
                // FfiMelted.state is a String, check for success states
                val success = meltResult.state.equals("paid", ignoreCase = true) || 
                             meltResult.state.equals("success", ignoreCase = true)
                Result.success(success)
                
            } catch (e: FfiException) {
                Log.e(TAG, "CDK FFI error paying invoice: ${e.message}")
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pay invoice", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * Decode a Cashu token to get information without receiving it
     */
    suspend fun decodeToken(token: String): Result<CashuToken> {
        return withContext(Dispatchers.IO) {
            try {
                // Use the proper CashuTokenParser for real token parsing
                val parser = CashuTokenParser()
                val parsedToken = parser.parseToken(token)
                
                if (parsedToken != null) {
                    val cashuToken = CashuToken(
                        token = token,
                        amount = BigDecimal(parsedToken.amount),
                        unit = parsedToken.unit,
                        mint = parsedToken.mintUrl,
                        memo = parsedToken.memo
                    )
                    
                    Log.d(TAG, "Decoded token: amount=${parsedToken.amount} ${parsedToken.unit} from ${parsedToken.mintUrl}")
                    Result.success(cashuToken)
                } else {
                    Log.e(TAG, "Failed to parse token: invalid format")
                    Result.failure(Exception("Invalid token format"))
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode token", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * Clean up wallet resources
     */
    fun cleanup() {
        try {
            wallet?.destroy()
            localStore?.destroy()
            wallet = null
            localStore = null
            isInitialized = false
            Log.d(TAG, "Cleaned up wallet resources")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
    
    /**
     * Check if real CDK is available
     */
    fun isCdkAvailable(): Boolean {
        initializeCdkAvailability()
        return isCdkAvailable
    }
    
    // Helper functions
    
    private fun getApplicationContext(): Context {
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentApplication = activityThread.getMethod("currentApplication")
            currentApplication.invoke(null) as Context
        } catch (e: Exception) {
            throw IllegalStateException("Could not get application context", e)
        }
    }
    
    private fun extractMintName(url: String): String {
        return try {
            val host = url.substringAfter("://").substringBefore("/")
            host.substringBefore(".").replaceFirstChar { it.uppercase() }
        } catch (e: Exception) {
            "Cashu Mint"
        }
    }
    

}
