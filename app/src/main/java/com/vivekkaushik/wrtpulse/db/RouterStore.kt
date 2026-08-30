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
    override fun equals(other: Any?) = other is RouterEntity && other.id == id
    override fun hashCode() = id.hashCode()
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
