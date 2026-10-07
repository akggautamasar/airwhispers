package com.airwhispers.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.airwhispers.core.DefaultDispatchers
import com.airwhispers.core.DispatcherProvider
import com.airwhispers.data.model.Contact
import com.airwhispers.data.model.Conversation
import com.airwhispers.data.model.DeliveryState
import com.airwhispers.data.model.Message
import com.airwhispers.data.model.MessagePriority
import com.airwhispers.data.model.MessageState
import com.airwhispers.domain.assist.SpokenLedger
import kotlinx.coroutines.withContext

/**
 * Single-writer local cache.
 *
 * Deliberately a hand-written SQLite store instead of an ORM: the schema is tiny,
 * it keeps the APK small, and — most importantly — the message table doubles as
 * the durable *spoken* ledger that makes Call Assist idempotent across process
 * restarts and push redelivery.
 */
class LocalStore(
    context: Context,
    private val dispatchers: DispatcherProvider = DefaultDispatchers,
) : SpokenLedger {

    private val helper = Db(context.applicationContext)

    // ---------------------------------------------------------------- conversations

    suspend fun conversations(): List<Conversation> = query { db ->
        db.query(T_CONVERSATIONS, null, null, null, null, null, "$C_LAST_AT DESC").use { c ->
            buildList { while (c.moveToNext()) add(c.toConversation()) }
        }
    }

    suspend fun upsertConversations(items: List<Conversation>) = transaction { db ->
        items.forEach { db.insertWithOnConflict(T_CONVERSATIONS, null, it.toValues(), SQLiteDatabase.CONFLICT_REPLACE) }
    }

    suspend fun upsertConversation(item: Conversation) = upsertConversations(listOf(item))

    suspend fun conversation(id: String): Conversation? = query { db ->
        db.query(T_CONVERSATIONS, null, "$C_ID = ?", arrayOf(id), null, null, null).use { c ->
            if (c.moveToFirst()) c.toConversation() else null
        }
    }

    suspend fun setConversationTrust(peerId: String, trusted: Boolean) = transaction { db ->
        db.update(T_CONVERSATIONS, ContentValues().apply { put(C_PEER_TRUSTED, if (trusted) 1 else 0) }, "$C_PEER_ID = ?", arrayOf(peerId))
    }

    suspend fun resetUnread(conversationId: String) = transaction { db ->
        db.update(T_CONVERSATIONS, ContentValues().apply { put(C_UNREAD, 0) }, "$C_ID = ?", arrayOf(conversationId))
    }

    suspend fun bumpUnread(conversationId: String) = transaction { db ->
        db.execSQL("UPDATE $T_CONVERSATIONS SET $C_UNREAD = $C_UNREAD + 1 WHERE $C_ID = ?", arrayOf<Any>(conversationId))
    }

    // --------------------------------------------------------------------- messages

    suspend fun messages(conversationId: String, limit: Int = 200): List<Message> = query { db ->
        db.query(
            T_MESSAGES, null, "$C_CONVERSATION_ID = ?", arrayOf(conversationId),
            null, null, "$C_CREATED_AT DESC", limit.toString(),
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.toMessage()) }.reversed()
        }
    }

    /**
     * Insert or update a message. Never overwrites the local `spokenAt` marker or
     * a pending outbox row: server echoes must not resurrect a message the user
     * already heard, nor clobber state the sender UI still needs.
     */
    suspend fun upsertMessage(message: Message) = transaction { db ->
        val existing = db.query(T_MESSAGES, null, "$C_CLIENT_ID = ? OR $C_ID = ?", arrayOf(message.clientMessageId, message.id), null, null, null).use { c ->
            if (c.moveToFirst()) c.toMessage() else null
        }
        if (existing == null) {
            db.insertWithOnConflict(T_MESSAGES, null, message.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
        } else {
            val merged = existing.copy(
                id = message.id.ifBlank { existing.id },
                text = message.text,
                createdAt = if (existing.state == MessageState.PENDING) existing.createdAt else message.createdAt,
                state = if (existing.state == MessageState.PENDING && message.state == MessageState.SENT) MessageState.SENT else message.state,
                deliveryState = maxOf(existing.deliveryState.ordinal, message.deliveryState.ordinal).let { DeliveryState.entries[it] },
                priority = if (message.priority == MessagePriority.SPEAK_NOW) message.priority else existing.priority,
                failureReason = null,
            )
            db.insertWithOnConflict(T_MESSAGES, null, merged.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    suspend fun upsertMessages(items: List<Message>) {
        // Newest first from the server; insert oldest-first so ordering metadata wins.
        items.sortedBy { it.createdAt }.forEach { upsertMessage(it) }
    }

    suspend fun pendingOutbox(): List<Message> = query { db ->
        db.query(T_MESSAGES, null, "$C_STATE = ?", arrayOf(MessageState.PENDING.name), null, null, "$C_CREATED_AT ASC").use { c ->
            buildList { while (c.moveToNext()) add(c.toMessage()) }
        }
    }

    suspend fun markMessageState(clientMessageId: String, state: MessageState, failure: String? = null) = transaction { db ->
        db.update(
            T_MESSAGES,
            ContentValues().apply {
                put(C_STATE, state.name)
                put(C_FAILURE, failure)
            },
            "$C_CLIENT_ID = ?", arrayOf(clientMessageId),
        )
    }

    suspend fun markConversationPreview(
        conversationId: String,
        text: String,
        at: Long,
    ) = transaction { db ->
        db.execSQL(
            "UPDATE $T_CONVERSATIONS SET $C_LAST_TEXT = ?, $C_LAST_AT = ? WHERE $C_ID = ?",
            arrayOf<Any>(text, at, conversationId),
        )
    }

    // ------------------------------------------------------- spoken ledger (dedupe)

    /**
     * Atomically claims the right to speak a message.
     *
     * This is the durable anti-duplication guard: only one caller — across push
     * redelivery, socket replay, process restarts and retries — ever receives
     * [SpeechClaim.GRANTED] for a given `clientMessageId`.
     *
     * The message row must already exist (the caller persists before evaluating).
     */
    override suspend fun claimForSpeech(clientMessageId: String, now: Long): SpokenLedger.Claim = transaction { db ->
        val values = ContentValues().apply { put(C_SPOKEN_AT, now) }
        val updated = db.update(
            T_MESSAGES, values,
            "$C_CLIENT_ID = ? AND $C_SPOKEN_AT IS NULL", arrayOf(clientMessageId),
        )
        if (updated > 0) {
            SpokenLedger.Claim.GRANTED
        } else {
            val exists = db.query(
                T_MESSAGES, arrayOf(C_CLIENT_ID), "$C_CLIENT_ID = ?", arrayOf(clientMessageId), null, null, null,
            ).use { it.moveToFirst() }
            if (exists) SpokenLedger.Claim.ALREADY_SPOKEN else SpokenLedger.Claim.UNKNOWN_MESSAGE
        }
    }

    /** Gives a claim back when queueing failed after the claim was taken. */
    override suspend fun releaseSpeechClaim(clientMessageId: String) {
        transaction { db ->
            db.update(
                T_MESSAGES,
                ContentValues().apply { putNull(C_SPOKEN_AT) },
                "$C_CLIENT_ID = ?", arrayOf(clientMessageId),
            )
        }
    }

    suspend fun markSpoken(clientMessageId: String, now: Long) = transaction { db ->
        db.update(T_MESSAGES, ContentValues().apply { put(C_SPOKEN_AT, now) }, "$C_CLIENT_ID = ?", arrayOf(clientMessageId))
    }

    override suspend fun wasSpoken(clientMessageId: String): Boolean = query { db ->
        db.query(T_MESSAGES, arrayOf(C_SPOKEN_AT), "$C_CLIENT_ID = ?", arrayOf(clientMessageId), null, null, null).use { c ->
            c.moveToFirst() && !c.isNull(0)
        }
    }

    suspend fun spokenCount(): Int = query { db ->
        db.rawQuery("SELECT COUNT(*) FROM $T_MESSAGES WHERE $C_SPOKEN_AT IS NOT NULL", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    // --------------------------------------------------------------------- contacts

    suspend fun contacts(): List<Contact> = query { db ->
        db.query(T_CONTACTS, null, null, null, null, null, "$C_DISPLAY_NAME COLLATE NOCASE ASC").use { c ->
            buildList { while (c.moveToNext()) add(c.toContact()) }
        }
    }

    suspend fun upsertContacts(items: List<Contact>) = transaction { db ->
        items.forEach { db.insertWithOnConflict(T_CONTACTS, null, it.toValues(), SQLiteDatabase.CONFLICT_REPLACE) }
    }

    suspend fun setContactTrust(userId: String, trusted: Boolean) = transaction { db ->
        db.update(T_CONTACTS, ContentValues().apply { put(C_TRUSTED, if (trusted) 1 else 0) }, "$C_USER_ID = ?", arrayOf(userId))
    }

    suspend fun deleteContact(userId: String) = transaction { db ->
        db.delete(T_CONTACTS, "$C_USER_ID = ?", arrayOf(userId))
    }

    suspend fun isTrustedSender(userId: String): Boolean = query { db ->
        db.query(T_CONTACTS, arrayOf(C_TRUSTED), "$C_USER_ID = ?", arrayOf(userId), null, null, null).use { c ->
            c.moveToFirst() && c.getInt(0) == 1
        }
    }

    // ------------------------------------------------------------------------- meta

    suspend fun putMeta(key: String, value: String) = transaction { db ->
        db.insertWithOnConflict(T_META, null, ContentValues().apply {
            put(C_META_KEY, key)
            put(C_META_VALUE, value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun meta(key: String): String? = query { db ->
        db.query(T_META, arrayOf(C_META_VALUE), "$C_META_KEY = ?", arrayOf(key), null, null, null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    /** Used on sign-out: a signed-out device keeps no message history. */
    suspend fun purgeAll() = transaction { db ->
        db.delete(T_MESSAGES, null, null)
        db.delete(T_CONVERSATIONS, null, null)
        db.delete(T_CONTACTS, null, null)
        db.delete(T_META, null, null)
    }

    // ------------------------------------------------------------------- internals

    private suspend fun <T> query(block: (SQLiteDatabase) -> T): T =
        withContext(dispatchers.io) { block(helper.readableDatabase) }

    private suspend fun <T> transaction(block: (SQLiteDatabase) -> T): T =
        withContext(dispatchers.io) {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                val result = block(db)
                db.setTransactionSuccessful()
                result
            } finally {
                db.endTransaction()
            }
        }

    // ------------------------------------------------------------------ mappings

    private fun Conversation.toValues() = ContentValues().apply {
        put(C_ID, id)
        put(C_PEER_ID, peerId)
        put(C_PEER_NAME, peerDisplayName)
        put(C_PEER_EMAIL, peerEmail)
        put(C_LAST_TEXT, lastMessageText)
        put(C_LAST_AT, lastMessageAt ?: 0L)
        put(C_UNREAD, unreadCount)
        put(C_PEER_TRUSTED, if (peerTrusted) 1 else 0)
    }

    private fun Cursor.toConversation() = Conversation(
        id = string(C_ID),
        peerId = string(C_PEER_ID),
        peerDisplayName = string(C_PEER_NAME),
        peerEmail = string(C_PEER_EMAIL),
        lastMessageText = nullableString(C_LAST_TEXT),
        lastMessageAt = getLong(getColumnIndexOrThrow(C_LAST_AT)).takeIf { it > 0 },
        unreadCount = getInt(getColumnIndexOrThrow(C_UNREAD)),
        peerTrusted = getInt(getColumnIndexOrThrow(C_PEER_TRUSTED)) == 1,
    )

    private fun Message.toValues() = ContentValues().apply {
        put(C_ID, id.ifBlank { clientMessageId })
        put(C_CLIENT_ID, clientMessageId)
        put(C_CONVERSATION_ID, conversationId)
        put(C_SENDER_ID, senderId)
        put(C_RECIPIENT_ID, recipientId)
        put(C_TEXT, text)
        put(C_CREATED_AT, createdAt)
        put(C_STATE, state.name)
        put(C_DELIVERY, deliveryState.name)
        put(C_PRIORITY, priority.name)
        put(C_IS_MINE, if (isMine) 1 else 0)
        if (spokenAt != null) put(C_SPOKEN_AT, spokenAt) else putNull(C_SPOKEN_AT)
        put(C_FAILURE, failureReason)
    }

    private fun Cursor.toMessage(): Message {
        val spokenIdx = getColumnIndexOrThrow(C_SPOKEN_AT)
        return Message(
            id = string(C_ID),
            clientMessageId = string(C_CLIENT_ID),
            conversationId = string(C_CONVERSATION_ID),
            senderId = string(C_SENDER_ID),
            recipientId = string(C_RECIPIENT_ID),
            text = string(C_TEXT),
            createdAt = getLong(getColumnIndexOrThrow(C_CREATED_AT)),
            state = runCatching { MessageState.valueOf(string(C_STATE)) }.getOrDefault(MessageState.SENT),
            deliveryState = runCatching { DeliveryState.valueOf(string(C_DELIVERY)) }.getOrDefault(DeliveryState.NONE),
            priority = runCatching { MessagePriority.valueOf(string(C_PRIORITY)) }.getOrDefault(MessagePriority.NORMAL),
            isMine = getInt(getColumnIndexOrThrow(C_IS_MINE)) == 1,
            spokenAt = if (isNull(spokenIdx)) null else getLong(spokenIdx),
            failureReason = nullableString(C_FAILURE),
        )
    }

    private fun Contact.toValues() = ContentValues().apply {
        put(C_USER_ID, userId)
        put(C_ID, id)
        put(C_DISPLAY_NAME, displayName)
        put(C_EMAIL, email)
        put(C_TRUSTED, if (isTrusted) 1 else 0)
        put(C_CONVERSATION_ID, conversationId)
    }

    private fun Cursor.toContact() = Contact(
        id = string(C_ID),
        userId = string(C_USER_ID),
        displayName = string(C_DISPLAY_NAME),
        email = string(C_EMAIL),
        isTrusted = getInt(getColumnIndexOrThrow(C_TRUSTED)) == 1,
        conversationId = nullableString(C_CONVERSATION_ID),
    )

    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column)) ?: ""

    private fun Cursor.nullableString(column: String): String? {
        val idx = getColumnIndexOrThrow(column)
        return if (isNull(idx)) null else getString(idx)
    }

    private class Db(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $T_CONVERSATIONS (
                    $C_ID TEXT PRIMARY KEY,
                    $C_PEER_ID TEXT NOT NULL,
                    $C_PEER_NAME TEXT NOT NULL,
                    $C_PEER_EMAIL TEXT NOT NULL,
                    $C_LAST_TEXT TEXT,
                    $C_LAST_AT INTEGER NOT NULL DEFAULT 0,
                    $C_UNREAD INTEGER NOT NULL DEFAULT 0,
                    $C_PEER_TRUSTED INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE $T_MESSAGES (
                    $C_ID TEXT PRIMARY KEY,
                    $C_CLIENT_ID TEXT NOT NULL UNIQUE,
                    $C_CONVERSATION_ID TEXT NOT NULL,
                    $C_SENDER_ID TEXT NOT NULL,
                    $C_RECIPIENT_ID TEXT NOT NULL,
                    $C_TEXT TEXT NOT NULL,
                    $C_CREATED_AT INTEGER NOT NULL,
                    $C_STATE TEXT NOT NULL,
                    $C_DELIVERY TEXT NOT NULL,
                    $C_PRIORITY TEXT NOT NULL,
                    $C_IS_MINE INTEGER NOT NULL DEFAULT 0,
                    $C_SPOKEN_AT INTEGER,
                    $C_FAILURE TEXT
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX idx_messages_conversation ON $T_MESSAGES ($C_CONVERSATION_ID, $C_CREATED_AT)")
            db.execSQL("CREATE INDEX idx_messages_spoken ON $T_MESSAGES ($C_SPOKEN_AT)")
            db.execSQL(
                """
                CREATE TABLE $T_CONTACTS (
                    $C_ID TEXT PRIMARY KEY,
                    $C_USER_ID TEXT NOT NULL UNIQUE,
                    $C_DISPLAY_NAME TEXT NOT NULL,
                    $C_EMAIL TEXT NOT NULL,
                    $C_TRUSTED INTEGER NOT NULL DEFAULT 0,
                    $C_CONVERSATION_ID TEXT
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE TABLE $T_META ($C_META_KEY TEXT PRIMARY KEY, $C_META_VALUE TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1: local cache only — safe to rebuild rather than migrate.
            if (oldVersion < 2) {
                db.execSQL("DROP TABLE IF EXISTS $T_MESSAGES")
                db.execSQL("DROP TABLE IF EXISTS $T_CONVERSATIONS")
                db.execSQL("DROP TABLE IF EXISTS $T_CONTACTS")
                db.execSQL("DROP TABLE IF EXISTS $T_META")
                onCreate(db)
            }
        }
    }

    companion object {
        private const val DB_NAME = "airwhispers.db"
        private const val DB_VERSION = 1

        private const val T_CONVERSATIONS = "conversations"
        private const val T_MESSAGES = "messages"
        private const val T_CONTACTS = "contacts"
        private const val T_META = "meta"

        private const val C_ID = "id"
        private const val C_CLIENT_ID = "client_id"
        private const val C_PEER_ID = "peer_id"
        private const val C_PEER_NAME = "peer_name"
        private const val C_PEER_EMAIL = "peer_email"
        private const val C_LAST_TEXT = "last_text"
        private const val C_LAST_AT = "last_at"
        private const val C_UNREAD = "unread"
        private const val C_PEER_TRUSTED = "peer_trusted"
        private const val C_CONVERSATION_ID = "conversation_id"
        private const val C_SENDER_ID = "sender_id"
        private const val C_RECIPIENT_ID = "recipient_id"
        private const val C_TEXT = "text"
        private const val C_CREATED_AT = "created_at"
        private const val C_STATE = "state"
        private const val C_DELIVERY = "delivery"
        private const val C_PRIORITY = "priority"
        private const val C_IS_MINE = "is_mine"
        private const val C_SPOKEN_AT = "spoken_at"
        private const val C_FAILURE = "failure"
        private const val C_USER_ID = "user_id"
        private const val C_DISPLAY_NAME = "display_name"
        private const val C_EMAIL = "email"
        private const val C_TRUSTED = "trusted"
        private const val C_META_KEY = "meta_key"
        private const val C_META_VALUE = "meta_value"
    }
}
