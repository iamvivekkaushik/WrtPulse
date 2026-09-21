package com.vivekkaushik.wrtpulse.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vivekkaushik.wrtpulse.net.SshTarget
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * One saved router. The password is never stored as text — [credential] is an
 * AES-GCM blob sealed by a Keystore key, opened only after the biometric gate.
 */
@Entity(tableName = "routers", indices = [Index(value = ["identity"], unique = true)])
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
    /**
     * What host keys are pinned to — see [SshTarget.identity]. Rows saved before this column
     * existed carry "host:port", the scope their pins were already stored under.
     */
    @ColumnInfo(defaultValue = "") val identity: String = "$host:$port",
    /** Set on a mesh node: the identity of the primary it belongs to. */
    val meshPrimary: String? = null,
    /** "wired" or "wireless" — how a node reaches its primary. */
    val meshBackhaul: String? = null,
    /** The archive taken just before this router became a node, which Leave mesh restores. */
    val meshSnapshot: String? = null,
    /** The MAC of a node's mesh point, so the primary's peer list can name it. */
    val meshMac: String? = null,
    /** On a primary: the sealed [com.vivekkaushik.wrtpulse.ops.MeshProfile] nodes are built from. */
    val meshProfile: ByteArray? = null,
    /**
     * The section the list files this router under — "Home", "Office" — or null for none. A
     * mesh node without one sits with its primary. Not `group`: that is a word SQL keeps.
     */
    val groupName: String? = null,
) {
    val isMeshNode: Boolean get() = meshPrimary != null
    /** The connection this row describes, carrying its identity so its own pins apply. */
    val sshTarget: SshTarget get() = SshTarget(host, port, username, identity)

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
            identity == other.identity &&
            meshPrimary == other.meshPrimary &&
            meshBackhaul == other.meshBackhaul &&
            meshSnapshot == other.meshSnapshot &&
            meshMac == other.meshMac &&
            groupName == other.groupName &&
            credential.contentEquals(other.credential) &&
            privateKey.contentEquals(other.privateKey) &&
            meshProfile.contentEquals(other.meshProfile)
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
        result = 31 * result + identity.hashCode()
        result = 31 * result + (credential?.contentHashCode() ?: 0)
        result = 31 * result + (privateKey?.contentHashCode() ?: 0)
        result = 31 * result + (meshPrimary?.hashCode() ?: 0)
        result = 31 * result + (meshBackhaul?.hashCode() ?: 0)
        result = 31 * result + (meshSnapshot?.hashCode() ?: 0)
        result = 31 * result + (meshMac?.hashCode() ?: 0)
        result = 31 * result + (meshProfile?.contentHashCode() ?: 0)
        result = 31 * result + (groupName?.hashCode() ?: 0)
        return result
    }

    companion object {
        /** A fresh identity for a router being added. Opaque; never shown. */
        fun newIdentity(): String = UUID.randomUUID().toString()
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

    @Query("SELECT * FROM routers WHERE identity = :identity LIMIT 1")
    suspend fun byIdentity(identity: String): RouterEntity?

    /**
     * Every row that knocks on this address as this user. More than one is legitimate — two
     * networks, two routers, both at 192.168.1.1 — which is why this is a list and not a
     * lookup: the caller tells them apart by host key, not by address.
     */
    @Query("SELECT * FROM routers WHERE host = :host AND port = :port AND username = :username")
    suspend fun atAddress(host: String, port: Int, username: String): List<RouterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(router: RouterEntity): Long

    @Query("UPDATE routers SET lastSeenEpoch = :epoch WHERE id = :id")
    suspend fun touch(id: Long, epoch: Long)

    @Query("UPDATE routers SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    /**
     * The router answers somewhere else now — after a LAN subnet change, the saved entry has
     * to follow it or the list points at an address nothing is on.
     */
    @Query("UPDATE routers SET host = :host, port = :port WHERE id = :id")
    suspend fun rehost(id: Long, host: String, port: Int)

    @Query("DELETE FROM routers WHERE id = :id")
    suspend fun delete(id: Long)

    /** The nodes of one primary, by its identity. */
    @Query("SELECT * FROM routers WHERE meshPrimary = :primary ORDER BY name")
    suspend fun nodesOf(primary: String): List<RouterEntity>

    /** Marks a row as a node of [primary]; nulls across the board make it a plain router again. */
    @Query(
        "UPDATE routers SET meshPrimary = :primary, meshBackhaul = :backhaul, " +
            "meshSnapshot = :snapshot, meshMac = :mac WHERE id = :id"
    )
    suspend fun setMesh(id: Long, primary: String?, backhaul: String?, snapshot: String?, mac: String?)

    /** The profile a primary's nodes are built from; null forgets it. */
    @Query("UPDATE routers SET meshProfile = :profile WHERE id = :id")
    suspend fun setMeshProfile(id: Long, profile: ByteArray?)

    /** A node that joined with a freshly installed key keeps it, password gone. */
    @Query("UPDATE routers SET privateKey = :privateKey, credential = NULL WHERE id = :id")
    suspend fun setPrivateKey(id: Long, privateKey: ByteArray)

    /** Files the row under a list section; null takes it out of any. */
    @Query("UPDATE routers SET groupName = :group WHERE id = :id")
    suspend fun setGroup(id: Long, group: String?)
}

@Database(entities = [RouterEntity::class, ClientName::class], version = 7, exportSchema = false)
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

        /**
         * Existing rows take "host:port" as their identity — the scope their host keys were
         * pinned under all along — so nobody re-confirms a fingerprint after the upgrade.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routers ADD COLUMN identity TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE routers SET identity = host || ':' || port WHERE identity = ''")
            }
        }

        /** Mesh membership and the primary's profile: five nullable columns, no backfill. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routers ADD COLUMN meshPrimary TEXT")
                db.execSQL("ALTER TABLE routers ADD COLUMN meshBackhaul TEXT")
                db.execSQL("ALTER TABLE routers ADD COLUMN meshSnapshot TEXT")
                db.execSQL("ALTER TABLE routers ADD COLUMN meshMac TEXT")
                db.execSQL("ALTER TABLE routers ADD COLUMN meshProfile BLOB")
            }
        }

        /**
         * Identity is what tells two saved routers apart — the session, the host-key pins and
         * the mesh rows all key on it — so two rows sharing one is two routers the app cannot
         * tell apart: both light up as connected, and a write meant for one lands on the other.
         * The index makes that impossible from here on; first, any duplicates that already
         * exist get a fresh identity, the row seen most recently keeping the old one (and its
         * pins). The others confirm a fingerprint once more when next opened.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE routers SET identity = lower(hex(randomblob(16))) WHERE id IN (" +
                        "SELECT r.id FROM routers r WHERE EXISTS (SELECT 1 FROM routers o " +
                        "WHERE o.identity = r.identity AND o.id != r.id AND " +
                        "(o.lastSeenEpoch > r.lastSeenEpoch OR (o.lastSeenEpoch = r.lastSeenEpoch AND o.id < r.id))))"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_routers_identity ON routers(identity)")
            }
        }

        /** One nullable column: a saved router's list section. Rows without one look as before. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routers ADD COLUMN groupName TEXT")
            }
        }

        fun build(context: Context): WrtDb =
            Room.databaseBuilder(context, WrtDb::class.java, "wrtpulse.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build()
    }
}
