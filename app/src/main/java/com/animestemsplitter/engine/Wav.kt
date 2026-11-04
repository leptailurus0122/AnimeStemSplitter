package com.animestemsplitter.engine

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** 16bit ステレオ WAV を少しずつ書き出す。close() でヘッダーのサイズを確定する。 */
class WavWriter(private val file: File, private val sampleRate: Int) : Closeable {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private val buf = ByteBuffer.allocate(4096 * 4).order(ByteOrder.LITTLE_ENDIAN)
    var frames = 0L
        private set

    init { out.write(header(0)) }

    fun write(interleaved: FloatArray, frameCount: Int) {
        var i = 0
        val n = frameCount * 2
        while (i < n) {
            buf.clear()
            while (i < n && buf.remaining() >= 2) {
                val v = (interleaved[i] * 32767f).coerceIn(-32768f, 32767f)
                buf.putShort(Math.round(v).toShort())
                i++
            }
            out.write(buf.array(), 0, buf.position())
        }
        frames += frameCount
    }

    override fun close() {
        out.close()
        RandomAccessFile(file, "rw").use { it.seek(0); it.write(header(frames)) }
    }

    private fun header(frames: Long): ByteArray {
        val dataBytes = frames * 4
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataBytes).coerceAtMost(0xFFFFFFFFL).toInt())
        b.put("WAVE".toByteArray()); b.put("fmt ".toByteArray())
        b.putInt(16); b.putShort(1); b.putShort(2)
        b.putInt(sampleRate); b.putInt(sampleRate * 4); b.putShort(4); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataBytes.coerceAtMost(0xFFFFFFFFL).toInt())
        return b.array()
    }
}

/** WavWriter で書いた 16bit ステレオ WAV を、位置を指定して読む（再生用）。 */
class WavReader(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val ch = raf.channel
    private var bb = ByteBuffer.allocateDirect(8192 * 4).order(ByteOrder.LITTLE_ENDIAN)
    val sampleRate: Int
    val frames: Long

    init {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        ch.read(h, 0)
        sampleRate = h.getInt(24)
        frames = (raf.length() - 44) / 4
    }

    /** start フレーム目から count フレームを dst（インターリーブ float）へ。読めた数を返す */
    fun read(start: Long, dst: FloatArray, count: Int): Int {
        val n = minOf(count.toLong(), frames - start).toInt().coerceAtLeast(0)
        if (n == 0) return 0
        if (bb.capacity() < n * 4) bb = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.clear(); bb.limit(n * 4)
        var pos = 44 + start * 4
        while (bb.hasRemaining()) {
            val r = ch.read(bb, pos)
            if (r <= 0) break
            pos += r
        }
        bb.flip()
        val got = bb.remaining() / 4
        for (i in 0 until got * 2) dst[i] = bb.getShort() / 32768f
        return got
    }

    override fun close() = raf.close()
}

/**
 * デコード済みの 44.1kHz ステレオ float を一時ファイルに貯め、メモリマップで読む。
 * 1話（24分）でも RAM をほぼ使わない。
 */
class PcmStore(private val file: File) : Closeable {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private val bb = ByteBuffer.allocate(4096 * 8).order(ByteOrder.LITTLE_ENDIAN)
    private var written = 0L

    fun append(interleaved: FloatArray, frames: Int) {
        var i = 0
        val n = frames * 2
        while (i < n) {
            bb.clear()
            while (i < n && bb.remaining() >= 4) { bb.putFloat(interleaved[i]); i++ }
            out.write(bb.array(), 0, bb.position())
        }
        written += frames
    }

    /** 書き込みを終えて読み出し用に開く */
    fun openSource(): MappedPcmSource {
        out.close()
        return MappedPcmSource(file, written)
    }

    override fun close() { runCatching { out.close() } }
}

class MappedPcmSource(file: File, override val frames: Long) : PcmSource, Closeable {
    private val raf = RandomAccessFile(file, "r")
    // 2GB を超える場合に備えて 512MB ずつに分けてマップする
    private val segFramesBits = 26
    private val segs = (0 until (((frames - 1) shr segFramesBits) + 1).toInt()).map { i ->
        val startFrame = i.toLong() shl segFramesBits
        val len = minOf(1L shl segFramesBits, frames - startFrame)
        raf.channel.map(FileChannel.MapMode.READ_ONLY, startFrame * 8, len * 8)
            .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    }

    override fun get(frame: Long, ch: Int): Float {
        val seg = (frame shr segFramesBits).toInt()
        val off = (frame and ((1L shl segFramesBits) - 1)).toInt()
        return segs[seg].get(off * 2 + ch)
    }

    override fun close() = raf.close()
}
