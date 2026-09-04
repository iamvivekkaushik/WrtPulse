package com.vivekkaushik.wrtpulse.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One saved router. The password is never stored as text — [credential] is an
 * AES-GCM blob sealed by a Keystore key, opened only after the biometric gate.
 */
@Entity(tableName = "routers")
data class RouterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,          // display name: hostname, else model, else host
    val host: String,
    val port: Int,
    val username: String,
    val model: String,
    val summary: String,       // "OpenWrt 24.10 · r28xxx · MediaTek …"
    val credential: ByteArray?,
    val lastSeenEpoch: Long,
    val privateKey: ByteArray? = null,  // sealed OpenSSH PEM; when set, key auth replaces the password
) {
    /**
     * Compared field by field, with content equality for the sealed blobs.
     *
     * Both easy answers are wrong here. Generated data-class equality compares the
     * ByteArrays by IDENTITY, so two reads of the same row never match. Comparing by `id`
     * alone — what this used to do — fails the other way: a renamed row compares EQUAL to
     * its old self, so `collectAsState`'s structural equality sees no change and Compose
     * never recomposes. A rename then wrote to the database and appeared to do nothing.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RouterEntity) return false
        return id == other.id &&
            name == other.name &&
            host == other.host &&
            port == other.port &&
            username == other.username &&
            model == other.model &&
            summary == other.summary &&
            lastSeenEpoch == other.lastSeenEpoch &&
            credential.contentEquals(other.credential) &&
            privateKey.contentEquals(other.privateKey)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + host.hashCode()
        result = 31 * result + port
        result = 31 * result + username.hashCode()
        result = 31 * result + model.hashCode()
        result = 31 * result + summary.hashCode()
        result = 31 * result + lastSeenEpoch.hashCode()
        result = 31 * result + (credential?.contentHashCode() ?: 0)
        result = 31 * result + (privateKey?.contentHashCode() ?: 0)
        return result
    }
}

/** A user-chosen display name for a client, keyed by MAC. Local to the app. */
@Entity(tableName = "client_names")
data class ClientName(
    @PrimaryKey val mac: String,
    val name: String,
)

@Dao
interface ClientNameDao {
    @Query("SELECT * FROM client_names")
    fun all(): Flow<List<ClientName>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(name: ClientName)

    @Query("DELETE FROM client_names WHERE mac = :mac")
    suspend fun delete(mac: String)
}

@Dao
interface RouterDao {
    @Query("SELECT * FROM routers ORDER BY lastSeenEpoch DESC")
    fun all(): Flow<List<RouterEntity>>

    @Query("SELECT COUNT(*) FROM routers")
    suspend fun count(): Int

    @Query("SELECT * FROM routers WHERE host = :host AND port = :port AND username = :username LIMIT 1")
    suspend fun find(host: String, port: Int, username: String): RouterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(router: RouterEntity): Long

    @Query("UPDATE routers SET lastSeenEpoch = :epoch WHERE id = :id")
    suspend fun touch(id: Long, epoch: Long)

    @Query("UPDATE routers SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM routers WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [RouterEntity::class, ClientName::class], version = 3, exportSchema = false)
abstract class WrtDb : RoomDatabase() {
    abstract fun routers(): RouterDao
    abstract fun clientNames(): ClientNameDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routers ADD COLUMN privateKey BLOB")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS client_names (" +
                        "mac TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)"
                )
            }
        }

        fun build(context: Context): WrtDb =
            Room.databaseBuilder(context, WrtDb::class.java, "wrtpulse.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
