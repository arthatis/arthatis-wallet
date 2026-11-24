package com.arthatis.wallet.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** One generic row per synced record. The JSON is the record itself; typed models live in `core`. */
@Entity(tableName = "records", primaryKeys = ["entity", "id"])
data class RecordRow(
    val entity: String,
    val id: String,
    val json: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    val dirty: Boolean,
)

/** Bareng (shared wallet) ledger, already decrypted with the group key. */
@Entity(tableName = "group_records", primaryKeys = ["groupId", "entity", "id"])
data class GroupRecordRow(
    val groupId: String,
    val entity: String,
    val id: String,
    val json: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    val dirty: Boolean,
)

@Entity(tableName = "kv")
data class KvRow(@androidx.room.PrimaryKey val k: String, val v: String)

@Dao
interface WalletDao {
    @Query("SELECT * FROM records")
    fun observeRecords(): Flow<List<RecordRow>>

    @Query("SELECT * FROM records")
    suspend fun records(): List<RecordRow>

    @Query("SELECT * FROM records WHERE dirty = 1 LIMIT :limit")
    suspend fun dirtyRecords(limit: Int): List<RecordRow>

    @Query("SELECT * FROM records WHERE entity = :entity AND id = :id")
    suspend fun record(entity: String, id: String): RecordRow?

    @Upsert
    suspend fun upsert(rows: List<RecordRow>)

    @Query("UPDATE records SET dirty = 0 WHERE entity = :entity AND id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(entity: String, id: String, updatedAt: Long)

    @Query("UPDATE records SET dirty = 1")
    suspend fun markAllDirty()

    @Query("DELETE FROM records")
    suspend fun clearRecords()

    @Query("SELECT * FROM group_records WHERE groupId = :groupId")
    fun observeGroup(groupId: String): Flow<List<GroupRecordRow>>

    @Query("SELECT * FROM group_records WHERE groupId = :groupId")
    suspend fun groupRows(groupId: String): List<GroupRecordRow>

    @Query("SELECT * FROM group_records WHERE groupId = :groupId AND dirty = 1")
    suspend fun dirtyGroup(groupId: String): List<GroupRecordRow>

    @Query("SELECT * FROM group_records WHERE groupId = :groupId AND entity = :entity AND id = :id")
    suspend fun groupRecord(groupId: String, entity: String, id: String): GroupRecordRow?

    @Upsert
    suspend fun upsertGroup(rows: List<GroupRecordRow>)

    @Query("UPDATE group_records SET dirty = 0 WHERE groupId = :groupId AND entity = :entity AND id = :id AND updatedAt = :updatedAt")
    suspend fun markGroupClean(groupId: String, entity: String, id: String, updatedAt: Long)

    @Query("DELETE FROM group_records")
    suspend fun clearGroups()

    @Query("SELECT v FROM kv WHERE k = :k")
    suspend fun kv(k: String): String?

    @Upsert
    suspend fun setKv(row: KvRow)

    @Query("DELETE FROM kv")
    suspend fun clearKv()
}

@Database(entities = [RecordRow::class, GroupRecordRow::class, KvRow::class], version = 1, exportSchema = false)
abstract class WalletDatabase : RoomDatabase() {
    abstract fun dao(): WalletDao

    companion object {
        fun open(context: Context): WalletDatabase =
            Room.databaseBuilder(context, WalletDatabase::class.java, "arthatis-wallet.db").build()
    }
}
