package com.vivekkaushik.wrtpulse.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A local AI result. Provider credentials and the raw router payload are never persisted. */
@Entity(
    tableName = "ai_analysis_logs",
    indices = [Index(value = ["routerIdentity", "createdAtEpochMs"])],
)
data class AiAnalysisLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routerIdentity: String,
    val routerHost: String,
    val question: String,
    val answer: String,
    val model: String,
    val createdAtEpochMs: Long,
    val healthScore: Int?,
    val findingCount: Int,
)

@Dao
interface AiAnalysisLogDao {
    @Query("SELECT * FROM ai_analysis_logs WHERE routerIdentity = :identity ORDER BY createdAtEpochMs DESC, id DESC")
    fun forRouter(identity: String): Flow<List<AiAnalysisLog>>

    @Insert
    suspend fun insert(log: AiAnalysisLog): Long

    @Query("DELETE FROM ai_analysis_logs WHERE id = :id AND routerIdentity = :identity")
    suspend fun delete(id: Long, identity: String)

    @Query("DELETE FROM ai_analysis_logs WHERE routerIdentity = :identity")
    suspend fun deleteForRouter(identity: String)

    /** Keep only the newest [limit] entries for this router. */
    @Query("DELETE FROM ai_analysis_logs WHERE routerIdentity = :identity AND id NOT IN (SELECT id FROM ai_analysis_logs WHERE routerIdentity = :identity ORDER BY createdAtEpochMs DESC, id DESC LIMIT :limit)")
    suspend fun trim(identity: String, limit: Int)
}
