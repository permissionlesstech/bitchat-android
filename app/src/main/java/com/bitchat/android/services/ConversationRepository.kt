package com.bitchat.android.services

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide, serialized persistence for private conversations.
 *
 * Android's SQLite database is deliberately kept behind this small repository instead of leaking
 * cursors or database threading into the mesh and UI layers. Every mutation is queued on one
 * writer so a delete/merge followed by a newly arriving message is applied in the same order that
 * [AppStateStore] publishes it.
 */
class ConversationRepository internal constructor(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Executors
        .newSingleThreadExecutor { runnable ->
            Thread(runnable, "conversation-store").apply { isDaemon = true }
        }
        .asCoroutineDispatcher(),
    databaseName: String = ConversationDatabase.DEFAULT_DATABASE_NAME
) {
    companion object {
        private const val TAG = "ConversationRepository"

        @Volatile
        private var instance: ConversationRepository? = null

        fun getInstance(context: Context): ConversationRepository =
            instance ?: synchronized(this) {
                instance ?: ConversationRepository(context.applicationContext).also {
                    instance = it
                }
            }

        fun tryGetInstance(): ConversationRepository? = instance
    }

    private val database = ConversationDatabase(
        context = context.applicationContext,
        databaseName = databaseName
    )
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val initialized = AtomicBoolean(false)

    internal fun initialize(onLoaded: (PersistedConversationSnapshot) -> Unit) {
        if (!initialized.compareAndSet(false, true)) return
        enqueueSnapshotLoad(pruneFirst = true, onLoaded = onLoaded)
    }

    /**
     * Re-reads persisted history even when this process already initialized the repository.
     *
     * This is required when the app's controlled shutdown cleared [AppStateStore], but Android
     * reused the still-running process when the user immediately reopened the UI.
     */
    internal fun reload(onLoaded: (PersistedConversationSnapshot) -> Unit) {
        enqueueSnapshotLoad(pruneFirst = false, onLoaded = onLoaded)
    }

    private fun enqueueSnapshotLoad(
        pruneFirst: Boolean,
        onLoaded: (PersistedConversationSnapshot) -> Unit
    ) {
        scope.launch {
            try {
                if (pruneFirst) database.pruneToRetentionLimits()
                onLoaded(database.loadSnapshot())
            } catch (error: Exception) {
                Log.e(TAG, "Unable to restore private conversations", error)
            }
        }
    }

    /**
     * Suspends until all persistence work queued before this call has finished.
     */
    suspend fun awaitPendingWrites() {
        withContext(dispatcher) { Unit }
    }

    fun upsertMessage(
        conversationID: String,
        aliases: Set<String>,
        displayName: String?,
        message: BitchatMessage,
        isRead: Boolean
    ) {
        scope.launch {
            try {
                database.upsertMessage(
                    conversationID = conversationID,
                    aliases = aliases,
                    displayName = displayName,
                    message = message,
                    isRead = isRead
                )
            } catch (error: Exception) {
                Log.e(TAG, "Unable to persist private message: ${error.message}")
            }
        }
    }

    fun updateDeliveryStatus(messageID: String, status: DeliveryStatus) {
        scope.launch {
            try {
                database.updateDeliveryStatus(messageID, status)
            } catch (error: Exception) {
                Log.e(TAG, "Unable to persist delivery status: ${error.message}")
            }
        }
    }

    fun markRead(messageID: String) {
        scope.launch {
            try {
                database.markRead(messageID)
            } catch (error: Exception) {
                Log.e(TAG, "Unable to persist local read state: ${error.message}")
            }
        }
    }

    fun mergeAliases(targetConversationID: String, aliases: Set<String>) {
        scope.launch {
            try {
                database.mergeAliases(targetConversationID, aliases)
            } catch (error: Exception) {
                Log.e(TAG, "Unable to merge conversation aliases: ${error.message}")
            }
        }
    }

    fun deleteConversation(conversationID: String, aliases: Set<String>) {
        scope.launch {
            try {
                database.deleteConversation(conversationID, aliases)
            } catch (error: Exception) {
                Log.e(TAG, "Unable to delete private conversation: ${error.message}")
            }
        }
    }

    fun deleteMessage(messageID: String) {
        scope.launch {
            try {
                database.deleteMessage(messageID)
            } catch (error: Exception) {
                Log.e(TAG, "Unable to delete private message: ${error.message}")
            }
        }
    }

    /**
     * Drains earlier writes and completes the database wipe before returning.
     *
     * Panic mode uses this stronger variant so identity regeneration and transport restart cannot
     * race an outstanding message insert or an unfinished conversation deletion.
     */
    suspend fun clearAllAndWait(): Boolean = withContext(dispatcher) {
        try {
            database.clearAll()
            true
        } catch (error: Exception) {
            Log.e(TAG, "Unable to synchronously clear private conversations", error)
            false
        }
    }
}

internal data class PersistedConversationSnapshot(
    val chats: Map<String, List<BitchatMessage>>,
    val readMessageIDs: Set<String>,
    val arrivalOrder: List<String>,
    val deletedMessageIDs: Set<String>
)

/**
 * Versioned SQLite schema for bounded private-message history.
 *
 * Conversation rows are intentionally tiny and remain until explicit deletion. Message rows are
 * bounded by per-conversation, global-row, and payload-byte limits. Media bytes stay in the app's
 * existing media storage; SQLite only stores the message metadata/path already present in content.
 */
internal class ConversationDatabase(
    context: Context,
    databaseName: String = DEFAULT_DATABASE_NAME,
    private val maxMessagesPerConversation: Int = MAX_MESSAGES_PER_CONVERSATION,
    private val maxMessagesTotal: Int = MAX_MESSAGES_TOTAL,
    private val maxPayloadBytes: Long = MAX_PAYLOAD_BYTES
) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {

    companion object {
        const val MAX_MESSAGES_PER_CONVERSATION = 1_000
        const val MAX_MESSAGES_TOTAL = 20_000
        const val MAX_PAYLOAD_BYTES = 32L * 1024L * 1024L

        internal const val DEFAULT_DATABASE_NAME = "private_conversations.db"
        private const val DATABASE_VERSION = 1
        private const val PRUNE_INTERVAL = 64
        private const val PRUNE_BATCH_SIZE = 256
    }

    private var writesSinceGlobalPrune = 0

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.execSQL("PRAGMA auto_vacuum = INCREMENTAL")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE conversations (
                conversation_id TEXT COLLATE NOCASE PRIMARY KEY NOT NULL,
                display_name TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE conversation_aliases (
                alias TEXT COLLATE NOCASE PRIMARY KEY NOT NULL,
                conversation_id TEXT COLLATE NOCASE NOT NULL,
                FOREIGN KEY(conversation_id) REFERENCES conversations(conversation_id)
                    ON DELETE CASCADE ON UPDATE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE private_messages (
                arrival_sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                message_id TEXT UNIQUE NOT NULL,
                conversation_id TEXT COLLATE NOCASE NOT NULL,
                sender TEXT NOT NULL,
                content TEXT NOT NULL,
                message_type INTEGER NOT NULL,
                sent_at INTEGER NOT NULL,
                is_relay INTEGER NOT NULL,
                original_sender TEXT,
                is_private INTEGER NOT NULL,
                recipient_nickname TEXT,
                sender_peer_id TEXT,
                mentions_json TEXT,
                channel_name TEXT,
                encrypted_content BLOB,
                is_encrypted INTEGER NOT NULL,
                delivery_type INTEGER NOT NULL,
                delivery_text TEXT,
                delivery_at INTEGER,
                delivery_reached INTEGER,
                delivery_total INTEGER,
                sender_nostr_pubkey TEXT,
                is_read INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(conversation_id) REFERENCES conversations(conversation_id)
                    ON DELETE CASCADE ON UPDATE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_private_messages_conversation_arrival " +
                "ON private_messages(conversation_id, arrival_sequence)"
        )
        db.execSQL(
            "CREATE INDEX idx_private_messages_read_arrival " +
                "ON private_messages(is_read, arrival_sequence)"
        )
        db.execSQL(
            "CREATE INDEX idx_conversation_aliases_conversation " +
                "ON conversation_aliases(conversation_id)"
        )
        db.execSQL(
            """
            CREATE TABLE deleted_private_messages (
                message_id TEXT PRIMARY KEY NOT NULL,
                deleted_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_deleted_private_messages_time " +
                "ON deleted_private_messages(deleted_at)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first persisted-conversation schema. Future versions must migrate
        // without dropping private history.
        check(oldVersion == newVersion) {
            "Missing conversation database migration from $oldVersion to $newVersion"
        }
    }

    fun loadSnapshot(): PersistedConversationSnapshot {
        val chats = linkedMapOf<String, MutableList<BitchatMessage>>()
        val readIDs = linkedSetOf<String>()
        val arrivalOrder = mutableListOf<String>()
        val deletedMessageIDs = linkedSetOf<String>()
        readableDatabase.query(
            "private_messages",
            MESSAGE_COLUMNS,
            null,
            null,
            null,
            null,
            "arrival_sequence ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val conversationID = cursor.string("conversation_id")
                val message = cursor.toMessage()
                chats.getOrPut(conversationID) { mutableListOf() }.add(message)
                arrivalOrder.add(message.id)
                if (cursor.boolean("is_read")) readIDs.add(message.id)
            }
        }
        readableDatabase.query(
            "deleted_private_messages",
            arrayOf("message_id"),
            null,
            null,
            null,
            null,
            "deleted_at ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) deletedMessageIDs.add(cursor.getString(0))
        }
        return PersistedConversationSnapshot(
            chats = chats.mapValues { it.value.toList() },
            readMessageIDs = readIDs,
            arrivalOrder = arrivalOrder,
            deletedMessageIDs = deletedMessageIDs
        )
    }

    fun upsertMessage(
        conversationID: String,
        aliases: Set<String>,
        displayName: String?,
        message: BitchatMessage,
        isRead: Boolean
    ) {
        val normalizedID = conversationID.trim()
        if (normalizedID.isBlank()) return
        val now = System.currentTimeMillis()
        writableDatabase.inTransaction {
            if (isDeletedMessageLocked(this, message.id)) return@inTransaction
            mergeAliasesLocked(
                db = this,
                targetConversationID = normalizedID,
                aliases = aliases + normalizedID,
                displayName = displayName,
                now = now
            )
            val values = message.toContentValues(normalizedID, isRead)
            val inserted = insertWithOnConflict(
                "private_messages",
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE
            )
            if (inserted == -1L) {
                val existingConversation = rawQuery(
                    "SELECT conversation_id, is_read FROM private_messages WHERE message_id = ?",
                    arrayOf(message.id)
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.string("conversation_id") to cursor.boolean("is_read")
                    } else {
                        null
                    }
                }
                if (existingConversation != null) {
                    val canonicalExisting = resolveStoredConversationLocked(
                        this,
                        existingConversation.first
                    )
                    if (!canonicalExisting.equals(normalizedID, ignoreCase = true)) {
                        mergeAliasesLocked(
                            db = this,
                            targetConversationID = normalizedID,
                            aliases = aliases + canonicalExisting,
                            displayName = displayName,
                            now = now
                        )
                    }
                    if (isRead && !existingConversation.second) {
                        update(
                            "private_messages",
                            ContentValues().apply { put("is_read", 1) },
                            "message_id = ?",
                            arrayOf(message.id)
                        )
                    }
                }
            }
            updateConversationMetadataLocked(this, normalizedID, displayName, now)
            pruneConversationLocked(this, normalizedID)
        }

        writesSinceGlobalPrune += 1
        if (writesSinceGlobalPrune >= PRUNE_INTERVAL) {
            writesSinceGlobalPrune = 0
            pruneToRetentionLimits()
        }
    }

    fun updateDeliveryStatus(messageID: String, status: DeliveryStatus) {
        val db = writableDatabase
        var found = false
        val existing = db.rawQuery(
            """
            SELECT delivery_type, delivery_text, delivery_at, delivery_reached, delivery_total
            FROM private_messages WHERE message_id = ?
            """.trimIndent(),
            arrayOf(messageID)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                found = true
                cursor.toDeliveryStatus()
            } else {
                null
            }
        }

        if (!found) return
        if (statusPriority(status) < statusPriority(existing)) return
        db.update(
            "private_messages",
            deliveryValues(status),
            "message_id = ?",
            arrayOf(messageID)
        )
    }

    fun markRead(messageID: String) {
        writableDatabase.update(
            "private_messages",
            ContentValues().apply { put("is_read", 1) },
            "message_id = ?",
            arrayOf(messageID)
        )
    }

    fun mergeAliases(targetConversationID: String, aliases: Set<String>) {
        if (targetConversationID.isBlank()) return
        writableDatabase.inTransaction {
            mergeAliasesLocked(
                db = this,
                targetConversationID = targetConversationID,
                aliases = aliases + targetConversationID,
                displayName = null,
                now = System.currentTimeMillis()
            )
        }
    }

    fun deleteConversation(conversationID: String, aliases: Set<String>) {
        writableDatabase.inTransaction {
            val ids = linkedSetOf<String>()
            (aliases + conversationID).forEach { value ->
                ids.add(resolveStoredConversationLocked(this, value))
                ids.add(value)
            }
            ids.filter { it.isNotBlank() }.forEach { id ->
                execSQL(
                    """
                    INSERT OR REPLACE INTO deleted_private_messages(message_id, deleted_at)
                    SELECT message_id, ? FROM private_messages
                    WHERE conversation_id = ? COLLATE NOCASE
                    """.trimIndent(),
                    arrayOf<Any>(System.currentTimeMillis(), id)
                )
            }
            ids.filter { it.isNotBlank() }.forEach { id ->
                delete(
                    "conversations",
                    "conversation_id = ? COLLATE NOCASE",
                    arrayOf(id)
                )
            }
            pruneDeletedMessageIDsLocked(this)
        }
    }

    fun deleteMessage(messageID: String) {
        writableDatabase.inTransaction {
            insertWithOnConflict(
                "deleted_private_messages",
                null,
                ContentValues().apply {
                    put("message_id", messageID)
                    put("deleted_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
            delete("private_messages", "message_id = ?", arrayOf(messageID))
            delete(
                "conversations",
                """
                NOT EXISTS (
                    SELECT 1 FROM private_messages
                    WHERE private_messages.conversation_id = conversations.conversation_id
                )
                """.trimIndent(),
                null
            )
            pruneDeletedMessageIDsLocked(this)
        }
    }

    fun clearAll() {
        writableDatabase.inTransaction {
            delete("conversation_aliases", null, null)
            delete("private_messages", null, null)
            delete("conversations", null, null)
            delete("deleted_private_messages", null, null)
            execSQL("DELETE FROM sqlite_sequence WHERE name = 'private_messages'")
        }
        writableDatabase.rawQuery("PRAGMA incremental_vacuum", null).use { }
    }

    fun pruneToRetentionLimits() {
        val db = writableDatabase
        db.inTransaction {
            rawQuery(
                "SELECT conversation_id FROM conversations",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    pruneConversationLocked(this, cursor.getString(0))
                }
            }

            var stats = storageStatsLocked(this)
            while (
                stats.first > maxMessagesTotal ||
                stats.second > maxPayloadBytes
            ) {
                val candidateLimit = if (stats.first > maxMessagesTotal) {
                    (stats.first - maxMessagesTotal)
                        .coerceAtMost(PRUNE_BATCH_SIZE.toLong())
                        .toInt()
                } else {
                    // Payload sizes vary, so recalculate after each removal instead of
                    // discarding an entire batch unnecessarily.
                    1
                }
                val candidates = pruneCandidatesLocked(
                    db = this,
                    readOnly = true,
                    preserveLatest = true,
                    limit = candidateLimit
                ).ifEmpty {
                    pruneCandidatesLocked(
                        db = this,
                        readOnly = false,
                        preserveLatest = true,
                        limit = candidateLimit
                    )
                }.ifEmpty {
                    // A store with one message in every conversation still needs a hard bound.
                    // Prefer retiring read conversations before unread ones.
                    pruneCandidatesLocked(
                        db = this,
                        readOnly = true,
                        preserveLatest = false,
                        limit = candidateLimit
                    )
                }.ifEmpty {
                    pruneCandidatesLocked(
                        db = this,
                        readOnly = false,
                        preserveLatest = false,
                        limit = candidateLimit
                    )
                }
                if (candidates.isEmpty()) break
                tombstoneMessagesLocked(this, candidates)
                candidates.forEach { messageID ->
                    delete("private_messages", "message_id = ?", arrayOf(messageID))
                }
                deleteEmptyConversationsLocked(this)
                pruneDeletedMessageIDsLocked(this)
                stats = storageStatsLocked(this)
            }
        }
        db.rawQuery("PRAGMA incremental_vacuum(128)", null).use { }
    }

    private fun mergeAliasesLocked(
        db: SQLiteDatabase,
        targetConversationID: String,
        aliases: Set<String>,
        displayName: String?,
        now: Long
    ) {
        ensureConversationLocked(db, targetConversationID, displayName, now)
        val normalizedAliases = aliases
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
        val sourceIDs = normalizedAliases
            .mapTo(linkedSetOf()) { resolveStoredConversationLocked(db, it) }
            .plus(
                db.queryValues(
                    table = "conversation_aliases",
                    resultColumn = "conversation_id",
                    selectionValues = normalizedAliases
                )
            )

        sourceIDs
            .filterNot { it.equals(targetConversationID, ignoreCase = true) }
            .forEach { sourceID ->
                db.update(
                    "private_messages",
                    ContentValues().apply { put("conversation_id", targetConversationID) },
                    "conversation_id = ? COLLATE NOCASE",
                    arrayOf(sourceID)
                )
                db.update(
                    "conversation_aliases",
                    ContentValues().apply { put("conversation_id", targetConversationID) },
                    "conversation_id = ? COLLATE NOCASE",
                    arrayOf(sourceID)
                )
                db.delete(
                    "conversations",
                    "conversation_id = ? COLLATE NOCASE",
                    arrayOf(sourceID)
                )
            }

        (normalizedAliases + sourceIDs + targetConversationID).forEach { alias ->
            db.insertWithOnConflict(
                "conversation_aliases",
                null,
                ContentValues().apply {
                    put("alias", alias)
                    put("conversation_id", targetConversationID)
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
        updateConversationMetadataLocked(db, targetConversationID, displayName, now)
    }

    private fun ensureConversationLocked(
        db: SQLiteDatabase,
        conversationID: String,
        displayName: String?,
        now: Long
    ) {
        db.insertWithOnConflict(
            "conversations",
            null,
            ContentValues().apply {
                put("conversation_id", conversationID)
                put("display_name", displayName)
                put("created_at", now)
                put("updated_at", now)
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    private fun updateConversationMetadataLocked(
        db: SQLiteDatabase,
        conversationID: String,
        displayName: String?,
        now: Long
    ) {
        db.update(
            "conversations",
            ContentValues().apply {
                if (!displayName.isNullOrBlank()) put("display_name", displayName)
                put("updated_at", now)
            },
            "conversation_id = ? COLLATE NOCASE",
            arrayOf(conversationID)
        )
    }

    private fun resolveStoredConversationLocked(db: SQLiteDatabase, value: String): String {
        if (value.isBlank()) return value
        return db.rawQuery(
            "SELECT conversation_id FROM conversation_aliases WHERE alias = ? COLLATE NOCASE",
            arrayOf(value)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else value
        }
    }

    private fun isDeletedMessageLocked(db: SQLiteDatabase, messageID: String): Boolean =
        db.longForQuery(
            "SELECT COUNT(*) FROM deleted_private_messages WHERE message_id = ?",
            arrayOf(messageID)
        ) > 0L

    private fun pruneDeletedMessageIDsLocked(db: SQLiteDatabase) {
        val excess = (
            db.longForQuery(
                "SELECT COUNT(*) FROM deleted_private_messages",
                emptyArray()
            ) - maxMessagesTotal
            ).coerceAtLeast(0L)
        if (excess == 0L) return
        db.execSQL(
            """
            DELETE FROM deleted_private_messages
            WHERE message_id IN (
                SELECT message_id FROM deleted_private_messages
                ORDER BY deleted_at ASC
                LIMIT ?
            )
            """.trimIndent(),
            arrayOf(excess)
        )
    }

    private fun pruneConversationLocked(db: SQLiteDatabase, conversationID: String) {
        val count = db.longForQuery(
            "SELECT COUNT(*) FROM private_messages WHERE conversation_id = ? COLLATE NOCASE",
            arrayOf(conversationID)
        )
        val excess = (count - maxMessagesPerConversation).coerceAtLeast(0L)
        if (excess == 0L) return
        db.rawQuery(
            """
            SELECT message_id
            FROM private_messages
            WHERE conversation_id = ? COLLATE NOCASE
                AND arrival_sequence < (
                    SELECT MAX(arrival_sequence)
                    FROM private_messages
                    WHERE conversation_id = ? COLLATE NOCASE
                )
            ORDER BY is_read DESC, arrival_sequence ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(conversationID, conversationID, excess.toString())
        ).use { cursor ->
            val ids = mutableListOf<String>()
            while (cursor.moveToNext()) ids.add(cursor.getString(0))
            tombstoneMessagesLocked(db, ids)
            ids.forEach { db.delete("private_messages", "message_id = ?", arrayOf(it)) }
            pruneDeletedMessageIDsLocked(db)
        }
    }

    private fun storageStatsLocked(db: SQLiteDatabase): Pair<Long, Long> =
        db.rawQuery(
            """
            SELECT COUNT(*),
                COALESCE(SUM(
                    length(content) +
                    COALESCE(length(encrypted_content), 0) +
                    COALESCE(length(mentions_json), 0)
                ), 0)
            FROM private_messages
            """.trimIndent(),
            null
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0) to cursor.getLong(1)
        }

    private fun pruneCandidatesLocked(
        db: SQLiteDatabase,
        readOnly: Boolean,
        preserveLatest: Boolean,
        limit: Int
    ): List<String> {
        val readClause = if (readOnly) "AND candidate.is_read = 1" else ""
        val newerMessageClause = if (preserveLatest) {
            """
            AND EXISTS (
                SELECT 1
                FROM private_messages AS newer
                WHERE newer.conversation_id = candidate.conversation_id
                    AND newer.arrival_sequence > candidate.arrival_sequence
            )
            """.trimIndent()
        } else {
            ""
        }
        return db.rawQuery(
            """
            SELECT candidate.message_id
            FROM private_messages AS candidate
            WHERE 1 = 1
            $newerMessageClause
            $readClause
            ORDER BY candidate.arrival_sequence ASC
            LIMIT $limit
            """.trimIndent(),
            null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    private fun deleteEmptyConversationsLocked(db: SQLiteDatabase) {
        db.delete(
            "conversations",
            """
            NOT EXISTS (
                SELECT 1 FROM private_messages
                WHERE private_messages.conversation_id = conversations.conversation_id
            )
            """.trimIndent(),
            null
        )
    }

    private fun tombstoneMessagesLocked(
        db: SQLiteDatabase,
        messageIDs: Collection<String>
    ) {
        val deletedAt = System.currentTimeMillis()
        messageIDs.forEach { messageID ->
            db.insertWithOnConflict(
                "deleted_private_messages",
                null,
                ContentValues().apply {
                    put("message_id", messageID)
                    put("deleted_at", deletedAt)
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
    }

    private fun SQLiteDatabase.queryValues(
        table: String,
        resultColumn: String,
        selectionValues: Set<String>
    ): Set<String> {
        if (selectionValues.isEmpty()) return emptySet()
        val placeholders = selectionValues.joinToString(",") { "?" }
        return rawQuery(
            "SELECT $resultColumn FROM $table WHERE alias IN ($placeholders)",
            selectionValues.toTypedArray()
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    private fun SQLiteDatabase.longForQuery(sql: String, args: Array<String>): Long =
        rawQuery(sql, args).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private inline fun <T> SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> T): T {
        beginTransaction()
        return try {
            val result = block()
            setTransactionSuccessful()
            result
        } finally {
            endTransaction()
        }
    }

    private fun BitchatMessage.toContentValues(
        conversationID: String,
        isRead: Boolean
    ): ContentValues = ContentValues().apply {
        put("message_id", id)
        put("conversation_id", conversationID)
        put("sender", sender)
        put("content", content)
        put("message_type", type.ordinal)
        put("sent_at", timestamp.time)
        put("is_relay", isRelay.asInt())
        put("original_sender", originalSender)
        put("is_private", isPrivate.asInt())
        put("recipient_nickname", recipientNickname)
        put("sender_peer_id", senderPeerID)
        put("mentions_json", mentions?.let { JSONArray(it).toString() })
        put("channel_name", channel)
        put("encrypted_content", encryptedContent)
        put("is_encrypted", isEncrypted.asInt())
        putAll(deliveryValues(deliveryStatus))
        put("sender_nostr_pubkey", senderNostrPubkey)
        put("is_read", isRead.asInt())
    }

    private fun deliveryValues(status: DeliveryStatus?): ContentValues = ContentValues().apply {
        when (status) {
            null -> put("delivery_type", 0)
            DeliveryStatus.Sending -> put("delivery_type", 1)
            DeliveryStatus.Sent -> put("delivery_type", 2)
            is DeliveryStatus.Delivered -> {
                put("delivery_type", 3)
                put("delivery_text", status.to)
                put("delivery_at", status.at.time)
            }
            is DeliveryStatus.Read -> {
                put("delivery_type", 4)
                put("delivery_text", status.by)
                put("delivery_at", status.at.time)
            }
            is DeliveryStatus.Failed -> {
                put("delivery_type", 5)
                put("delivery_text", status.reason)
            }
            is DeliveryStatus.PartiallyDelivered -> {
                put("delivery_type", 6)
                put("delivery_reached", status.reached)
                put("delivery_total", status.total)
            }
        }
    }

    private fun statusPriority(status: DeliveryStatus?): Int = when (status) {
        null -> 0
        is DeliveryStatus.Failed -> 0
        DeliveryStatus.Sending -> 1
        DeliveryStatus.Sent -> 2
        is DeliveryStatus.PartiallyDelivered -> 3
        is DeliveryStatus.Delivered -> 4
        is DeliveryStatus.Read -> 5
    }

    private fun Cursor.toMessage(): BitchatMessage = BitchatMessage(
        id = string("message_id"),
        sender = string("sender"),
        content = string("content"),
        type = BitchatMessageType.entries.getOrElse(int("message_type")) {
            BitchatMessageType.Message
        },
        timestamp = Date(long("sent_at")),
        isRelay = boolean("is_relay"),
        originalSender = nullableString("original_sender"),
        isPrivate = boolean("is_private"),
        recipientNickname = nullableString("recipient_nickname"),
        senderPeerID = nullableString("sender_peer_id"),
        mentions = nullableString("mentions_json")?.let(::jsonStringList),
        channel = nullableString("channel_name"),
        encryptedContent = blobOrNull("encrypted_content"),
        isEncrypted = boolean("is_encrypted"),
        deliveryStatus = toDeliveryStatus(),
        senderNostrPubkey = nullableString("sender_nostr_pubkey")
    )

    private fun Cursor.toDeliveryStatus(): DeliveryStatus? = when (int("delivery_type")) {
        1 -> DeliveryStatus.Sending
        2 -> DeliveryStatus.Sent
        3 -> DeliveryStatus.Delivered(
            to = nullableString("delivery_text").orEmpty(),
            at = Date(nullableLong("delivery_at") ?: 0L)
        )
        4 -> DeliveryStatus.Read(
            by = nullableString("delivery_text").orEmpty(),
            at = Date(nullableLong("delivery_at") ?: 0L)
        )
        5 -> DeliveryStatus.Failed(nullableString("delivery_text").orEmpty())
        6 -> DeliveryStatus.PartiallyDelivered(
            reached = nullableInt("delivery_reached") ?: 0,
            total = nullableInt("delivery_total") ?: 0
        )
        else -> null
    }

    private fun jsonStringList(json: String): List<String> {
        val array = JSONArray(json)
        return buildList(array.length()) {
            for (index in 0 until array.length()) add(array.getString(index))
        }
    }

    private fun Cursor.index(column: String): Int = getColumnIndexOrThrow(column)
    private fun Cursor.string(column: String): String = getString(index(column))
    private fun Cursor.nullableString(column: String): String? =
        index(column).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.int(column: String): Int = getInt(index(column))
    private fun Cursor.nullableInt(column: String): Int? =
        index(column).let { if (isNull(it)) null else getInt(it) }
    private fun Cursor.long(column: String): Long = getLong(index(column))
    private fun Cursor.nullableLong(column: String): Long? =
        index(column).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.boolean(column: String): Boolean = int(column) != 0
    private fun Cursor.blobOrNull(column: String): ByteArray? =
        index(column).let { if (isNull(it)) null else getBlob(it) }
    private fun Boolean.asInt(): Int = if (this) 1 else 0

    private val MESSAGE_COLUMNS = arrayOf(
        "arrival_sequence",
        "message_id",
        "conversation_id",
        "sender",
        "content",
        "message_type",
        "sent_at",
        "is_relay",
        "original_sender",
        "is_private",
        "recipient_nickname",
        "sender_peer_id",
        "mentions_json",
        "channel_name",
        "encrypted_content",
        "is_encrypted",
        "delivery_type",
        "delivery_text",
        "delivery_at",
        "delivery_reached",
        "delivery_total",
        "sender_nostr_pubkey",
        "is_read"
    )
}
