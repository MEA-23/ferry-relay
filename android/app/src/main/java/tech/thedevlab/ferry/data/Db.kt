package tech.thedevlab.ferry.data

import android.content.Context
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
)

/** Event ids already handled, so push and pull never show the same event twice (§3.4, §7.3). */
@Entity(tableName = "seen")
data class Seen(@PrimaryKey val id: String, val at: Long)

/** A thread's newest message and its unread count. */
data class ThreadSummary(@Embedded val last: Message, val unreadCount: Int)

@Dao
abstract class MessageDao {
    @Query("SELECT occurredAt FROM messages WHERE rowKey = :rowKey")
    abstract suspend fun occurredAt(rowKey: String): Long?

    /** Use [save], which keeps a later call state from being overwritten by an earlier one. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(message: Message)

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
        SELECT m.*, t.unreadCount FROM messages m
        JOIN (SELECT threadKey AS tk, MAX(occurredAt) AS lastAt, SUM(unread) AS unreadCount FROM messages GROUP BY threadKey) t
          ON m.threadKey = t.tk AND m.occurredAt = t.lastAt
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

    @Query("SELECT COUNT(*) FROM messages WHERE unread = 1")
    abstract fun unreadCount(): Flow<Int>

    @Query("UPDATE messages SET unread = 0 WHERE threadKey = :threadKey AND unread = 1")
    abstract suspend fun markRead(threadKey: String)

    @Query("DELETE FROM messages WHERE threadKey = :threadKey")
    abstract suspend fun deleteThread(threadKey: String)

    companion object {
        /** A LIKE pattern matching messages whose text contains [query], however it's accented or cased. */
        fun likePattern(query: String): String =
            "%" + TextTools.normalize(query.trim()).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    }
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

@Database(entities = [OutboxItem::class, Activity::class, Message::class, Seen::class], version = 1)
abstract class FerryDb : RoomDatabase() {
    abstract fun outbox(): OutboxDao
    abstract fun activity(): ActivityDao
    abstract fun messages(): MessageDao
    abstract fun seen(): SeenDao
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
