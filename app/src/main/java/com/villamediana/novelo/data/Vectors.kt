package com.villamediana.novelo.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * The meaning of a piece of something kept (1.74.0): a note, task or diary ([KIND_ENTRY]; a long
 * one in pieces, [chunk] 0, 1…), a photo ([KIND_PHOTO]) or a file ([KIND_FILE]), as the numbers the
 * search by meaning compares (see mind/Senses). [space]: the model and size they were made in;
 * [hash]: of the text they were made from (the same text again needs no model); [start]: where the
 * piece starts in the item's text. The numbers in 8 bits, [scale] each. No foreign key: what is
 * left of a deleted item goes on the next pass, and an import that puts everything back doesn't
 * take the vectors away with it. In the backup as vectors.bin, never in backup.json.
 */
@Entity(tableName = "vectors", primaryKeys = ["kind", "itemId", "chunk"])
class Vector(
    val kind: Int,
    val itemId: Long,
    val chunk: Int,
    val space: String,
    val hash: Long,
    val start: Int,
    val scale: Float,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val vec: ByteArray,
) {
    companion object {
        const val KIND_ENTRY = 0
        const val KIND_PHOTO = 1
        const val KIND_FILE = 2
    }
}

/** A vector without its numbers: what a pass compares with what should be there. */
data class VectorKey(val kind: Int, val itemId: Long, val chunk: Int, val space: String, val hash: Long)

@Dao
interface VectorDao {
    @Query("SELECT kind, itemId, chunk, space, hash FROM vectors")
    suspend fun keys(): List<VectorKey>

    @Query("SELECT * FROM vectors WHERE space = :space")
    suspend fun all(space: String): List<Vector>

    @Query("SELECT * FROM vectors WHERE space = :space ORDER BY kind, itemId, chunk")
    suspend fun ordered(space: String): List<Vector>

    /** One made from this very text before (an item put back, a piece that didn't change place). */
    @Query("SELECT * FROM vectors WHERE space = :space AND hash = :hash LIMIT 1")
    suspend fun byHash(space: String, hash: Long): Vector?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(list: List<Vector>)

    @Query("DELETE FROM vectors WHERE kind = :kind AND itemId = :itemId AND chunk = :chunk")
    suspend fun delete(kind: Int, itemId: Long, chunk: Int)

    @Query("DELETE FROM vectors WHERE space != :space")
    suspend fun deleteOtherSpaces(space: String)

    @Query("DELETE FROM vectors")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM vectors WHERE space = :space")
    suspend fun count(space: String): Int
}
