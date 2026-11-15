package com.villamediana.novelo.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** 16-bit mono PCM WAV files: written while recording, read back as floats, cut into pieces. */
object Wav {
    const val RATE = 16_000
    private const val HEADER = 44

    /** A 44-byte header for [dataBytes] of 16-bit mono PCM at [rate]. */
    fun header(dataBytes: Int, rate: Int = RATE): ByteArray = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(dataBytes)
    }.array()

    /** Rewrites the header once the size is known (the recorder writes a placeholder first). */
    fun finish(file: File, rate: Int = RATE) {
        RandomAccessFile(file, "rw").use { f ->
            val data = (f.length() - HEADER).coerceAtLeast(0).toInt()
            f.seek(0)
            f.write(header(data, rate))
        }
    }

    /** The samples of a file written by [write]/the recorder, as floats in [-1, 1]. */
    fun readFloats(file: File): FloatArray {
        val bytes = file.readBytes()
        val start = dataOffset(bytes)
        val n = (bytes.size - start) / 2
        val buf = ByteBuffer.wrap(bytes, start, n * 2).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(n) { buf.short / 32768f }
    }

    /** Samples recorded so far in a file still being written (the recorder's placeholder header, 16-bit mono). */
    fun samplesSoFar(file: File): Int = ((file.length() - HEADER).coerceAtLeast(0) / 2).toInt()

    /** Samples [from, to) of a recorder's file, read straight from where they are (the file may still be growing). */
    fun readRange(file: File, from: Int, to: Int): FloatArray {
        val n = (to - from).coerceAtLeast(0)
        val bytes = ByteArray(n * 2)
        RandomAccessFile(file, "r").use { f ->
            f.seek(HEADER + from * 2L)
            f.readFully(bytes)
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(n) { buf.short / 32768f }
    }

    /** How loud the file is along its length, in [bars] steps from 0 to 1 (the loudest is 1): its drawing. */
    fun levels(file: File, bars: Int): FloatArray {
        val samples = readFloats(file)
        if (samples.isEmpty() || bars <= 0) return FloatArray(bars.coerceAtLeast(0))
        val out = FloatArray(bars) { b ->
            val from = (samples.size.toLong() * b / bars).toInt()
            val to = (samples.size.toLong() * (b + 1) / bars).toInt().coerceAtLeast(from + 1).coerceAtMost(samples.size)
            var sum = 0.0
            for (i in from until to) sum += samples[i] * samples[i]
            sqrt(sum / (to - from)).toFloat()
        }
        val top = out.max()
        return if (top <= 0f) out else FloatArray(bars) { out[it] / top }
    }

    fun write(file: File, samples: FloatArray, rate: Int = RATE) {
        val out = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { out.putShort((it.coerceIn(-1f, 1f) * 32767f).toInt().toShort()) }
        file.outputStream().use { it.write(header(samples.size * 2, rate)); it.write(out.array()) }
    }

    fun durationMs(file: File): Long = ((file.length() - HEADER).coerceAtLeast(0) / 2) * 1000L / RATE

    /** Finds the "data" chunk (any extra chunk before it is skipped). */
    private fun dataOffset(bytes: ByteArray): Int {
        if (bytes.size < HEADER || String(bytes, 0, 4) != "RIFF") return 0
        var i = 12
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4)
            val len = b.getInt(i + 4)
            if (id == "data") return i + 8
            i += 8 + len + (len and 1)
        }
        return HEADER
    }
}

/**
 * Long speech in pieces the models take: each cut falls in the quietest moment of the last
 * seconds before the limit (between words, not in the middle of one).
 */
object Pieces {
    /**
     * [samples] in pieces of up to [maxSeconds], each cut where she paused: in the last
     * [lookBackSeconds] before the limit, the middle of the longest quiet stretch (the end of a
     * sentence, a breath — not the short gap between two words), the latest of the longest; with no
     * pause at all, the quietest moment. Each cut is decided only from the [maxSeconds] after the
     * piece's start ([nextWhole] relies on it).
     */
    fun cut(samples: FloatArray, maxSeconds: Int, rate: Int = Wav.RATE, lookBackSeconds: Int = 10): List<FloatArray> {
        val max = maxSeconds * rate
        if (samples.size <= max) return listOf(samples)
        val out = mutableListOf<FloatArray>()
        var start = 0
        while (samples.size - start > max) {
            val limit = start + max
            val from = (limit - lookBackSeconds * rate).coerceAtLeast(start + rate)
            val at = pause(samples, from, limit, rate)
            out += samples.copyOfRange(start, at)
            start = at
        }
        if (start < samples.size) out += samples.copyOfRange(start, samples.size)
        return out.filter { it.size > rate / 4 }
    }

    /** Where to cut in [from, to): see [cut]. */
    private fun pause(s: FloatArray, from: Int, to: Int, rate: Int): Int {
        val frame = rate / 50 // 20 ms
        val n = (to - from) / frame
        if (n <= 0) return to
        val e = DoubleArray(n) { energy(s, from + it * frame, frame) }
        // Quiet: far under her voice in this stretch (a twentieth of its loud part, ~13 dB down).
        val loud = e.sorted()[(n * 0.8).toInt().coerceAtMost(n - 1)]
        val quiet = maxOf(loud * 0.05, 1e-9)
        var bestLen = 0
        var bestMid = -1
        var run = 0
        for (i in 0 until n) {
            if (e[i] <= quiet) {
                run++
                if (run >= bestLen) { bestLen = run; bestMid = i - run / 2 }
            } else {
                run = 0
            }
        }
        if (bestLen * 20 >= MIN_PAUSE_MS) return from + bestMid * frame + frame / 2
        val q = e.indices.minByOrNull { e[it] } ?: return to
        return from + q * frame + frame / 2
    }

    /** A pause this long is where she stops between things, not between two words. */
    private const val MIN_PAUSE_MS = 120

    /**
     * The first piece of [samples] that is already whole while the recording goes on: [cut] decides
     * each cut only from the [maxSeconds] after the piece's start, so this is the very piece [cut]
     * will make of the whole recording later. Null while there isn't more than [maxSeconds] yet.
     */
    fun nextWhole(samples: FloatArray, maxSeconds: Int, rate: Int = Wav.RATE): FloatArray? =
        if (samples.size <= maxSeconds * rate) null else cut(samples, maxSeconds, rate).first()

    /**
     * [samples] with her long silences made short: a quiet stretch longer than [longMs] keeps only
     * [keepMs] of itself (half at each end), so a pause still reads as a pause. Quiet here is far
     * under her voice (a fiftieth of its loud part, ~17 dB down) and never more than a whisper's
     * level. What goes to a model that hears is paid by the second and waited for by the byte;
     * thinking in silence is free. The recording itself is never changed.
     */
    fun squeeze(samples: FloatArray, rate: Int = Wav.RATE, longMs: Int = 1_000, keepMs: Int = 400): FloatArray {
        val frame = rate / 50 // 20 ms
        val n = samples.size / frame
        if (n * 20 < longMs) return samples
        val e = DoubleArray(n) { energy(samples, it * frame, frame) }
        val loud = e.sorted()[(n * 0.8).toInt().coerceAtMost(n - 1)]
        val quiet = minOf(maxOf(loud * 0.02, 1e-9), QUIET_CEILING)
        val keep = keepMs / 20
        val out = FloatArray(samples.size)
        var size = 0
        fun copy(fromFrame: Int, toFrame: Int) {
            val a = fromFrame * frame
            val b = (toFrame * frame).coerceAtMost(samples.size)
            if (b <= a) return
            System.arraycopy(samples, a, out, size, b - a)
            size += b - a
        }
        var i = 0
        while (i < n) {
            if (e[i] > quiet) { copy(i, i + 1); i++; continue }
            var j = i
            while (j < n && e[j] <= quiet) j++
            if ((j - i) * 20 > longMs) {
                copy(i, i + keep / 2)
                copy(j - (keep - keep / 2), j)
            } else {
                copy(i, j)
            }
            i = j
        }
        // The last bit shorter than a frame goes as it is.
        if (n * frame < samples.size) { System.arraycopy(samples, n * frame, out, size, samples.size - n * frame); size += samples.size - n * frame }
        return if (size == samples.size) samples else out.copyOf(size)
    }

    /** Never quieter than a whisper counts as silence (RMS ~0.006), however loud she talks. */
    private const val QUIET_CEILING = 0.006 * 0.006

    /** True when there is (almost) only silence: nothing to transcribe. */
    fun isSilent(samples: FloatArray, rate: Int = Wav.RATE): Boolean {
        if (samples.size < rate / 3) return true
        val frame = rate / 20
        var loud = 0
        var i = 0
        while (i + frame <= samples.size) {
            if (sqrt(energy(samples, i, frame)) > 0.012) loud++
            i += frame
        }
        return loud < 4
    }

    private fun energy(s: FloatArray, from: Int, n: Int): Double {
        var sum = 0.0
        for (i in from until (from + n).coerceAtMost(s.size)) sum += s[i] * s[i]
        return sum / n
    }
}
