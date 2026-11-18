package com.villamediana.novelo.sync

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * What the server has of one row of the phone (a record): its name there ([k]), the version it is at
 * ([seq]), and the row as it was when the two last agreed ([base], with its [hash]) — what tells a
 * change made here from one that came, and what a merge starts from. [pending]: a record that came
 * and couldn't go in yet (the task of a topic that hasn't arrived), tried again at each sync.
 */
@Entity(tableName = "sync_records", indices = [Index(value = ["tbl", "rid"], unique = true)])
data class SyncRecord(
    @PrimaryKey val k: String,
    val tbl: String,
    val rid: String,
    val seq: Long,
    val hash: String,
    val base: String,
    val pending: String? = null,
)

/**
 * A file of a capture on the server (its photo, the photo's thumbnail, its sound, its document): the
 * name it has there ([blob]), and where it is here ([path]; null while it is only there).
 * [slot] = "<captureId>:<image|thumb|audio|file>". [up]: it is there already.
 */
@Entity(tableName = "sync_files", indices = [Index("captureId")])
data class SyncFile(
    @PrimaryKey val slot: String,
    val captureId: Long,
    val kind: String,
    val blob: String,
    val size: Long,
    val mime: String? = null,
    val path: String? = null,
    val up: Boolean = false,
)

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_records WHERE tbl = :table")
    suspend fun records(table: String): List<SyncRecord>

    @Query("SELECT * FROM sync_records WHERE k = :k")
    suspend fun record(k: String): SyncRecord?

    @Upsert
    suspend fun put(record: SyncRecord)

    @Query("DELETE FROM sync_records WHERE k = :k")
    suspend fun drop(k: String)

    @Query("SELECT * FROM sync_records WHERE pending IS NOT NULL")
    suspend fun pending(): List<SyncRecord>

    @Query("DELETE FROM sync_records")
    suspend fun clearRecords()

    @Query("SELECT * FROM sync_files WHERE captureId = :captureId")
    suspend fun files(captureId: Long): List<SyncFile>

    @Query("SELECT * FROM sync_files")
    suspend fun allFiles(): List<SyncFile>

    @Upsert
    suspend fun putFile(file: SyncFile)

    @Query("DELETE FROM sync_files WHERE slot = :slot")
    suspend fun dropFile(slot: String)

    @Query("DELETE FROM sync_files WHERE captureId = :captureId")
    suspend fun dropFiles(captureId: Long)

    /** Files here not yet there. */
    @Query("SELECT * FROM sync_files WHERE up = 0 AND path IS NOT NULL")
    suspend fun toSend(): List<SyncFile>

    /** Files there not yet here. */
    @Query("SELECT * FROM sync_files WHERE path IS NULL AND kind != 'thumb'")
    suspend fun toFetch(): List<SyncFile>

    @Query("DELETE FROM sync_files")
    suspend fun clearFiles()
}
