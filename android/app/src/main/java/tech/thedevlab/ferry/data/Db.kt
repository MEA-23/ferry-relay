package tech.thedevlab.ferry.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.DataInputStream
import java.io.File

/** An encrypted envelope waiting to be uploaded (§6.2). Survives restarts until the server confirms it. */
@Entity(tableName = "outbox")
data class OutboxItem(
    @PrimaryKey val id: String,
    val kind: String,
    val keyId: String,
    val nonce: String,
    val ct: String,
    val createdAt: Long,
)

/**
 * A captured event a node keeps until its first receiver answers and it has a key to encrypt
 * with (§5.6 flow A). The database is encrypted at rest, like everything else here.
 */
@Entity(tableName = "pending")
data class PendingEvent(
    @PrimaryKey val id: String,
    val kind: String,
    val plaintext: String,
    val createdAt: Long,
)

/**
 * A command this node received (§6.6), whether it sent the text or refused it. Its id keeps a
 * replay from sending twice, the times count towards the rate limit, and the newest ones are the
 * "Recently sent" list.
 */
@Entity(tableName = "sent", indices = [Index("at")])
data class SentSms(
    @PrimaryKey val commandId: String,
    val to: String,
    val body: String,
    val at: Long,
    /** sending · sent · delivered · failed:<reason> */
    val status: String,
    /** Handed to the radio, so it counts towards the rate limit. */
    val dispatched: Boolean,
)

/** Numbers this node relayed a text or call from, which replies may go to without the SMS store (§6.6). */
@Entity(tableName = "known_numbers")
data class KnownNumber(@PrimaryKey val number: String, val at: Long)

/** A pulled event from a node with no trusted profile yet; retried after the next device refresh (§3.7). */
@Entity(tableName = "held")
data class HeldEvent(
    @PrimaryKey val id: String,
    val json: String,
    val receivedAt: Long,
)

/** Events this phone captured and relayed, for the source's "Recent" list. Never leaves the device. */
@Entity(tableName = "activity", indices = [Index(value = ["eventId"], unique = true)])
data class Activity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val eventId: String,
    val type: String,
    val title: String,
    val body: String,
    val simSlot: Int?,
    val occurredAt: Long,
    val received: Boolean,
    val sourceDeviceId: String?,
)

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(item: OutboxItem)

    @Query("SELECT * FROM outbox ORDER BY createdAt LIMIT :limit")
    suspend fun take(limit: Int): List<OutboxItem>

    @Query("DELETE FROM outbox WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("SELECT COUNT(*) FROM outbox")
    fun count(): Flow<Int>

    @Query("DELETE FROM outbox")
    suspend fun clear()
}

@Dao
interface SentDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: SentSms): Long

    @Query("SELECT EXISTS(SELECT 1 FROM sent WHERE commandId = :id)")
    suspend fun exists(id: String): Boolean

    @Query("SELECT COUNT(*) FROM sent WHERE dispatched = 1 AND at >= :since")
    suspend fun dispatchedSince(since: Long): Int

    @Query("SELECT * FROM sent WHERE at >= :since")
    suspend fun since(since: Long): List<SentSms>

    @Query("SELECT * FROM sent ORDER BY at DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<SentSms>>

    @Query("UPDATE sent SET status = :status WHERE commandId = :id")
    suspend fun setStatus(id: String, status: String)

    @Query("DELETE FROM sent WHERE at < :before")
    suspend fun prune(before: Long)
}

@Dao
interface KnownNumberDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(items: List<KnownNumber>)

    @Query("SELECT number FROM known_numbers ORDER BY at DESC LIMIT 5000")
    suspend fun all(): List<String>
}

@Dao
interface PendingDao {
    @Insert
    suspend fun insert(item: PendingEvent)

    @Query("SELECT * FROM pending ORDER BY createdAt")
    suspend fun all(): List<PendingEvent>

    @Query("DELETE FROM pending WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)
}

@Dao
interface HeldDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: HeldEvent)

    @Query("SELECT * FROM held ORDER BY receivedAt")
    suspend fun all(): List<HeldEvent>

    @Query("SELECT COUNT(*) FROM held")
    suspend fun count(): Int

    @Query("DELETE FROM held WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM held WHERE receivedAt < :before")
    suspend fun prune(before: Long)
}

@Dao
interface ActivityDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(activity: Activity): Long

    @Query("SELECT * FROM activity ORDER BY occurredAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<Activity>>

    @Query("SELECT EXISTS(SELECT 1 FROM activity WHERE eventId = :eventId)")
    suspend fun exists(eventId: String): Boolean

    @Query("DELETE FROM activity WHERE rowId NOT IN (SELECT rowId FROM activity ORDER BY occurredAt DESC LIMIT :keep)")
    suspend fun prune(keep: Int)

    @Query("DELETE FROM activity")
    suspend fun clear()
}

/**
 * A received event, decrypted, as the inbox shows it (receiver role). Calls that share a
 * `callKey` share a row, so a ringing call becomes missed or answered in place (§4.3).
 */
@Entity(tableName = "messages", indices = [Index("threadKey", "occurredAt"), Index("eventId")])
data class Message(
    /** The event id, or the call's merge key. */
    @PrimaryKey val rowKey: String,
    val eventId: String,
    val sourceDeviceId: String,
    /** sms · call · notification */
    val type: String,
    /** One thread per source phone and sender, as on iOS: `p:<device>:<number>` or `n:<device>:<package>`. */
    val threadKey: String,
    val title: String,
    val body: String,
    /** The other party's number for texts and calls; null when unknown. */
    val number: String?,
    val simSlot: Int?,
    val occurredAt: Long,
    val receivedAt: Long,
    /** A text sent from the source phone (§4.2). */
    val outgoing: Boolean,
    val unread: Boolean,
    /** [TextTools.normalize]d body. Conversation names are matched separately, from the thread list. */
    val searchText: String,
    /** A text sent from here through a node (§6.6): sending · sent · delivered · failed:<reason>. */
    val status: String? = null,
    /** The SIM it came in on, so a reply goes out on the same one. */
    val subscriptionId: Int? = null,
    /** Arrived without sound or a banner, and doesn't count towards the badge: see [Filters]. */
    @ColumnInfo(defaultValue = "0") val muted: Boolean = false,
    /** Looked like marketing when last scored; the inbox offers to mute its sender. */
    @ColumnInfo(defaultValue = "0") val suspect: Boolean = false,
)

/**
 * A mute rule or a decision about one, on this phone only: a muted sender (its normalized number)
 * or app (its package), a muted word, a sender kept as not marketing, or a suggestion turned down.
 * [kind] is one of the [Filters] kinds.
 */
@Entity(tableName = "mutes", primaryKeys = ["kind", "value"])
data class Mute(val kind: String, val value: String, val label: String, val at: Long)

/**
 * How many muted senders ([spam]) and wanted conversations ([ham]) used each word, rebuilt from
 * the inbox whenever the rules change. The row with an empty token holds the document totals.
 */
@Entity(tableName = "filter_words")
data class FilterWord(@PrimaryKey val token: String, val spam: Int, val ham: Int)

/** A conversation moved out of the inbox. A new message that isn't muted brings it back. */
@Entity(tableName = "archived")
data class Archived(@PrimaryKey val threadKey: String, val at: Long)

/** Event ids already handled, so push and pull never show the same event twice (§3.4, §7.3). */
@Entity(tableName = "seen")
data class Seen(@PrimaryKey val id: String, val at: Long)

/** A thread's newest message, its unread count, and whether it's archived. */
data class ThreadSummary(@Embedded val last: Message, val unreadCount: Int, val archived: Boolean = false)

@Dao
abstract class MessageDao {
    @Query("SELECT occurredAt FROM messages WHERE rowKey = :rowKey")
    abstract suspend fun occurredAt(rowKey: String): Long?

    /** Use [save], which keeps a later call state from being overwritten by an earlier one. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(message: Message)

    @Query("SELECT status FROM messages WHERE rowKey = :rowKey")
    abstract suspend fun status(rowKey: String): String?

    @Query("UPDATE messages SET status = :status WHERE rowKey = :rowKey")
    abstract suspend fun setStatus(rowKey: String, status: String)

    /**
     * A text sent through a node (§6.6), from this phone or another receiver. Keyed by its command,
     * so the node's copy replaces the one queued here, and it keeps any status that's further along.
     */
    @Transaction
    open suspend fun saveSent(message: Message) {
        val old = status(message.rowKey)
        upsert(message.copy(status = listOfNotNull(old, message.status).maxByOrNull(::statusRank)))
    }

    /** Applies a §4.8 status, which never moves backwards. */
    @Transaction
    open suspend fun updateStatus(rowKey: String, status: String) {
        val old = status(rowKey) ?: return
        if (statusRank(status) > statusRank(old)) setStatus(rowKey, status)
    }

    /** Saves [message] unless a later state of the same call is already stored. Returns whether it was saved. */
    @Transaction
    open suspend fun save(message: Message): Boolean {
        val existing = occurredAt(message.rowKey)
        if (existing != null && existing > message.occurredAt) return false
        upsert(message)
        return true
    }

    @Query(
        """
        SELECT m.*, t.unreadCount, a.threadKey IS NOT NULL AS archived FROM messages m
        JOIN (SELECT threadKey AS tk, MAX(occurredAt) AS lastAt, SUM(unread) AS unreadCount FROM messages GROUP BY threadKey) t
          ON m.threadKey = t.tk AND m.occurredAt = t.lastAt
        LEFT JOIN archived a ON a.threadKey = m.threadKey
        GROUP BY m.threadKey
        ORDER BY m.occurredAt DESC
        """,
    )
    abstract fun threads(): Flow<List<ThreadSummary>>

    @Query("SELECT * FROM messages WHERE threadKey = :threadKey ORDER BY occurredAt, rowKey")
    abstract fun thread(threadKey: String): Flow<List<Message>>

    /** Newest first. [pattern] is a LIKE pattern over [Message.searchText]; see [likePattern]. */
    @Query("SELECT * FROM messages WHERE searchText LIKE :pattern ESCAPE '\\' ORDER BY occurredAt DESC LIMIT :limit")
    abstract fun search(pattern: String, limit: Int): Flow<List<Message>>

    /** The badge: unread messages that weren't muted, outside archived conversations. */
    @Query("SELECT COUNT(*) FROM messages WHERE unread = 1 AND muted = 0 AND threadKey NOT IN (SELECT threadKey FROM archived)")
    abstract fun unreadCount(): Flow<Int>

    @Query("UPDATE messages SET unread = 0 WHERE threadKey = :threadKey AND unread = 1")
    abstract suspend fun markRead(threadKey: String)

    @Query("DELETE FROM messages WHERE threadKey = :threadKey")
    abstract suspend fun deleteThread(threadKey: String)

    @Query("SELECT DISTINCT threadKey FROM messages")
    abstract suspend fun threadKeys(): List<String>

    @Query("UPDATE messages SET muted = :muted, suspect = 0 WHERE threadKey IN (:threadKeys)")
    abstract suspend fun setMuted(threadKeys: List<String>, muted: Boolean)

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE threadKey = :threadKey AND outgoing = 1)")
    abstract suspend fun hasOutgoing(threadKey: String): Boolean

    /** Every text, newest first, for learning from mutes. */
    @Query("SELECT * FROM messages WHERE type = 'sms' ORDER BY occurredAt DESC")
    abstract suspend fun texts(): List<Message>

    @Query("UPDATE messages SET suspect = 0 WHERE suspect = 1")
    abstract suspend fun clearSuspects()

    @Query("UPDATE messages SET suspect = 1 WHERE rowKey IN (:rowKeys)")
    abstract suspend fun markSuspects(rowKeys: List<String>)

    /** The newest text on this thread, sent or received, whose SIM a reply goes out on (§6.6). */
    @Query("SELECT * FROM messages WHERE threadKey = :threadKey AND type = 'sms' ORDER BY occurredAt DESC LIMIT 1")
    abstract suspend fun lastText(threadKey: String): Message?

    companion object {
        fun statusRank(status: String) = when {
            status == "sending" -> 0
            status == "sent" -> 1
            status == "delivered" -> 2
            else -> 3 // failed:<reason>
        }

        /** A LIKE pattern matching messages whose text contains [query], however it's accented or cased. */
        fun likePattern(query: String): String =
            "%" + TextTools.normalize(query.trim()).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    }
}

@Dao
interface MuteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(mute: Mute)

    @Query("DELETE FROM mutes WHERE kind = :kind AND value = :value")
    suspend fun delete(kind: String, value: String)

    @Query("SELECT * FROM mutes ORDER BY at DESC")
    suspend fun list(): List<Mute>

    @Query("SELECT * FROM mutes ORDER BY at DESC")
    fun all(): Flow<List<Mute>>
}

@Dao
interface FilterWordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(words: List<FilterWord>)

    @Query("DELETE FROM filter_words")
    suspend fun clear()

    @Query("SELECT * FROM filter_words WHERE token IN (:tokens)")
    suspend fun find(tokens: List<String>): List<FilterWord>

    @Query("SELECT * FROM filter_words")
    suspend fun all(): List<FilterWord>
}

@Dao
interface ArchivedDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: Archived)

    @Query("DELETE FROM archived WHERE threadKey = :threadKey")
    suspend fun delete(threadKey: String)
}

@Dao
interface SeenDao {
    /** Returns -1 if the id was already seen. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(seen: Seen): Long

    @Query("SELECT EXISTS(SELECT 1 FROM seen WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Query("DELETE FROM seen WHERE at < :before")
    suspend fun prune(before: Long)
}

@Database(
    entities = [
        OutboxItem::class, Activity::class, Message::class, Seen::class, PendingEvent::class, HeldEvent::class,
        SentSms::class, KnownNumber::class, Mute::class, FilterWord::class, Archived::class,
    ],
    version = 4,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class FerryDb : RoomDatabase() {
    abstract fun outbox(): OutboxDao
    abstract fun sent(): SentDao
    abstract fun knownNumbers(): KnownNumberDao
    abstract fun pending(): PendingDao
    abstract fun held(): HeldDao
    abstract fun activity(): ActivityDao
    abstract fun messages(): MessageDao
    abstract fun seen(): SeenDao
    abstract fun mutes(): MuteDao
    abstract fun filterWords(): FilterWordDao
    abstract fun archived(): ArchivedDao
}

private const val DB_NAME = "ferry.db"
private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray()

/**
 * Opens the local database, encrypted with SQLCipher under a random key that the Android Keystore
 * seals (§9.1). A plaintext database from before encryption is encrypted in place, so queued
 * uploads survive. One under a key that's gone can't be read, so it starts fresh; the next pull
 * fetches whatever the server still holds.
 */
fun openFerryDb(context: Context, store: Store): FerryDb {
    System.loadLibrary("sqlcipher")
    val file = context.getDatabasePath(DB_NAME)
    val key = store.databaseKey ?: store.newDatabaseKey().also { if (file.exists() && !isPlaintext(file)) deleteDbFiles(file) }
    if (file.exists() && isPlaintext(file)) encryptInPlace(file, key)
    return Room.databaseBuilder(context, FerryDb::class.java, DB_NAME)
        .openHelperFactory(SupportOpenHelperFactory(rawKey(key).toByteArray()))
        .build()
}

/** A raw key skips SQLCipher's PBKDF2, which only helps with passphrases. */
private fun rawKey(key: ByteArray) = "x'" + key.joinToString("") { "%02x".format(it) } + "'"

private fun isPlaintext(file: File): Boolean {
    if (file.length() < SQLITE_HEADER.size) return false
    val header = ByteArray(SQLITE_HEADER.size)
    DataInputStream(file.inputStream()).use { it.readFully(header) }
    return header.contentEquals(SQLITE_HEADER)
}

/** Copies a plaintext database into an encrypted one, then swaps it in. A crash before the swap leaves the original. */
private fun encryptInPlace(file: File, key: ByteArray) {
    val tmp = File(file.path + ".encrypting")
    deleteDbFiles(tmp)
    SQLiteDatabase.openDatabase(file.path, "", null, SQLiteDatabase.OPEN_READWRITE, null, null).use { db ->
        db.execSQL("ATTACH DATABASE ? AS encrypted KEY ?", arrayOf(tmp.path, rawKey(key)))
        db.rawExecSQL("SELECT sqlcipher_export('encrypted')")
        // Room reads its schema version from user_version; carry it over explicitly.
        db.execSQL("PRAGMA encrypted.user_version = ${db.version}")
        db.execSQL("DETACH DATABASE encrypted")
    }
    for (suffix in listOf("-journal", "-wal", "-shm")) File(file.path + suffix).delete()
    check(tmp.renameTo(file)) { "couldn't replace $DB_NAME with its encrypted copy" }
}

private fun deleteDbFiles(file: File) {
    for (suffix in listOf("", "-journal", "-wal", "-shm")) File(file.path + suffix).delete()
}
