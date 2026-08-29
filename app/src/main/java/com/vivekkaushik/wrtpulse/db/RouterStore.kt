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
) {
    override fun equals(other: Any?) = other is RouterEntity && other.id == id
    override fun hashCode() = id.hashCode()
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

@Database(entities = [RouterEntity::class], version = 1, exportSchema = false)
abstract class WrtDb : RoomDatabase() {
    abstract fun routers(): RouterDao

    companion object {
        fun build(context: Context): WrtDb =
            Room.databaseBuilder(context, WrtDb::class.java, "wrtpulse.db").build()
    }
}
