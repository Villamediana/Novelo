package com.villamediana.novelo.backup

import com.villamediana.novelo.data.Vector
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The meanings in the backup (1.74.0): vectors.bin, beside backup.json in "Novelo backup.zip". An
 * import puts them back, so a new phone searches by meaning without embedding everything again.
 * "NVEC", a version, the space and its size, how many; then each: kind, item, piece, where it
 * starts, the text's hash, the scale and the numbers (8 bits). An older app leaves it alone.
 */
object VectorsFile {
    const val NAME = "vectors.bin"
    private val MAGIC = byteArrayOf('N'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(), 'C'.code.toByte())
    private const val VERSION = 1

    data class Read(val space: String, val dims: Int, val rows: List<Vector>)

    fun write(out: OutputStream, space: String, dims: Int, rows: List<Vector>) {
        val d = DataOutputStream(out)
        d.write(MAGIC)
        d.writeByte(VERSION)
        d.writeShort(dims)
        d.writeUTF(space)
        val ok = rows.filter { it.space == space && it.vec.size == dims }
        d.writeInt(ok.size)
        for (v in ok) {
            d.writeByte(v.kind)
            d.writeLong(v.itemId)
            d.writeShort(v.chunk)
            d.writeInt(v.start)
            d.writeLong(v.hash)
            d.writeFloat(v.scale)
            d.write(v.vec)
        }
        d.flush()
    }

    /** What [input] has; null when it isn't this file (another, broken or cut short). */
    fun read(input: InputStream): Read? = runCatching {
        val d = DataInputStream(input)
        val magic = ByteArray(4).also { d.readFully(it) }
        if (!magic.contentEquals(MAGIC) || d.readUnsignedByte() != VERSION) return null
        val dims = d.readUnsignedShort()
        val space = d.readUTF()
        val n = d.readInt()
        if (n < 0 || dims == 0) return null
        val rows = ArrayList<Vector>(minOf(n, 100_000))
        repeat(n) {
            val kind = d.readUnsignedByte()
            val item = d.readLong()
            val chunk = d.readUnsignedShort()
            val start = d.readInt()
            val hash = d.readLong()
            val scale = d.readFloat()
            val vec = ByteArray(dims).also { d.readFully(it) }
            rows += Vector(kind, item, chunk, space, hash, start, scale, vec)
        }
        Read(space, dims, rows)
    }.getOrNull()
}
