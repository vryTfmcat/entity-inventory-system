package com.obsidiancodx.entityinventory.data

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

@Entity(tableName = "file_cache")
data class FileCacheEntity(
    @PrimaryKey val documentUri: String,
    val relativePath: String,
    val modifiedAt: Long,
    val contentHash: String,
    val entityType: String,
    val stableId: String
)

@Entity(tableName = "draft_audit")
data class DraftAuditEntity(
    @PrimaryKey val auditId: String,
    val json: String,
    val updatedAt: Long
)

@Dao
interface CacheDao {
    @Query("SELECT * FROM file_cache") suspend fun allFiles(): List<FileCacheEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertFiles(files: List<FileCacheEntity>)
    @Query("DELETE FROM file_cache WHERE documentUri NOT IN (:uris)") suspend fun deleteMissing(uris: List<String>)
    @Query("DELETE FROM file_cache") suspend fun clearFiles()
    @Query("SELECT * FROM draft_audit LIMIT 1") suspend fun activeDraft(): DraftAuditEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveDraft(draft: DraftAuditEntity)
    @Query("DELETE FROM draft_audit WHERE auditId = :auditId") suspend fun deleteDraft(auditId: String)
}

@Database(entities = [FileCacheEntity::class, DraftAuditEntity::class], version = 1, exportSchema = false)
abstract class CacheDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao

    companion object {
        @Volatile private var instance: CacheDatabase? = null

        fun get(context: Context): CacheDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                CacheDatabase::class.java,
                "entity-inventory-cache.db"
            ).build().also { instance = it }
        }
    }
}
